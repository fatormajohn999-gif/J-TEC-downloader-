import os
import re
import uuid
import asyncio
from typing import Optional, List
from urllib.parse import urlparse

from fastapi import FastAPI, HTTPException, Header, Depends, BackgroundTasks, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel
import yt_dlp

app = FastAPI(title="OmniDrop Video Downloader API", version="1.0.0")

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

def cleanup_file(filepath: str):
    """Safely removes temporary download file after streaming."""
    try:
        if os.path.exists(filepath):
            os.remove(filepath)
    except Exception:
        pass

@app.get("/health")
def health_check():
    return {"status": "ok"}

@app.post("/info", dependencies=[Depends(verify_api_key)])
async def get_video_info(payload: InfoRequest):
    url = validate_url(payload.url)

    ydl_opts = {
        'skip_download': True,
        'quiet': True,
        'no_warnings': True,
        'extract_flat': False,
        'socket_timeout': 15,
    }

    loop = asyncio.get_running_loop()

    def _extract():
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            return ydl.extract_info(url, download=False)

    try:
        # 25 second timeout on metadata extraction
        info = await asyncio.wait_for(loop.run_in_executor(None, _extract), timeout=25.0)
    except asyncio.TimeoutError:
        raise HTTPException(status_code=504, detail="Timeout while retrieving video information. Please try again.")
    except yt_dlp.utils.DownloadError as e:
        err_msg = str(e)
        if "private" in err_msg.lower() or "login required" in err_msg.lower():
            raise HTTPException(status_code=403, detail="This video is private or requires authentication to view.")
        if "not found" in err_msg.lower() or "404" in err_msg.lower() or "does not exist" in err_msg.lower():
            raise HTTPException(status_code=404, detail="The requested video was not found or has been deleted.")
        raise HTTPException(status_code=400, detail="Could not extract video details. Please ensure the link is public and valid.")
    except Exception:
        raise HTTPException(status_code=500, detail="An error occurred while inspecting the video link.")

    if not info:
        raise HTTPException(status_code=404, detail="Unable to retrieve video information.")

    formats = info.get("formats", []) or []
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
    
    # Always include audio-only option
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
        "title": info.get("title") or "Video Download",
        "thumbnail": info.get("thumbnail") or "",
        "duration": info.get("duration") or 0,
        "uploader": info.get("uploader") or info.get("channel") or info.get("creator") or "Unknown",
        "qualities": qualities,
        "platform": platform,
        "id": info.get("id") or "",
    }

@app.post("/download", dependencies=[Depends(verify_api_key)])
async def download_video(payload: DownloadRequest, background_tasks: BackgroundTasks):
    url = validate_url(payload.url)
    quality = (payload.quality or "best").lower().strip()

    file_id = str(uuid.uuid4())[:8]
    output_template = os.path.join(TEMP_DOWNLOAD_DIR, f"%(title).80s_{file_id}.%(ext)s")

    # Select yt-dlp format specification
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

    loop = asyncio.get_running_loop()

    def _do_download():
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            meta = ydl.extract_info(url, download=True)
            # Find the actual written filename
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
        # 120 second download timeout
        filepath, raw_title = await asyncio.wait_for(loop.run_in_executor(None, _do_download), timeout=120.0)
    except asyncio.TimeoutError:
        raise HTTPException(status_code=504, detail="Download timed out. The video might be too large or connection too slow.")
    except yt_dlp.utils.DownloadError as e:
        err_msg = str(e)
        if "private" in err_msg.lower():
            raise HTTPException(status_code=403, detail="Cannot download private video.")
        raise HTTPException(status_code=400, detail="Download failed. Please check the link and try again.")
    except Exception:
        raise HTTPException(status_code=500, detail="Failed to process video download.")

    if not os.path.exists(filepath):
        # Fallback search if extension merged
        base = os.path.splitext(filepath)[0]
        for candidate in [base + ".mp4", base + ".mkv", base + ".webm", base + ".mp3"]:
            if os.path.exists(candidate):
                filepath = candidate
                break

    if not os.path.exists(filepath):
        raise HTTPException(status_code=500, detail="Output file could not be generated.")

    # Schedule deletion after response finishes streaming
    background_tasks.add_task(cleanup_file, filepath)

    # Sanitize title for filename
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
    """Never return internal stack traces to users."""
    return JSONResponse(
        status_code=500,
        content={"detail": "An unexpected error occurred while processing your request."}
    )
