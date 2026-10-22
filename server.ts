import express, { Request, Response, NextFunction } from 'express';
import http from 'http';
import cors from 'cors';
import path from 'path';
import { fileURLToPath } from 'url';
import { spawn } from 'child_process';
import fs from 'fs';
import os from 'os';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = parseInt(process.env.PORT || '3000', 10);
const APP_API_KEY = (process.env.APP_API_KEY || '').trim();

// Locate yt-dlp binary (local workspace bin or /tmp or system)
const LOCAL_YTDLP = path.resolve(__dirname, 'bin/yt-dlp');
const YTDLP_BIN = fs.existsSync(LOCAL_YTDLP) ? LOCAL_YTDLP : (fs.existsSync('/tmp/yt-dlp') ? '/tmp/yt-dlp' : 'yt-dlp');

app.use(cors());
app.use(express.json());

const ALLOWED_DOMAINS = [
  'tiktok.com',
  'vt.tiktok.com',
  'youtube.com',
  'youtu.be',
  'instagram.com',
  'facebook.com',
  'fb.watch',
];

function validateUrl(rawUrl: unknown): string {
  if (!rawUrl || typeof rawUrl !== 'string') {
    throw { status: 400, message: 'Missing or empty URL' };
  }
  const cleanUrl = rawUrl.trim();
  let parsed: URL;
  try {
    parsed = new URL(cleanUrl);
  } catch {
    throw { status: 400, message: 'Invalid URL format provided' };
  }

  const hostname = parsed.hostname.toLowerCase();
  const isAllowed = ALLOWED_DOMAINS.some(
    (d) => hostname === d || hostname.endsWith('.' + d)
  );

  if (!isAllowed) {
    throw {
      status: 400,
      message: `Unsupported domain '${hostname}'. Only TikTok, YouTube, Instagram, and Facebook links are supported.`,
    };
  }

  return cleanUrl;
}

function verifyApiKey(req: Request, res: Response, next: NextFunction) {
  if (APP_API_KEY) {
    const key = req.headers['x-api-key'];
    if (key !== APP_API_KEY) {
      return res.status(401).json({ error: 'Invalid or missing API key (x-api-key header required)' });
    }
  }
  next();
}

// Health check endpoints
const handleHealth = (_req: Request, res: Response) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
};
app.get('/health', handleHealth);
app.get('/api/health', handleHealth);

// Video Information endpoint
const handleInfo = async (req: Request, res: Response) => {
  try {
    const url = validateUrl(req.body?.url);

    // Spawn yt-dlp with JSON dump
    const args = [
      '--dump-single-json',
      '--skip-download',
      '--no-warnings',
      '--socket-timeout', '15',
      url,
    ];

    const child = spawn(YTDLP_BIN, args, { timeout: 30000 });
    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (d) => { stdout += d.toString(); });
    child.stderr.on('data', (d) => { stderr += d.toString(); });

    child.on('close', (code) => {
      if (code !== 0) {
        const lowerErr = stderr.toLowerCase();
        if (lowerErr.includes('private') || lowerErr.includes('login required')) {
          return res.status(403).json({ error: 'This video is private or requires authentication to view.' });
        }
        if (lowerErr.includes('404') || lowerErr.includes('not found') || lowerErr.includes('does not exist')) {
          return res.status(404).json({ error: 'The requested video was not found or has been deleted.' });
        }
        return res.status(400).json({ error: 'Could not extract video details. Please ensure the link is public and accessible.' });
      }

      try {
        const info = JSON.parse(stdout);
        const formats: Array<{ height?: number; vcodec?: string }> = info.formats || [];
        const detectedHeights = new Set<number>();
        for (const f of formats) {
          if (typeof f.height === 'number') {
            detectedHeights.add(f.height);
          }
        }

        const qualities: string[] = [];
        for (const h of [1080, 720, 480, 360]) {
          if (Array.from(detectedHeights).some((dh) => dh >= h)) {
            qualities.push(`${h}p`);
          }
        }

        if (qualities.length === 0) {
          qualities.push('best');
        } else if (!qualities.includes('best')) {
          qualities.unshift('best');
        }
        qualities.push('audio');

        let platform = 'video';
        const extractor = (info.extractor_key || info.extractor || '').toLowerCase();
        if (extractor.includes('tiktok') || url.includes('tiktok')) platform = 'tiktok';
        else if (extractor.includes('youtube') || url.includes('youtu')) platform = 'youtube';
        else if (extractor.includes('instagram') || url.includes('instagr')) platform = 'instagram';
        else if (extractor.includes('facebook') || url.includes('fb')) platform = 'facebook';

        return res.json({
          title: info.title || 'Video Download',
          thumbnail: info.thumbnail || '',
          duration: info.duration || 0,
          uploader: info.uploader || info.channel || info.creator || 'Creator',
          qualities,
          platform,
          id: info.id || '',
        });
      } catch {
        return res.status(500).json({ error: 'Failed to parse video metadata response.' });
      }
    });

    child.on('error', () => {
      return res.status(500).json({ error: 'Failed to spawn video extractor service.' });
    });
  } catch (err: any) {
    return res.status(err.status || 500).json({ error: err.message || 'Error processing video URL.' });
  }
};
app.post('/info', verifyApiKey, handleInfo);
app.post('/api/info', verifyApiKey, handleInfo);

