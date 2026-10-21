# OmniDrop - Personal Video Downloader

A full-stack, mobile-first personal video downloader designed for TikTok, YouTube, Instagram, and Facebook. Downloads full quality videos with zero watermark and saves them directly to your phone.

---

## 🏗️ Repository Architecture

This repository contains both frontend and backend in a unified structure:

```
├── backend/                  # Python 3.11, FastAPI, yt-dlp, FFmpeg
│   ├── Dockerfile            # Container definition (python:3.11-slim + ffmpeg)
│   ├── main.py               # FastAPI application (/health, /info, /download)
│   ├── requirements.txt      # Python dependencies
│   └── README.md             # Backend setup & Render deployment guide
│
├── app/                      # Capacitor & native Android files
│   ├── android-native/       # Native Kotlin source files
│   │   ├── VideoDownloaderPlugin.kt   # Capacitor Plugin bridge
│   │   ├── FloatingBubbleService.kt   # "Draw over other apps" overlay bubble
│   │   ├── ClipboardBridgeActivity.kt # Transparent clipboard reader
│   │   ├── ShareActivity.kt           # ACTION_SEND text/plain receiver
│   │   ├── NotificationHelper.kt      # Persistent & finish notifications
│   │   └── AndroidManifest_snippet.xml# Android permissions and components
│   └── capacitor.config.ts   # Capacitor configuration
│
├── src/                      # React 19 + TypeScript + Vite + Tailwind CSS
│   ├── components/           # Mobile-first UI screens & components
│   ├── plugins/              # Capacitor TypeScript plugin bindings
│   ├── services/             # API clients, health monitoring, stream downloader
│   └── hooks/                # PWA installation & clipboard detection
│
├── .github/workflows/        # Automated CI/CD Pipelines
│   ├── deploy-pages.yml      # Deploys web PWA to GitHub Pages on push to main
│   └── build-apk.yml         # Builds Android Debug APK via Capacitor & Gradle
│
└── server.ts                 # Full-stack Node/Express dev server with yt-dlp
```

---

## ⚡ Quick Start (Local Development)

### Run the App Locally
```bash
# 1. Install dependencies
npm install

# 2. Start full-stack local server
npm run dev
```
Open [http://localhost:3000](http://localhost:3000) to use the app in your browser or mobile emulator.

---

## 🚀 Deployment Guide

### 1. Deploy the Backend to Render (Docker Web Service)

1. Sign up or log into [Render.com](https://render.com).
2. Click **New +** → **Web Service**.
3. Connect your GitHub repository.
4. Set the following settings:
   - **Name**: `omnidrop-api`
   - **Environment**: `Docker`
   - **Root Directory**: `backend` *(CRITICAL: must be set to `backend`)*
   - **Branch**: `main`
   - **Instance Type**: Free or Starter
5. Under **Environment Variables**:
   - `PORT`: `8000` (Render binds this port automatically)
   - `APP_API_KEY`: *(Optional)* Secret key required in `x-api-key` header
6. Click **Create Web Service**.
7. Once deployed, copy your Render URL (e.g., `https://omnidrop-api.onrender.com`).
8. Open the OmniDrop frontend, go to **Settings**, and paste your Render URL.

> **Render Free Tier Note**: Free instances sleep after 15 minutes of inactivity. OmniDrop automatically detects this, displays a *"Waking up server, please wait..."* banner, and polls `/health` until ready.

---

### 2. Deploy the Web App to GitHub Pages

This repository includes `.github/workflows/deploy-pages.yml`.

1. Go to your GitHub repository **Settings** → **Pages**.
2. Under **Build and deployment** → **Source**, select **GitHub Actions**.
3. Push changes to the `main` branch. The workflow will automatically:
   - Check out code
   - Install dependencies
   - Run `npm run build` with Vite base path
   - Publish to GitHub Pages
4. Open the GitHub Pages URL in your mobile browser and tap **Install** to add to your home screen as a PWA!

---

### 3. Build Android APK with Capacitor

This repository includes `.github/workflows/build-apk.yml`.

Whenever you push to `main` or run the workflow manually via GitHub Actions:
1. Node 20 and Java 17 are provisioned.
2. The web application is built.
3. Capacitor copies and syncs web assets into the Android native project.
4. Kotlin plugin files from `app/android-native` and Android permissions are wired.
5. Gradle builds the debug APK (`./gradlew assembleDebug`).
6. The APK is uploaded as a downloadable artifact `omnidrop-debug-apk`.

To build locally on your machine with Android Studio:
```bash
npm run build
npx cap add android
cp app/android-native/*.kt android/app/src/main/java/com/omnidrop/app/plugin/
npx cap sync android
npx cap open android
```

---

## 📱 Mobile & Native Features

- **PWA Share Target**: Allows sharing links directly from TikTok, YouTube, Instagram, or Facebook into OmniDrop via the system share sheet.
- **Clipboard Auto-Detection**: Prompts you to download supported links as soon as you copy them and switch to OmniDrop.
- **Floating Overlay Bubble**: Draggable bubble on top of other apps (uses `SYSTEM_ALERT_WINDOW`). Tapping it grabs clipboard links instantly.
- **Persistent Notification**: Notification with a "Download from clipboard" action.
- **Download Completed Alerts**: System notifications when high-res video downloads finish.
