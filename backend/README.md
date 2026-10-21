# OmniDrop Backend (FastAPI + yt-dlp + FFmpeg)

Production-ready backend API service for OmniDrop that inspects and downloads full-quality, watermark-free videos from TikTok, YouTube, Instagram, and Facebook using yt-dlp and FFmpeg.

---

## 🚀 Features

- **Fast & Resilient**: Built with Python 3.11, FastAPI, and async thread workers.
- **Watermark-Free Downloads**: Optimized yt-dlp extraction specifically designed for unwatermarked TikTok videos, high-definition YouTube, Instagram Reels, and Facebook videos.
- **FFmpeg Integration**: Merges best audio and video streams on the fly into clean `.mp4` or extracts `.mp3`.
- **Domain Whitelist**: Restricts requests to verified domains (`tiktok.com`, `youtube.com`, `youtu.be`, `instagram.com`, `facebook.com`, `fb.watch`).
- **Zero-Storage Footprint**: Files are automatically deleted immediately after streaming completes.
- **Safe Error Responses**: Clean JSON errors (no internal stack traces exposed).
- **Optional API Key Security**: Protect your deployment with an `APP_API_KEY` environment variable.

---

## 🛠️ Deploy to Render (Docker Web Service)

1. Create a free account on [Render.com](https://render.com).
2. Click **New +** → **Web Service**.
3. Connect your GitHub repository containing this project.
4. Configure the service settings:
   - **Name**: `omnidrop-api` (or any name you prefer)
   - **Environment**: `Docker`
   - **Root Directory**: `backend` *(Important: set this to `backend` so Render builds using `backend/Dockerfile`)*
   - **Instance Type**: Free or Starter
5. Under **Environment Variables**, add:
   - `PORT`: `8000` (Render will also provide `$PORT` automatically)
   - `APP_API_KEY`: *(Optional)* If set, clients must send this key in the `x-api-key` header.
6. Click **Create Web Service**.
7. Once deployed, copy your Render service URL (e.g., `https://omnidrop-api.onrender.com`).
8. Open the OmniDrop frontend Settings tab and paste your Render URL in the **Backend URL** field.

> **Note on Render Free Tier**: Render spins down inactive free web services after 15 minutes of inactivity. When a request comes in, it may take 30–50 seconds to wake up. OmniDrop includes automatic health-check polling and displays a friendly *"Waking up server, please wait..."* message.

---

## 💻 Local Development

### Option 1: Docker
```bash
docker build -t omnidrop-backend .
docker run -p 8000:8000 omnidrop-backend
```

### Option 2: Python Virtual Environment
```bash
# Ensure ffmpeg is installed on your OS:
# macOS: brew install ffmpeg
# Ubuntu/Debian: sudo apt update && sudo apt install -y ffmpeg

python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

---

## 📡 API Endpoints

### 1. Health Check
`GET /health`

Response:
```json
{
  "status": "ok"
}
```

### 2. Video Inspection
`POST /info`

Headers:
- `Content-Type: application/json`
- `x-api-key: <key>` *(if APP_API_KEY configured)*

Body:
```json
{
  "url": "https://www.tiktok.com/@user/video/123456789"
}
```

Response:
```json
{
  "title": "Amazing dance choreography",
  "thumbnail": "https://p16-sign.tiktokcdn.com/...",
  "duration": 45,
  "uploader": "@creator",
  "qualities": ["best", "1080p", "720p", "audio"],
  "platform": "tiktok",
  "id": "123456789"
}
```

### 3. Video Download
`POST /download`

Body:
```json
{
  "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "quality": "1080p"
}
```

Response:
- Binary video file stream (`video/mp4` or `audio/mpeg`) with `Content-Disposition: attachment; filename="..."`.
