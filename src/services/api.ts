import { AppSettings, VideoInfo, PlatformType } from '../types';

export const SUPPORTED_DOMAINS = [
  'tiktok.com',
  'vt.tiktok.com',
  'youtube.com',
  'youtu.be',
  'instagram.com',
  'facebook.com',
  'fb.watch',
];

export function isSupportedUrl(url: string): boolean {
  if (!url || typeof url !== 'string') return false;
  try {
    const parsed = new URL(url.trim());
    const host = parsed.hostname.toLowerCase();
    return SUPPORTED_DOMAINS.some((d) => host === d || host.endsWith('.' + d));
  } catch {
    return false;
  }
}

export function detectPlatform(url: string): PlatformType {
  const lower = url.toLowerCase();
  if (lower.includes('tiktok.')) return 'tiktok';
  if (lower.includes('youtube.') || lower.includes('youtu.be')) return 'youtube';
  if (lower.includes('instagram.')) return 'instagram';
  if (lower.includes('facebook.') || lower.includes('fb.watch')) return 'facebook';
  return 'video';
}

export function cleanBackendUrl(rawUrl: string): string {
  if (!rawUrl || !rawUrl.trim()) return '';
  let url = rawUrl.trim();
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    url = 'https://' + url;
  }
  return url.replace(/\/+$/, '');
}

export async function checkServerHealth(settings: AppSettings): Promise<{ ok: boolean; latencyMs: number; error?: string }> {
  const base = cleanBackendUrl(settings.backendUrl);
  const targetUrl = base ? `${base}/health` : '/api/health';
  const start = performance.now();

  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 8000);

    const headers: Record<string, string> = {};
    if (settings.apiKey) {
      headers['x-api-key'] = settings.apiKey;
    }

    const res = await fetch(targetUrl, {
      method: 'GET',
      headers,
      signal: controller.signal,
    });
    clearTimeout(timeoutId);

    const latencyMs = Math.round(performance.now() - start);
    if (res.ok) {
      return { ok: true, latencyMs };
    }
    return { ok: false, latencyMs, error: `HTTP ${res.status}` };
  } catch (err: any) {
    const latencyMs = Math.round(performance.now() - start);
    return { ok: false, latencyMs, error: err.name === 'AbortError' ? 'Timeout' : (err.message || 'Offline') };
  }
}

export async function wakeUpServerUntilReady(
  settings: AppSettings,
  onAttempt?: (count: number) => void
): Promise<boolean> {
  const maxAttempts = 24; // 24 * 2.5s ~ 60s max
  let attempts = 0;

  while (attempts < maxAttempts) {
    attempts++;
    if (onAttempt) onAttempt(attempts);
    const health = await checkServerHealth(settings);
    if (health.ok) {
      return true;
    }
    await new Promise((r) => setTimeout(r, 2500));
  }
  return false;
}

export async function fetchVideoInfo(url: string, settings: AppSettings): Promise<VideoInfo> {
  const base = cleanBackendUrl(settings.backendUrl);
  const targetUrl = base ? `${base}/info` : '/api/info';

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (settings.apiKey) {
    headers['x-api-key'] = settings.apiKey;
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 28000);

  try {
    const res = await fetch(targetUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify({ url: url.trim() }),
      signal: controller.signal,
    });
    clearTimeout(timeoutId);

    if (!res.ok) {
      let errorMsg = 'Failed to extract video details.';
      try {
        const data = await res.json();
        errorMsg = data.detail || data.error || errorMsg;
      } catch {
        if (res.status === 404) errorMsg = 'Video not found or was deleted.';
        else if (res.status === 403) errorMsg = 'Video is private or restricted.';
        else if (res.status === 504) errorMsg = 'Request timed out waiting for platform response.';
      }
      throw new Error(errorMsg);
    }

    const data = await res.json();
    return {
      title: data.title || 'Untitled Video',
      thumbnail: data.thumbnail || '',
      duration: data.duration || 0,
      uploader: data.uploader || 'Creator',
      qualities: data.qualities && data.qualities.length > 0 ? data.qualities : ['best', 'audio'],
      platform: data.platform || detectPlatform(url),
      id: data.id,
      url: url.trim(),
    };
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Server took too long to inspect the video link. It might be waking up.');
    }
    throw err;
  }
}

export async function downloadVideoFile(
  url: string,
  quality: string,
  title: string,
  settings: AppSettings,
  onProgress?: (percent: number, statusText: string) => void
): Promise<string> {
  const base = cleanBackendUrl(settings.backendUrl);
  const targetUrl = base ? `${base}/download` : '/api/download';

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (settings.apiKey) {
    headers['x-api-key'] = settings.apiKey;
  }

  if (onProgress) onProgress(15, 'Requesting video stream from server...');

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 160000);

  try {
    const res = await fetch(targetUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify({ url: url.trim(), quality }),
      signal: controller.signal,
    });
    clearTimeout(timeoutId);

    if (!res.ok) {
      let errText = 'Download failed.';
      try {
        const data = await res.json();
        errText = data.detail || data.error || errText;
      } catch {
        if (res.status === 504) errText = 'Server timeout while processing video streams.';
      }
      throw new Error(errText);
    }

    if (onProgress) onProgress(50, 'Downloading media stream...');

    const contentLength = res.headers.get('content-length');
    const totalBytes = contentLength ? parseInt(contentLength, 10) : 0;

    let blob: Blob;

    if (res.body && totalBytes > 0 && typeof ReadableStream !== 'undefined') {
      const reader = res.body.getReader();
      let receivedBytes = 0;
      const chunks: Uint8Array[] = [];

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        if (value) {
          chunks.push(value);
          receivedBytes += value.length;
          const pct = Math.min(95, Math.round(50 + (receivedBytes / totalBytes) * 45));
          if (onProgress) onProgress(pct, `Transferring video: ${(receivedBytes / (1024 * 1024)).toFixed(1)} MB...`);
        }
      }
      blob = new Blob(chunks as BlobPart[], {
        type: quality === 'audio' ? 'audio/mpeg' : 'video/mp4',
      });
    } else {
      blob = await res.blob();
    }

    if (onProgress) onProgress(98, 'Saving file to device...');

    // Determine filename
    const sanitizedTitle = title.replace(/[^a-zA-Z0-9_\-]/g, '_').substring(0, 50) || 'OmniDrop_video';
    const ext = quality === 'audio' ? '.mp3' : '.mp4';
    const fileName = `${sanitizedTitle}${ext}`;

    // Browser file download trigger
    const blobUrl = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);

    setTimeout(() => {
      window.URL.revokeObjectURL(blobUrl);
    }, 60000);

    if (onProgress) onProgress(100, 'Saved successfully!');
    return fileName;
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Download timed out. The video may be very large or network congested.');
    }
    throw err;
  }
}
