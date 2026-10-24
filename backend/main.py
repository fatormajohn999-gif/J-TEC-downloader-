import os
import re
import json
import uuid
import shutil
import tempfile
import asyncio
import zipfile
import urllib.request
from typing import Optional, List
from urllib.parse import urlparse

from fastapi import FastAPI, HTTPException, Header, Depends, BackgroundTasks, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel
import subprocess
import yt_dlp

app = FastAPI(title="OmniDrop Video & Photo Downloader API", version="1.1.0")

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

def resolve_tiktok_url(url: str) -> str:
    """Resolves short TikTok links (vm.tiktok.com / vt.tiktok.com) by following redirects."""
    try:
        req = urllib.request.Request(
            url,
            headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.geturl()
    except Exception:
        return url

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

def get_gallery_info(target_url: str):
    """Extracts photo slideshow metadata from TikTok using gallery-dl."""
    cmd = ["gallery-dl", "-j", "--socket-timeout", "15", target_url]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=25)
    
    items = []
    for line in res.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            data = json.loads(line)
            if isinstance(data, list) and len(data) >= 2 and isinstance(data[1], dict):
                items.append(data[1])
            elif isinstance(data, dict):
                items.append(data)
        except Exception:
            continue

    if not items:
        return None

    first = items[0]
    title = first.get("description") or first.get("desc") or first.get("title") or "TikTok Photos"
    author_obj = first.get("author") or {}
    uploader = ""
    if isinstance(author_obj, dict):
        uploader = author_obj.get("nickname") or author_obj.get("unique_id") or ""
    if not uploader:
        uploader = first.get("uploader") or first.get("author") or "Creator"

    thumbnail = first.get("url") or first.get("thumbnail") or ""
    image_count = len(items)

    return {
        "type": "photo",
        "title": title,
        "uploader": uploader,
        "thumbnail": thumbnail,
        "imageCount": image_count,
        "platform": "tiktok"
    }

def download_gallery_photos(target_url: str, title: str):
    """Downloads all slideshow images with gallery-dl, renames to image_01.jpg, packs into ZIP."""
    temp_dir = tempfile.mkdtemp(prefix="omnidrop_photos_")
    try:
        cmd = ["gallery-dl", "-d", temp_dir, "--socket-timeout", "30", target_url]
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        
        found_files = []
        for root, _, files in os.walk(temp_dir):
            for f in sorted(files):
                ext = os.path.splitext(f)[1].lower()
                if ext in [".jpg", ".jpeg", ".png", ".webp", ".heic", ".bmp"]:
                    found_files.append(os.path.join(root, f))

        if not found_files:
            raise Exception("No photos found in gallery download output")

        found_files.sort()

        # Sanitize title for filename
        clean_title = re.sub(r'[^a-zA-Z0-9_\-\. ]', '_', title).strip() or "TikTok_photos"
        clean_title = clean_title[:70]
        zip_id = str(uuid.uuid4())[:8]
        zip_filename = f"{clean_title} [TikTok photos].zip"
        zip_path = os.path.join(TEMP_DOWNLOAD_DIR, f"{clean_title}_{zip_id}.zip")

        with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED) as zip_file:
            for idx, src_file in enumerate(found_files, start=1):
                archive_name = f"image_{idx:02d}.jpg"
                zip_file.write(src_file, arcname=archive_name)

        return zip_path, zip_filename, temp_dir
    except Exception as e:
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise e

@app.get("/health")
def health_check():
    return {"status": "ok"}

@app.post("/info", dependencies=[Depends(verify_api_key)])
async def get_video_info(payload: InfoRequest):
    url = validate_url(payload.url)
    loop = asyncio.get_running_loop()

    # If TikTok link, resolve potential short redirects (vm.tiktok.com, vt.tiktok.com)
    resolved_url = url
    if "tiktok.com" in url.lower():
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
        except Exception:
            pass
        raise HTTPException(status_code=400, detail="Could not get the photos from this post.")

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
    if "tiktok.com" in url.lower():
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
        except asyncio.TimeoutError:
            raise HTTPException(status_code=504, detail="Photo download timed out. Connection may be too slow.")
        except Exception:
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
        # If yt-dlp failed because it's a photo post, fallback to gallery-dl
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

@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    return JSONResponse(
        status_code=500,
        content={"detail": "An unexpected error occurred while processing your request."}
    )
