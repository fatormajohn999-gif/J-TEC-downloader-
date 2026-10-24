import os
import re
import json
import uuid
import shutil
import tempfile
import asyncio
import zipfile
import subprocess
import urllib.request
import urllib.error
from typing import Optional, List, Dict, Any, Tuple
from urllib.parse import urlparse

from fastapi import FastAPI, HTTPException, Header, Depends, BackgroundTasks, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel
import yt_dlp

app = FastAPI(title="OmniDrop Video & Photo Downloader API", version="1.2.0")

# CORS middleware allowing all origins
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

APP_API_KEY = os.getenv("APP_API_KEY", "").strip()
TEMP_DOWNLOAD_DIR = "/tmp/omnidrop_downloads"
os.makedirs(TEMP_DOWNLOAD_DIR, exist_ok=True)

ALLOWED_DOMAINS = [
    "tiktok.com",
    "vt.tiktok.com",
    "vm.tiktok.com",
    "youtube.com",
    "youtu.be",
    "instagram.com",
    "facebook.com",
    "fb.watch",
]

DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

class InfoRequest(BaseModel):
    url: str

class DownloadRequest(BaseModel):
    url: str
    quality: Optional[str] = "best"

def verify_api_key(x_api_key: Optional[str] = Header(None)):
    """Verifies x-api-key header if APP_API_KEY environment variable is configured."""
    if APP_API_KEY and x_api_key != APP_API_KEY:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing API key (x-api-key header required)"
        )

def validate_url(url: str) -> str:
    """Validates URL format and confirms it belongs to allowed domains."""
    if not url or not isinstance(url, str):
        raise HTTPException(status_code=400, detail="Missing or empty URL")
    
    clean_url = url.strip()
    try:
        parsed = urlparse(clean_url)
        if not parsed.scheme or not parsed.netloc:
            raise ValueError()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid URL format provided")
    
    hostname = (parsed.hostname or "").lower()
    is_allowed = any(hostname == domain or hostname.endswith("." + domain) for domain in ALLOWED_DOMAINS)
    if not is_allowed:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported domain '{hostname}'. Allowed domains: TikTok, YouTube, Instagram, and Facebook."
        )
    return clean_url

class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Urllib redirect handler that stops automatic redirection and exposes Location."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def resolve_tiktok_url(url: str) -> str:
    """
    Resolves short links (vm.tiktok.com, vt.tiktok.com) by following redirects manually,
    one hop at a time with no automatic redirect, up to 5 hops, using a desktop browser
    User-Agent, and stopping at the first Location that contains /photo/ or /video/,
    so a captcha or login redirect does not hide the real post link.
    """
    if not any(d in url.lower() for d in ["tiktok.com", "vt.tiktok.com", "vm.tiktok.com"]):
        return url

    current_url = url
    opener = urllib.request.build_opener(NoRedirectHandler)

    for hop in range(5):
        if "/photo/" in current_url.lower() or "/video/" in current_url.lower():
            break

        try:
            req = urllib.request.Request(
                current_url,
                headers={
                    "User-Agent": DESKTOP_UA,
                    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language": "en-US,en;q=0.9",
                }
            )
            try:
                with opener.open(req, timeout=8) as resp:
                    final_url = resp.geturl()
                    if "/photo/" in final_url.lower() or "/video/" in final_url.lower():
                        current_url = final_url
                    break
            except urllib.error.HTTPError as e:
                if e.code in (301, 302, 303, 307, 308):
                    location = e.headers.get("Location")
                    if location:
                        if location.startswith("/"):
                            parsed_u = urlparse(current_url)
                            location = f"{parsed_u.scheme}://{parsed_u.netloc}{location}"
                        current_url = location
                        if "/photo/" in current_url.lower() or "/video/" in current_url.lower():
                            break
                        continue
                break
        except Exception as ex:
            print(f"[resolve_tiktok_url] hop {hop} failed: {ex}")
            break

    return current_url

def cleanup_file(filepath: str):
    """Safely removes temporary download file after streaming."""
    try:
        if os.path.exists(filepath):
            os.remove(filepath)
    except Exception:
        pass

