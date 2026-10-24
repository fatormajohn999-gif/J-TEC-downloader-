import { Capacitor } from '@capacitor/core';
import { AppSettings, VideoInfo, PlatformType } from '../types';
import { VideoDownloader } from '../plugins/videoDownloader';

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

export function normalizeUrl(rawUrl: string): string {
  if (!rawUrl || typeof rawUrl !== 'string') return '';
  const trimmed = rawUrl.trim();
  try {
    const parsed = new URL(trimmed);
    const host = parsed.hostname.toLowerCase();

    // YouTube: preserve only ?v= for /watch
    if (host === 'youtube.com' || host.endsWith('.youtube.com')) {
      if (parsed.pathname === '/watch') {
        const v = parsed.searchParams.get('v');
        return v ? `https://${parsed.host}/watch?v=${v}` : `https://${parsed.host}${parsed.pathname}`;
      }
      return `https://${parsed.host}${parsed.pathname.replace(/\/+$/, '')}`;
    }

    if (host === 'youtu.be') {
      return `https://youtu.be${parsed.pathname.replace(/\/+$/, '')}`;
    }

    // TikTok: remove everything after ?
    if (host === 'tiktok.com' || host.endsWith('.tiktok.com')) {
      return `https://${parsed.host}${parsed.pathname.replace(/\/+$/, '')}`;
    }

    // Instagram: remove everything after ?
    if (host === 'instagram.com' || host.endsWith('.instagram.com')) {
      return `https://${parsed.host}${parsed.pathname.replace(/\/+$/, '')}`;
    }

    // Facebook: remove tracking query params except ?v= for /watch
    if (host === 'facebook.com' || host.endsWith('.facebook.com') || host === 'fb.watch') {
      if (parsed.pathname.includes('/watch') && parsed.searchParams.has('v')) {
        const v = parsed.searchParams.get('v');
        return `https://${parsed.host}/watch?v=${v}`;
      }
      return `https://${parsed.host}${parsed.pathname.replace(/\/+$/, '')}`;
    }

    return trimmed;
  } catch {
    return trimmed;
  }
}

export function detectPlatform(url: string): PlatformType {
  const lower = url.toLowerCase();
  if (lower.includes('tiktok.')) return 'tiktok';
  if (lower.includes('youtube.') || lower.includes('youtu.be')) return 'youtube';
  if (lower.includes('instagram.')) return 'instagram';
  if (lower.includes('facebook.') || lower.includes('fb.watch')) return 'facebook';
  return 'other';
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
  const isNative = Capacitor.isNativePlatform();
  if (isNative && (!settings.backendUrl || !settings.backendUrl.trim())) {
    return { ok: false, latencyMs: 0, error: 'Configure Render URL in Settings' };
  }

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
  const isNative = Capacitor.isNativePlatform();
  if (isNative && (!settings.backendUrl || !settings.backendUrl.trim())) {
    return false;
  }

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
  const isNative = Capacitor.isNativePlatform();

  // On native platforms, require Backend URL to be set
  if (isNative && (!settings.backendUrl || !settings.backendUrl.trim())) {
    throw new Error('Please set your Render Backend URL in Settings before inspecting links on mobile.');
  }

  const normalized = normalizeUrl(url);
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
      body: JSON.stringify({ url: normalized }),
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
    if (data.type === 'photo') {
      return {
        type: 'photo',
        title: data.title || 'TikTok Photos',
        thumbnail: data.thumbnail || '',
        duration: 0,
        uploader: data.uploader || 'Creator',
        qualities: [],
        platform: 'tiktok',
        imageCount: data.imageCount || 0,
        url: normalized,
      };
    }

    let rawPlatform = data.platform || detectPlatform(normalized);
    if (rawPlatform === 'video') rawPlatform = 'other';

    return {
      type: 'video',
      title: data.title || 'Untitled Video',
      thumbnail: data.thumbnail || '',
      duration: data.duration || 0,
      uploader: data.uploader || 'Creator',
      qualities: data.qualities && data.qualities.length > 0 ? data.qualities : ['best', 'audio'],
      platform: rawPlatform,
      id: data.id,
      url: normalized,
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
  const isNative = Capacitor.isNativePlatform();
  const normalized = normalizeUrl(url);

  // 1. Native Capacitor platform: Stream straight to gallery via MediaStore Kotlin plugin
  if (isNative) {
    if (!settings.backendUrl || !settings.backendUrl.trim()) {
      throw new Error('Please set your Render Backend URL in Settings before downloading on mobile.');
    }

    const cleanUrl = cleanBackendUrl(settings.backendUrl);
    let progressHandle: any = null;

    try {
      if (onProgress) {
        progressHandle = await VideoDownloader.addListener('onDownloadProgress', (data) => {
          onProgress(data.percent, data.statusText);
        });
      }

      const res = await VideoDownloader.downloadAndSave({
        url: normalized,
        quality,
        title,
        backendUrl: cleanUrl,
        apiKey: settings.apiKey || '',
      });

      if (onProgress) onProgress(100, 'Saved to phone gallery!');
      return res.fileName;
    } finally {
      if (progressHandle && typeof progressHandle.remove === 'function') {
        progressHandle.remove();
      }
    }
  }

  // 2. Web / PWA platform: Browser stream and anchor download
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
      body: JSON.stringify({ url: normalized, quality }),
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

    const contentType = res.headers.get('content-type') || '';
    const isZip = contentType.includes('application/zip');
    const contentDisposition = res.headers.get('content-disposition') || '';

    let serverFileName = '';
    const filenameMatch = contentDisposition.match(/filename=["']?([^"';]+)["']?/i);
    if (filenameMatch && filenameMatch[1]) {
      serverFileName = filenameMatch[1].trim();
    }

    if (onProgress) onProgress(50, isZip ? 'Downloading photos archive...' : 'Downloading media stream...');

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
          if (onProgress) onProgress(pct, `Transferring: ${(receivedBytes / (1024 * 1024)).toFixed(1)} MB...`);
        }
      }
      blob = new Blob(chunks as BlobPart[], {
        type: isZip ? 'application/zip' : (quality === 'audio' ? 'audio/mpeg' : 'video/mp4'),
      });
    } else {
      blob = await res.blob();
    }

    if (onProgress) onProgress(98, 'Saving file to device...');

    const sanitizedTitle = title.replace(/[^a-zA-Z0-9_\-]/g, '_').substring(0, 50) || 'OmniDrop';
    let fileName = serverFileName;
    if (!fileName) {
      if (isZip) {
        fileName = `${sanitizedTitle} [TikTok photos].zip`;
      } else {
        const ext = quality === 'audio' ? '.mp3' : '.mp4';
        fileName = `${sanitizedTitle}${ext}`;
      }
    }

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

    if (onProgress) onProgress(100, isZip ? 'Photos ZIP saved!' : 'Saved successfully!');
    return fileName;
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Download timed out. The video may be very large or network congested.');
    }
    throw err;
  }
}