// Video Download endpoint
const handleDownload = async (req: Request, res: Response) => {
  let tempFilePath = '';
  try {
    const url = validateUrl(req.body?.url);
    const quality = (req.body?.quality || 'best').toString().toLowerCase().trim();

    const uniqueId = Math.random().toString(36).substring(2, 10);
    const tempDir = os.tmpdir();
    const tempPrefix = path.join(tempDir, `omnidrop_${uniqueId}`);

    let formatSpec = 'bv*+ba/b';
    let isAudio = false;

    if (quality === 'audio') {
      formatSpec = 'ba/b';
      isAudio = true;
    } else if (quality === '1080p') {
      formatSpec = 'bv*[height<=1080]+ba/b[height<=1080]/b';
    } else if (quality === '720p') {
      formatSpec = 'bv*[height<=720]+ba/b[height<=720]/b';
    } else if (quality === '480p') {
      formatSpec = 'bv*[height<=480]+ba/b[height<=480]/b';
    } else if (quality === '360p') {
      formatSpec = 'bv*[height<=360]+ba/b[height<=360]/b';
    }

    const args = [
      '--format', formatSpec,
      '--no-warnings',
      '--socket-timeout', '30',
      '--output', `${tempPrefix}.%(ext)s`,
    ];

    if (isAudio) {
      args.push('--extract-audio', '--audio-format', 'mp3', '--audio-quality', '192K');
    } else {
      args.push('--merge-output-format', 'mp4');
    }

    args.push(url);

    const child = spawn(YTDLP_BIN, args, { timeout: 150000 });
    let stderr = '';

    child.stderr.on('data', (d) => { stderr += d.toString(); });

    child.on('close', (code) => {
      if (code !== 0) {
        return res.status(400).json({
          error: 'Download failed. The media may be protected, restricted, or unavailable.',
        });
      }

      // Locate the downloaded file
      const expectedExts = isAudio ? ['.mp3', '.m4a'] : ['.mp4', '.mkv', '.webm'];
      let foundPath = '';
      for (const ext of expectedExts) {
        const candidate = `${tempPrefix}${ext}`;
        if (fs.existsSync(candidate)) {
          foundPath = candidate;
          break;
        }
      }

      if (!foundPath) {
        // Search directory for prefix
        const files = fs.readdirSync(tempDir);
        const match = files.find((f) => f.startsWith(`omnidrop_${uniqueId}`));
        if (match) {
          foundPath = path.join(tempDir, match);
        }
      }

      if (!foundPath || !fs.existsSync(foundPath)) {
        return res.status(500).json({ error: 'Downloaded file could not be finalized.' });
      }

      tempFilePath = foundPath;
      const stat = fs.statSync(foundPath);
      const ext = path.extname(foundPath) || (isAudio ? '.mp3' : '.mp4');
      const filename = `OmniDrop_${uniqueId}${ext}`;
      const contentType = isAudio ? 'audio/mpeg' : 'video/mp4';

      res.setHeader('Content-Disposition', `attachment; filename="${filename}"`);
      res.setHeader('Content-Type', contentType);
      res.setHeader('Content-Length', stat.size);

      const fileStream = fs.createReadStream(foundPath);
      fileStream.pipe(res);

      const cleanup = () => {
        try {
          if (tempFilePath && fs.existsSync(tempFilePath)) {
            fs.unlinkSync(tempFilePath);
          }
        } catch {
          // Ignored
        }
      };

      fileStream.on('end', cleanup);
      fileStream.on('close', cleanup);
      res.on('finish', cleanup);
      res.on('error', cleanup);
    });

    child.on('error', () => {
      return res.status(500).json({ error: 'Failed to execute video download processor.' });
    });
  } catch (err: any) {
    if (tempFilePath && fs.existsSync(tempFilePath)) {
      try { fs.unlinkSync(tempFilePath); } catch {}
    }
    return res.status(err.status || 500).json({ error: err.message || 'Error executing download request.' });
  }
};
app.post('/download', verifyApiKey, handleDownload);
app.post('/api/download', verifyApiKey, handleDownload);

async function startServer() {
  const httpServer = http.createServer(app);

  if (process.env.NODE_ENV !== 'production') {
    const { createServer: createViteServer } = await import('vite');
    const isHmrDisabled = process.env.DISABLE_HMR === 'true';
    const vite = await createViteServer({
      server: {
        middlewareMode: true,
        hmr: isHmrDisabled ? false : { server: httpServer },
      },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.resolve(__dirname, 'dist');
    app.use(express.static(distPath));
    app.get('*', (_req, res) => {
      res.sendFile(path.resolve(distPath, 'index.html'));
    });
  }

  httpServer.listen(PORT, '0.0.0.0', () => {
    console.log(`[OmniDrop] Server listening on port ${PORT}`);
  });
}

startServer().catch((err) => {
  console.error('[OmniDrop] Failed to start server:', err);
  process.exit(1);
});