def cleanup_photo_files(zip_path: str, temp_dir: str):
    """Safely removes temporary ZIP and images folder after streaming."""
    try:
        if os.path.exists(zip_path):
            os.remove(zip_path)
    except Exception:
        pass
    try:
        if os.path.exists(temp_dir):
            shutil.rmtree(temp_dir, ignore_errors=True)
    except Exception:
        pass

def is_valid_image_url(url_str: str) -> bool:
    """Checks if a URL points to a standard image file."""
    u = url_str.strip()
    if not (u.startswith("http://") or u.startswith("https://")):
        return False
    path = urlparse(u).path.lower()
    return any(path.endswith(ext) for ext in [".jpg", ".jpeg", ".png", ".webp", ".heic"])

def fetch_tiktok_rehydration_data(target_url: str) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    """
    Fallback method: fetches TikTok post page with desktop User-Agent,
    finds <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">,
    and extracts image URLs and metadata.
    """
    try:
        req = urllib.request.Request(
            target_url,
            headers={
                "User-Agent": DESKTOP_UA,
                "Accept-Language": "en-US,en;q=0.9",
                "Referer": "https://www.tiktok.com/",
            }
        )
        with urllib.request.urlopen(req, timeout=12) as resp:
            html = resp.read().decode("utf-8", errors="replace")

        if "verify-bar" in html or "captcha" in html.lower() or "security check" in html.lower():
            print("[fetch_tiktok_rehydration_data] TikTok captcha/block page encountered")
            return None, "captcha"

        match = re.search(r'<script\s+id="__UNIVERSAL_DATA_FOR_REHYDRATION__"\s+type="application/json">(.*?)</script>', html, re.DOTALL)
        if not match:
            print("[fetch_tiktok_rehydration_data] __UNIVERSAL_DATA_FOR_REHYDRATION__ not found")
            return None, "no_data"

        data = json.loads(match.group(1))
        scope = data.get("__DEFAULT_SCOPE__", {})
        video_detail = scope.get("webapp.video-detail", {})
        item_info = video_detail.get("itemInfo", {})
        item_struct = item_info.get("itemStruct", {})

        title = item_struct.get("desc") or "TikTok photos"
        author = item_struct.get("author", {})
        uploader = author.get("nickname") or author.get("uniqueId") or "Creator"

        image_post = item_struct.get("imagePost", {})
        raw_images = image_post.get("images", []) or []

        image_urls = []
        for img in raw_images:
            url_list = img.get("imageURL", {}).get("urlList", [])
            if url_list:
                image_urls.append(url_list[0])

        if not image_urls:
            return None, "no_photos"

        return {
            "title": title,
            "uploader": uploader,
            "thumbnail": image_urls[0],
            "imageUrls": image_urls,
            "count": len(image_urls)
        }, None
    except Exception as e:
        print(f"[fetch_tiktok_rehydration_data] Error: {e}")
        return None, str(e)

def get_gallery_info(target_url: str) -> Optional[Dict[str, Any]]:
    """
    Extracts photo slideshow metadata from TikTok.
    1. Runs gallery-dl -g to get image list and count.
    2. Runs gallery-dl -j and parses the whole stdout with a single json.loads.
    3. If metadata parse fails, still returns type 'photo' with title 'TikTok photos',
       count from URL list and first image URL as thumbnail.
    4. If gallery-dl returns no images, falls back to __UNIVERSAL_DATA_FOR_REHYDRATION__.
    """
    # 1. Run gallery-dl -g
    cmd_g = ["gallery-dl", "-g", "--socket-timeout", "15", target_url]
    res_g = subprocess.run(cmd_g, capture_output=True, text=True, timeout=25)

    image_urls = []
    if res_g.returncode == 0 and res_g.stdout:
        for line in res_g.stdout.splitlines():
            clean_line = line.strip()
            if is_valid_image_url(clean_line):
                image_urls.append(clean_line)
    else:
        print(f"[gallery-dl -g] cmd: {' '.join(cmd_g)}, code: {res_g.returncode}")
        if res_g.stderr:
            print(f"[gallery-dl -g stderr]: {res_g.stderr[-1500:]}")

    title = ""
    uploader = ""
    thumbnail = ""

    # 2. Run gallery-dl -j if image_urls found
    if image_urls:
        cmd_j = ["gallery-dl", "-j", "--socket-timeout", "15", target_url]
        res_j = subprocess.run(cmd_j, capture_output=True, text=True, timeout=25)
        if res_j.returncode == 0 and res_j.stdout:
            try:
                parsed_json = json.loads(res_j.stdout)
                if isinstance(parsed_json, list):
                    for item in parsed_json:
                        meta = None
                        if isinstance(item, list):
                            for elem in item:
                                if isinstance(elem, dict):
                                    meta = elem
                                    break
                        elif isinstance(item, dict):
                            meta = item

                        if meta:
                            if not title:
                                title = meta.get("description") or meta.get("desc") or meta.get("title") or ""
                            if not uploader:
                                author = meta.get("author")
                                if isinstance(author, dict):
                                    uploader = author.get("nickname") or author.get("unique_id") or ""
                                elif isinstance(author, str):
                                    uploader = author
                                if not uploader:
                                    uploader = meta.get("uploader") or ""
                            if not thumbnail:
                                thumbnail = meta.get("thumbnail") or meta.get("url") or ""
            except Exception as e:
                print(f"[gallery-dl -j] JSON parse error: {e}")
        else:
            print(f"[gallery-dl -j] code: {res_j.returncode}")
            if res_j.stderr:
                print(f"[gallery-dl -j stderr]: {res_j.stderr[-1500:]}")

        return {
            "type": "photo",
            "title": title or "TikTok photos",
            "uploader": uploader or "Creator",
            "thumbnail": thumbnail or (image_urls[0] if image_urls else ""),
            "imageCount": len(image_urls),
            "platform": "tiktok"
        }

    # 3. Fallback: __UNIVERSAL_DATA_FOR_REHYDRATION__
    fallback_data, err_reason = fetch_tiktok_rehydration_data(target_url)
    if fallback_data:
        return {
            "type": "photo",
            "title": fallback_data.get("title") or "TikTok photos",
            "uploader": fallback_data.get("uploader") or "Creator",
            "thumbnail": fallback_data.get("thumbnail") or "",
            "imageCount": fallback_data.get("count", 0),
            "platform": "tiktok"
        }

    return None

def download_gallery_photos(target_url: str, title: str) -> Tuple[str, str, str]:
    """
    Downloads all slideshow images, packs them into a ZIP, and preserves each image's
    real extension (image_01.jpeg, image_02.webp, etc.).
    Falls back to __UNIVERSAL_DATA_FOR_REHYDRATION__ if gallery-dl fails.
    """
    temp_dir = tempfile.mkdtemp(prefix="omnidrop_photos_")
    try:
        # 1. Try gallery-dl download
        cmd = ["gallery-dl", "-d", temp_dir, "--socket-timeout", "30", target_url]
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=120)

        found_files = []
        for root, _, files in os.walk(temp_dir):
            for f in sorted(files):
                ext = os.path.splitext(f)[1].lower()
                if ext in [".jpg", ".jpeg", ".png", ".webp", ".heic", ".bmp"]:
                    found_files.append(os.path.join(root, f))

        # 2. If gallery-dl returned no files, log and try scraping fallback
        if not found_files:
            print(f"[download_gallery_photos] gallery-dl failed. cmd: {' '.join(cmd)}, code: {res.returncode}")
            if res.stderr:
                print(f"[download_gallery_photos stderr]: {res.stderr[-1500:]}")

            fallback_data, reason = fetch_tiktok_rehydration_data(target_url)
            if fallback_data and fallback_data.get("imageUrls"):
                image_urls = fallback_data["imageUrls"]
                for idx, img_url in enumerate(image_urls, start=1):
                    # Determine extension
                    path_ext = os.path.splitext(urlparse(img_url).path)[1].lower()
                    if path_ext not in [".jpg", ".jpeg", ".png", ".webp", ".heic"]:
                        path_ext = ".jpeg"

                    dest_file = os.path.join(temp_dir, f"dl_{idx:03d}{path_ext}")
                    req = urllib.request.Request(
                        img_url,
                        headers={
                            "User-Agent": DESKTOP_UA,
                            "Referer": "https://www.tiktok.com/",
                        }
                    )
                    with urllib.request.urlopen(req, timeout=15) as resp, open(dest_file, "wb") as out_f:
                        out_f.write(resp.read())
                    found_files.append(dest_file)
            else:
                if reason == "captcha":
                    raise HTTPException(
                        status_code=403,
                        detail="TikTok blocked the server from reading this post. Try again later."
                    )
                elif reason == "no_photos":
                    raise HTTPException(status_code=400, detail="This post has no photos")
                else:
                    raise HTTPException(status_code=400, detail="TikTok blocked the server from reading this post. Try again later.")

        if not found_files:
            raise HTTPException(status_code=400, detail="This post has no photos")

        found_files.sort()

        # Sanitize title for filename
        clean_title = re.sub(r'[^a-zA-Z0-9_\-\. ]', '_', title).strip() or "TikTok_photos"
        clean_title = clean_title[:70]
        zip_id = str(uuid.uuid4())[:8]
        zip_filename = f"{clean_title} [TikTok photos].zip"
        zip_path = os.path.join(TEMP_DOWNLOAD_DIR, f"{clean_title}_{zip_id}.zip")

        with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED) as zip_file:
            for idx, src_file in enumerate(found_files, start=1):
                ext = os.path.splitext(src_file)[1].lower() or ".jpeg"
                archive_name = f"image_{idx:02d}{ext}"
                zip_file.write(src_file, arcname=archive_name)

        return zip_path, zip_filename, temp_dir
    except HTTPException:
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise
    except Exception as e:
        shutil.rmtree(temp_dir, ignore_errors=True)
        print(f"[download_gallery_photos] General error: {e}")
        raise HTTPException(status_code=400, detail="Could not get the photos from this post.")

@app.get("/health")
def health_check():
    return {"status": "ok"}

@app.post("/info", dependencies=[Depends(verify_api_key)])
async def get_video_info(payload: InfoRequest):
    url = validate_url(payload.url)
    loop = asyncio.get_running_loop()

    # If TikTok link, resolve short redirects (vm.tiktok.com, vt.tiktok.com)
    resolved_url = url
    if any(d in url.lower() for d in ["tiktok.com", "vt.tiktok.com", "vm.tiktok.com"]):
        resolved_url = await loop.run_in_executor(None, resolve_tiktok_url, url)

    # 1. Detect photo post by /photo/ in URL path
    if "/photo/" in resolved_url.lower():
        try:
            gallery_data = await asyncio.wait_for(
                loop.run_in_executor(None, get_gallery_info, resolved_url),
                timeout=25.0
            )
            if gallery_data:
                return gallery_data
        except asyncio.TimeoutError:
            raise HTTPException(status_code=504, detail="Timeout while retrieving photos from post.")
        except Exception as e:
            print(f"[/info] Error getting photo info: {e}")

        # Return minimal photo response rather than failing so user can still attempt download
        return {
            "type": "photo",
            "title": "TikTok photos",
            "uploader": "Creator",
            "thumbnail": "",
            "imageCount": 1,
            "platform": "tiktok"
        }

    # 2. Standard video extraction with yt-dlp
    ydl_opts = {
        'skip_download': True,
        'quiet': True,
        'no_warnings': True,
        'extract_flat': False,
        'socket_timeout': 15,
    }

    def _extract():
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            return ydl.extract_info(url, download=False)

    try:
        info = await asyncio.wait_for(loop.run_in_executor(None, _extract), timeout=25.0)
    except yt_dlp.utils.DownloadError as e:
        err_msg = str(e)
        # Check if yt-dlp failed because it's a photo slideshow
        if "tiktok" in url.lower() or "no video formats" in err_msg.lower():
            try:
                gallery_data = await asyncio.wait_for(
                    loop.run_in_executor(None, get_gallery_info, resolved_url),
                    timeout=20.0
                )
                if gallery_data:
                    return gallery_data
            except Exception:
                pass

        if "private" in err_msg.lower() or "login required" in err_msg.lower():
            raise HTTPException(status_code=403, detail="This video is private or requires authentication to view.")
        if "not found" in err_msg.lower() or "404" in err_msg.lower() or "does not exist" in err_msg.lower():
            raise HTTPException(status_code=404, detail="The requested video was not found or has been deleted.")
        raise HTTPException(status_code=400, detail="Could not extract video details. Please ensure the link is public and valid.")
    except asyncio.TimeoutError:
        raise HTTPException(status_code=504, detail="Timeout while retrieving video information. Please try again.")
    except Exception:
        raise HTTPException(status_code=500, detail="An error occurred while inspecting the video link.")

    if not info:
        raise HTTPException(status_code=404, detail="Unable to retrieve video information.")

    formats = info.get("formats", []) or []

    # If yt-dlp returned info without video formats on TikTok, check if photo post
    if not formats and "tiktok" in url.lower():
        try:
            gallery_data = await asyncio.wait_for(
                loop.run_in_executor(None, get_gallery_info, resolved_url),
                timeout=20.0
            )
            if gallery_data:
                return gallery_data
        except Exception:
            pass

    detected_heights = set()
    for f in formats:
        h = f.get("height")
        if h and isinstance(h, int):
            detected_heights.add(h)

    qualities = []
    for h in [1080, 720, 480, 360]:
        if any(height >= h for height in detected_heights):
            qualities.append(f"{h}p")

    if not qualities:
        qualities = ["best"]
    else:
        if "best" not in qualities:
            qualities.insert(0, "best")

    # Always include audio-only option for videos
    qualities.append("audio")

    # Detect platform
    extractor = (info.get("extractor_key") or info.get("extractor") or "").lower()
    platform = "video"
    if "tiktok" in extractor or "tiktok" in url:
        platform = "tiktok"
    elif "youtube" in extractor or "youtu" in url:
        platform = "youtube"
    elif "instagram" in extractor or "instagr" in url:
        platform = "instagram"
    elif "facebook" in extractor or "fb" in url:
        platform = "facebook"

    return {
        "type": "video",
        "title": info.get("title") or "Video Download",
        "thumbnail": info.get("thumbnail") or "",
        "duration": info.get("duration") or 0,
        "uploader": info.get("uploader") or info.get("channel") or info.get("creator") or "Unknown",
        "qualities": qualities,
        "platform": platform,
        "id": info.get("id") or "",
    }

@app.post("/download", dependencies=[Depends(verify_api_key)])
async def download_media(payload: DownloadRequest, background_tasks: BackgroundTasks):
    url = validate_url(payload.url)
    quality = (payload.quality or "best").lower().strip()
    loop = asyncio.get_running_loop()

    # Resolve redirects for TikTok links
    resolved_url = url
    if any(d in url.lower() for d in ["tiktok.com", "vt.tiktok.com", "vm.tiktok.com"]):
        resolved_url = await loop.run_in_executor(None, resolve_tiktok_url, url)

    # 1. Check if photo post
    if "/photo/" in resolved_url.lower():
        try:
            info = await loop.run_in_executor(None, get_gallery_info, resolved_url)
            title = info.get("title") if info else "TikTok Photos"
            zip_path, zip_filename, temp_dir = await asyncio.wait_for(
                loop.run_in_executor(None, download_gallery_photos, resolved_url, title),
                timeout=120.0
            )
            background_tasks.add_task(cleanup_photo_files, zip_path, temp_dir)

            return FileResponse(
                path=zip_path,
                filename=zip_filename,
                media_type="application/zip",
                headers={
                    "Content-Disposition": f'attachment; filename="{zip_filename}"'
                }
            )
        except HTTPException:
            raise
        except asyncio.TimeoutError:
            raise HTTPException(status_code=504, detail="Photo download timed out. Connection may be too slow.")
        except Exception as e:
            print(f"[/download] Photo download exception: {e}")
            raise HTTPException(status_code=400, detail="Could not get the photos from this post.")

    # 2. Standard video download via yt-dlp
    file_id = str(uuid.uuid4())[:8]
    output_template = os.path.join(TEMP_DOWNLOAD_DIR, f"%(title).80s_{file_id}.%(ext)s")

    if quality == "audio":
        format_spec = "ba/b"
    elif quality == "1080p":
        format_spec = "bv*[height<=1080]+ba/b[height<=1080]/b"
    elif quality == "720p":
        format_spec = "bv*[height<=720]+ba/b[height<=720]/b"
    elif quality == "480p":
        format_spec = "bv*[height<=480]+ba/b[height<=480]/b"
    elif quality == "360p":
        format_spec = "bv*[height<=360]+ba/b[height<=360]/b"
    else:
        format_spec = "bv*+ba/b"

    ydl_opts = {
        'format': format_spec,
        'outtmpl': output_template,
        'quiet': True,
        'no_warnings': True,
        'merge_output_format': 'mp3' if quality == 'audio' else 'mp4',
        'socket_timeout': 30,
    }

    if quality == "audio":
        ydl_opts['postprocessors'] = [{
            'key': 'FFmpegExtractAudio',
            'preferredcodec': 'mp3',
            'preferredquality': '192',
        }]

    def _do_download():
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            meta = ydl.extract_info(url, download=True)
            if 'requested_downloads' in meta and meta['requested_downloads']:
                filename = meta['requested_downloads'][0]['filepath']
            else:
                filename = ydl.prepare_filename(meta)
                if quality == 'audio':
                    base, _ = os.path.splitext(filename)
                    filename = base + ".mp3"
                else:
                    base, _ = os.path.splitext(filename)
                    if os.path.exists(base + ".mp4"):
                        filename = base + ".mp4"
            return filename, meta.get('title') or "video"

    try:
        filepath, raw_title = await asyncio.wait_for(loop.run_in_executor(None, _do_download), timeout=120.0)
    except yt_dlp.utils.DownloadError as e:
        err_msg = str(e)
        # If yt-dlp failed because it's a photo post, fallback to photo download
        if "tiktok" in url.lower() or "no video formats" in err_msg.lower():
            try:
                info = await loop.run_in_executor(None, get_gallery_info, resolved_url)
                title = info.get("title") if info else "TikTok Photos"
                zip_path, zip_filename, temp_dir = await asyncio.wait_for(
                    loop.run_in_executor(None, download_gallery_photos, resolved_url, title),
                    timeout=120.0
                )
                background_tasks.add_task(cleanup_photo_files, zip_path, temp_dir)
                return FileResponse(
                    path=zip_path,
                    filename=zip_filename,
                    media_type="application/zip",
                    headers={
                        "Content-Disposition": f'attachment; filename="{zip_filename}"'
                    }
                )
            except HTTPException:
                raise
            except Exception:
                pass

        if "private" in err_msg.lower():
            raise HTTPException(status_code=403, detail="Cannot download private video.")
        raise HTTPException(status_code=400, detail="Download failed. Please check the link and try again.")
    except asyncio.TimeoutError:
        raise HTTPException(status_code=504, detail="Download timed out. The video might be too large or connection too slow.")
    except Exception:
        raise HTTPException(status_code=500, detail="Failed to process video download.")

    if not os.path.exists(filepath):
        base = os.path.splitext(filepath)[0]
        for candidate in [base + ".mp4", base + ".mkv", base + ".webm", base + ".mp3"]:
            if os.path.exists(candidate):
                filepath = candidate
                break

    if not os.path.exists(filepath):
        raise HTTPException(status_code=500, detail="Output file could not be generated.")

    background_tasks.add_task(cleanup_file, filepath)

    safe_title = re.sub(r'[^a-zA-Z0-9_\-\. ]', '_', raw_title).strip() or "video"
    extension = os.path.splitext(filepath)[1] or (".mp3" if quality == "audio" else ".mp4")
    download_filename = f"{safe_title}{extension}"
    media_type = "audio/mpeg" if quality == "audio" else "video/mp4"

    return FileResponse(
        path=filepath,
        filename=download_filename,
        media_type=media_type,
        headers={
            "Content-Disposition": f'attachment; filename="{download_filename}"'
        }
    )

@app.exception_handler(HTTPException)
async def http_exception_handler(request, exc: HTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content={"detail": exc.detail}
    )

@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    return JSONResponse(
        status_code=500,
        content={"detail": "An unexpected error occurred while processing your request."}
    )
