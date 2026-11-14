import { Capacitor } from '@capacitor/core';
import {
  AppSettings,
  VideoInfo,
  PlatformType,
  ServerHealthProbe,
  SpeedTestResult,
} from '../types';
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

const ACTIVE_SERVER_STORAGE_KEY = 'omnidrop_active_server_v1';
const SPEED_TEST_HISTORY_KEY = 'omnidrop_speed_tests_v1';
const STICKY_BACKUP_WINDOW_MS = 10 * 60 * 1000; // 10 minutes

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

    if (host === 'tiktok.com' || host.endsWith('.tiktok.com')) {
      return `https://${parsed.host}${parsed.pathname.replace(/\/+$/, '')}`;
    }

    if (host === 'instagram.com' || host.endsWith('.instagram.com')) {
      return `https://${parsed.host}${parsed.pathname.replace(/\/+$/, '')}`;
    }

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

interface ServerTarget {
  role: 'primary' | 'backup';
  baseUrl: string;
  apiKey: string;
}

function buildCandidateServers(settings: AppSettings): ServerTarget[] {
  const primaryBase = cleanBackendUrl(settings.backendUrl || '');
  const backupBase = cleanBackendUrl(settings.backupBackendUrl || '');
  const autoSwitch = settings.autoSwitchToBackup ?? true;

  const primaryTarget: ServerTarget | null = primaryBase || !Capacitor.isNativePlatform()
    ? { role: 'primary', baseUrl: primaryBase, apiKey: (settings.apiKey || '').trim() }
    : null;
  const backupTarget: ServerTarget | null = autoSwitch && backupBase
    ? { role: 'backup', baseUrl: backupBase, apiKey: (settings.backupApiKey || '').trim() }
    : null;

  const active = getStickyActiveServer(settings);
  if (active === 'backup' && backupTarget) {
    return primaryTarget ? [backupTarget, primaryTarget] : [backupTarget];
  }
  if (primaryTarget && backupTarget) {
    return [primaryTarget, backupTarget];
  }
  if (primaryTarget) return [primaryTarget];
  if (backupTarget) return [backupTarget];
  return [];
}

export function getStickyActiveServer(settings: AppSettings): 'primary' | 'backup' {
  const autoSwitch = settings.autoSwitchToBackup ?? true;
  const hasBackup = Boolean(cleanBackendUrl(settings.backupBackendUrl || ''));
  if (!autoSwitch || !hasBackup) return 'primary';

  try {
    const raw = localStorage.getItem(ACTIVE_SERVER_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (
        parsed?.activeServer === 'backup' &&
        typeof parsed?.setAt === 'number' &&
        Date.now() - parsed.setAt < STICKY_BACKUP_WINDOW_MS
      ) {
        return 'backup';
      }
    }
  } catch {}
  return 'primary';
}

export function getStickyActiveServerRemainingMs(settings: AppSettings): number {
  if (getStickyActiveServer(settings) !== 'backup') return 0;
  try {
    const raw = localStorage.getItem(ACTIVE_SERVER_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (typeof parsed?.setAt === 'number') {
        return Math.max(0, STICKY_BACKUP_WINDOW_MS - (Date.now() - parsed.setAt));
      }
    }
  } catch {}
  return 0;
}

export function setStickyActiveServer(role: 'primary' | 'backup') {
  try {
    localStorage.setItem(
      ACTIVE_SERVER_STORAGE_KEY,
      JSON.stringify({
        activeServer: role,
        setAt: role === 'backup' ? Date.now() : 0,
      })
    );
  } catch {}
  VideoDownloader.setActiveServerStatus({ activeServer: role }).catch(() => {});
}

export function getActiveServerTarget(settings: AppSettings): ServerTarget {
  const active = getStickyActiveServer(settings);
  const backupBase = cleanBackendUrl(settings.backupBackendUrl || '');
  if (active === 'backup' && backupBase && (settings.autoSwitchToBackup ?? true)) {
    return {
      role: 'backup',
      baseUrl: backupBase,
      apiKey: (settings.backupApiKey || '').trim(),
    };
  }
  return {
    role: 'primary',
    baseUrl: cleanBackendUrl(settings.backendUrl || ''),
    apiKey: (settings.apiKey || '').trim(),
  };
}

async function probeEndpointHealth(baseUrl: string, apiKey: string): Promise<ServerHealthProbe> {
  const clean = cleanBackendUrl(baseUrl);
  const isNative = Capacitor.isNativePlatform();
  if (!clean && isNative) {
    return { configured: false, ok: false, latencyMs: 0, error: 'Not configured' };
  }
  const targetUrl = clean ? `${clean}/health` : '/api/health';
  const start = performance.now();
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 15000);
    const headers: Record<string, string> = {};
    if (apiKey) {
      headers['x-api-key'] = apiKey;
    }
    const res = await fetch(targetUrl, {
      method: 'GET',
      headers,
      signal: controller.signal,
    });
    clearTimeout(timeoutId);
    const latencyMs = Math.round(performance.now() - start);
    if (res.ok) {
      return { configured: true, ok: true, latencyMs };
    }
    return { configured: true, ok: false, latencyMs, error: `HTTP ${res.status}` };
  } catch (err: any) {
    const latencyMs = Math.round(performance.now() - start);
    return {
      configured: true,
      ok: false,
      latencyMs,
      error: err?.name === 'AbortError' ? 'Timeout' : (err?.message || 'Offline'),
    };
  }
}

export async function checkServerHealth(settings: AppSettings): Promise<{ ok: boolean; latencyMs: number; error?: string; serverRole?: 'primary' | 'backup' }> {
  const isNative = Capacitor.isNativePlatform();
  const target = getActiveServerTarget(settings);
  if (isNative && !target.baseUrl) {
    return { ok: false, latencyMs: 0, error: 'Configure Render URL in Settings', serverRole: target.role };
  }
  const res = await probeEndpointHealth(target.baseUrl, target.apiKey);
  return { ok: res.ok, latencyMs: res.latencyMs, error: res.error, serverRole: target.role };
}

export async function testBothServers(settings: AppSettings): Promise<{
  primary: ServerHealthProbe;
  backup: ServerHealthProbe;
  activeServer: 'primary' | 'backup';
}> {
  const primaryUrl = cleanBackendUrl(settings.backendUrl || '');
  const backupUrl = cleanBackendUrl(settings.backupBackendUrl || '');

  const primaryPromise: Promise<ServerHealthProbe> =
    primaryUrl || !Capacitor.isNativePlatform()
      ? probeEndpointHealth(primaryUrl, (settings.apiKey || '').trim())
      : Promise.resolve({ configured: false, ok: false, latencyMs: 0, error: 'Not configured' });

  const backupPromise: Promise<ServerHealthProbe> = backupUrl
    ? probeEndpointHealth(backupUrl, (settings.backupApiKey || '').trim())
    : Promise.resolve({ configured: false, ok: false, latencyMs: 0, error: 'Not configured' });

  const [primary, backup] = await Promise.all([primaryPromise, backupPromise]);

  let nextActive: 'primary' | 'backup' = getStickyActiveServer(settings);
  const autoSwitch = settings.autoSwitchToBackup ?? true;

  if (autoSwitch && backupUrl) {
    if (!primary.ok && backup.ok) {
      nextActive = 'backup';
      setStickyActiveServer('backup');
    } else if (primary.ok && nextActive === 'primary') {
      setStickyActiveServer('primary');
    } else if (primary.ok && !backup.ok) {
      nextActive = 'primary';
      setStickyActiveServer('primary');
    }
  } else {
    nextActive = 'primary';
    setStickyActiveServer('primary');
  }

  return { primary, backup, activeServer: nextActive };
}

export async function wakeUpServerUntilReady(
  settings: AppSettings,
  onAttempt?: (count: number) => void
): Promise<boolean> {
  const isNative = Capacitor.isNativePlatform();
  if (isNative && !cleanBackendUrl(settings.backendUrl) && !cleanBackendUrl(settings.backupBackendUrl || '')) {
    return false;
  }

  const maxAttempts = 24;
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
  const candidates = buildCandidateServers(settings);

  if (isNative && candidates.length === 0) {
    throw new Error('Please set your Render Backend URL in Settings before inspecting links on mobile.');
  }

  const normalized = normalizeUrl(url);
  const targetsToTry = candidates.length > 0 ? candidates : [{ role: 'primary' as const, baseUrl: '', apiKey: (settings.apiKey || '').trim() }];

  let lastError: Error | null = null;

  for (let i = 0; i < targetsToTry.length; i++) {
    const candidate = targetsToTry[i];
    const targetUrl = candidate.baseUrl ? `${candidate.baseUrl}/info` : '/api/info';
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (candidate.apiKey) {
      headers['x-api-key'] = candidate.apiKey;
    }

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 25000);

    try {
      const res = await fetch(targetUrl, {
        method: 'POST',
        headers,
        body: JSON.stringify({ url: normalized }),
        signal: controller.signal,
      });
      clearTimeout(timeoutId);

      if (!res.ok) {
        let errorMsg = `Server returned ${res.status}`;
        try {
          const data = await res.json();
          errorMsg = data.detail || data.error || errorMsg;
        } catch {
          if (res.status === 404) errorMsg = 'Video not found or was deleted.';
          else if (res.status === 403) errorMsg = 'Video is private or restricted.';
          else if (res.status === 502 || res.status === 503 || res.status === 504) errorMsg = `Server returned ${res.status} (starting up)`;
        }
        const canFailoverStatus = res.status >= 500 && i < targetsToTry.length - 1;
        if (canFailoverStatus) {
          lastError = new Error(errorMsg);
          continue;
        }
        throw new Error(errorMsg);
      }

      const data = await res.json();
      setStickyActiveServer(candidate.role);

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
        filesize: typeof data.filesize === 'number' ? data.filesize : undefined,
        filesize_approx: typeof data.filesize_approx === 'number' ? data.filesize_approx : undefined,
      };
    } catch (err: any) {
      clearTimeout(timeoutId);
      const isTimeoutOrNet = err?.name === 'AbortError' || err instanceof TypeError || /network|fetch|failed to fetch|timeout/i.test(err?.message || '');
      if (isTimeoutOrNet && i < targetsToTry.length - 1) {
        lastError = new Error(err?.name === 'AbortError' ? 'Timed out inspecting video link' : (err?.message || 'Connection error'));
        continue;
      }
      if (err?.name === 'AbortError') {
        throw new Error('Server took too long to inspect the video link.');
      }
      throw err;
    }
  }

  throw lastError || new Error('Failed to extract video details.');
}

export function resolveEffectiveQuality(
  requestedQuality: string,
  dataSaver: boolean | undefined,
  isMobileData: boolean
): { effectiveQuality: string; cappedByDataSaver: boolean } {
  const q = (requestedQuality || 'best').toLowerCase();
  if (dataSaver && isMobileData && (q === 'best' || q === '1080p')) {
    return { effectiveQuality: '720p', cappedByDataSaver: true };
  }
  return { effectiveQuality: q, cappedByDataSaver: false };
}

export function formatDownloadFileName(
  title: string,
  platform: string,
  ext: string,
  style: AppSettings['fileNameStyle'] = 'title_platform'
): string {
  const cleanTitle =
    (title || 'video')
      .replace(/[\\/:*?"<>|\r\n]/g, '_')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 75) || 'OmniDrop';
  const platClean =
    !platform || platform === 'other' || platform === 'video'
      ? 'Other'
      : platform.charAt(0).toUpperCase() + platform.slice(1);
  const dateStr = new Date().toISOString().slice(0, 10);

  if (style === 'platform_title_date') {
    return `${platClean} - ${cleanTitle} - ${dateStr}${ext}`;
  }
  if (style === 'title_only') {
    return `${cleanTitle}${ext}`;
  }
  return `${cleanTitle} [${platClean}]${ext}`;
}

export async function downloadVideoFile(
  url: string,
  quality: string,
  title: string,
  settings: AppSettings,
  onProgress?: (percent: number, statusText: string, speedMb?: number) => void
): Promise<string> {
  const isNative = Capacitor.isNativePlatform();
  const normalized = normalizeUrl(url);

  // 1. Native Capacitor platform: Stream straight to gallery via MediaStore Kotlin plugin (with native failover)
  if (isNative) {
    const primaryUrl = cleanBackendUrl(settings.backendUrl || '');
    const backupUrl = cleanBackendUrl(settings.backupBackendUrl || '');
    if (!primaryUrl && !backupUrl) {
      throw new Error('Please set your Render Backend URL in Settings before downloading on mobile.');
    }

    let progressHandle: any = null;
    try {
      if (onProgress) {
        progressHandle = await VideoDownloader.addListener('onDownloadProgress', (data) => {
          onProgress(data.percent, data.statusText, data.speedMb);
        });
      }

      const res = await VideoDownloader.downloadAndSave({
        url: normalized,
        quality,
        title,
        backendUrl: primaryUrl,
        apiKey: (settings.apiKey || '').trim(),
        backupBackendUrl: backupUrl,
        backupApiKey: (settings.backupApiKey || '').trim(),
        autoSwitchToBackup: settings.autoSwitchToBackup ?? true,
      });

      if (res?.usedServer) {
        setStickyActiveServer(res.usedServer);
      }

      if (onProgress) onProgress(100, 'Saved to phone gallery!');
      return res.fileName;
    } finally {
      if (progressHandle && typeof progressHandle.remove === 'function') {
        progressHandle.remove();
      }
    }
  }

  // 2. Web / PWA platform: Failover + wake-up retries + streaming download
  const candidates = buildCandidateServers(settings);
  const targetsToTry =
    candidates.length > 0
      ? candidates
      : [{ role: 'primary' as const, baseUrl: '', apiKey: (settings.apiKey || '').trim() }];

  let lastError: Error | null = null;

  for (let sIdx = 0; sIdx < targetsToTry.length; sIdx++) {
    const candidate = targetsToTry[sIdx];
    const hasNextServer = sIdx < targetsToTry.length - 1;
    const targetUrl = candidate.baseUrl ? `${candidate.baseUrl}/download` : '/api/download';
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (candidate.apiKey) {
      headers['x-api-key'] = candidate.apiKey;
    }

    const maxAttempts = 6;
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      if (onProgress) {
        const label = candidate.role === 'backup' ? '[BACKUP] ' : '';
        onProgress(
          15,
          attempt === 1
            ? `${label}CONNECTING...`
            : `${label}SERVER IS WAKING UP... attempt ${attempt}/${maxAttempts}`
        );
      }

      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 180000);

      try {
        const res = await fetch(targetUrl, {
          method: 'POST',
          headers,
          body: JSON.stringify({ url: normalized, quality }),
          signal: controller.signal,
        });
        clearTimeout(timeoutId);

        if (!res.ok) {
          let detailMsg = `Server returned ${res.status}`;
          try {
            const data = await res.json();
            if (data.detail) detailMsg = `Server returned ${res.status}: ${data.detail}`;
            else if (data.error) detailMsg = `Server returned ${res.status}: ${data.error}`;
          } catch {}

          if (res.status === 502 || res.status === 503 || res.status === 504) {
            lastError = new Error(`Server returned ${res.status} (starting up)`);
            if (attempt < maxAttempts) {
              if (onProgress) {
                onProgress(
                  18,
                  `${candidate.role === 'backup' ? '[BACKUP] ' : ''}SERVER IS WAKING UP... attempt ${attempt + 1}/${maxAttempts}`
                );
              }
              await new Promise((r) => setTimeout(r, 5000));
              continue;
            }
            // Exhausted retries on this server
            break;
          }

          // 400 / 403 / 404 / 500 immediate error
          throw new Error(detailMsg);
        }

        // Success -> mark working server
        setStickyActiveServer(candidate.role);

        const contentType = res.headers.get('content-type') || '';
        const isZip = contentType.includes('application/zip');
        const contentDisposition = res.headers.get('content-disposition') || '';

        let serverFileName = '';
        const filenameMatch = contentDisposition.match(/filename=["']?([^"';]+)["']?/i);
        if (filenameMatch && filenameMatch[1]) {
          serverFileName = filenameMatch[1].trim();
        }

        if (onProgress) onProgress(50, isZip ? 'DOWNLOADING... photos archive' : 'DOWNLOADING...');

        const contentLength = res.headers.get('content-length');
        const totalBytes = contentLength ? parseInt(contentLength, 10) : 0;

        let blob: Blob;
        if (res.body && totalBytes > 0 && typeof ReadableStream !== 'undefined') {
          const reader = res.body.getReader();
          let receivedBytes = 0;
          let lastSampleTime = performance.now();
          let lastSampleBytes = 0;
          let currentSpeedMb = 0;
          const chunks: Uint8Array[] = [];

          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            if (value) {
              chunks.push(value);
              receivedBytes += value.length;
              const now = performance.now();
              const dtSec = (now - lastSampleTime) / 1000;
              if (dtSec >= 0.25) {
                const deltaBytes = receivedBytes - lastSampleBytes;
                currentSpeedMb = deltaBytes / (1024 * 1024) / dtSec;
                lastSampleTime = now;
                lastSampleBytes = receivedBytes;
              }
              const pct = Math.min(95, Math.round(50 + (receivedBytes / totalBytes) * 45));
              if (onProgress) {
                onProgress(
                  pct,
                  `DOWNLOADING... ${(receivedBytes / (1024 * 1024)).toFixed(1)} MB`,
                  currentSpeedMb
                );
              }
            }
          }
          blob = new Blob(chunks as BlobPart[], {
            type: isZip ? 'application/zip' : quality === 'audio' ? 'audio/mpeg' : 'video/mp4',
          });
        } else {
          blob = await res.blob();
        }

        if (onProgress) onProgress(98, 'Saving file to device...');

        const ext = quality === 'audio' ? '.mp3' : '.mp4';
        const inferredPlatform = detectPlatform(normalized);
        let fileName: string;
        if (isZip) {
          const sanitizedTitle = title.replace(/[^a-zA-Z0-9_\- ]/g, '_').trim().substring(0, 60) || 'TikTok_photos';
          fileName = serverFileName || `${sanitizedTitle} [TikTok photos].zip`;
        } else if (settings.fileNameStyle && settings.fileNameStyle !== 'title_platform') {
          fileName = formatDownloadFileName(title, inferredPlatform, ext, settings.fileNameStyle);
        } else {
          fileName = formatDownloadFileName(title, inferredPlatform, ext, 'title_platform');
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
        clearTimeout(timeoutId);
        if (typeof navigator !== 'undefined' && navigator.onLine === false) {
          throw new Error('No internet');
        }
        const isAbortTimeout = err?.name === 'AbortError';
        const isImmediateConnectionOrDns = err instanceof TypeError;

        if (isImmediateConnectionOrDns) {
          lastError = new Error(err?.message || 'Connection failed');
          if (hasNextServer) {
            if (onProgress) onProgress(20, 'Primary unreachable — switching to BACKUP...');
            break; // Immediate switch to backup on DNS / connection error
          }
          if (attempt < maxAttempts) {
            await new Promise((r) => setTimeout(r, 5000));
            continue;
          }
          break;
        }

        if (isAbortTimeout) {
          lastError = new Error('Timed out after 180s');
          if (attempt < maxAttempts) {
            await new Promise((r) => setTimeout(r, 5000));
            continue;
          }
          break;
        }

        // Immediate HTTP 4xx/500 error thrown above
        throw err;
      }
    }

    if (hasNextServer && onProgress) {
      onProgress(20, 'Switching to backup server...');
    }
  }

  throw lastError || new Error('Download failed.');
}

export function getSpeedTestHistory(): SpeedTestResult[] {
  try {
    const raw = localStorage.getItem(SPEED_TEST_HISTORY_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.slice(0, 5) : [];
  } catch {
    return [];
  }
}

export function saveSpeedTestHistory(item: SpeedTestResult): SpeedTestResult[] {
  const prev = getSpeedTestHistory();
  const next = [item, ...prev].slice(0, 5);
  try {
    localStorage.setItem(SPEED_TEST_HISTORY_KEY, JSON.stringify(next));
  } catch {}
  return next;
}

export async function performServerSpeedTest(settings: AppSettings): Promise<SpeedTestResult> {
  const activeTarget = getActiveServerTarget(settings);

  if (Capacitor.isNativePlatform()) {
    const nativeRes = await VideoDownloader.runServerSpeedTest({
      url: activeTarget.baseUrl,
      apiKey: activeTarget.apiKey,
      serverRole: activeTarget.role,
    });
    if (nativeRes.supported) {
      saveSpeedTestHistory(nativeRes);
    }
    return nativeRes;
  }

  // Web execution with fetch
  const base = activeTarget.baseUrl;
  const healthUrl = base ? `${base}/health` : '/api/health';
  const speedUrl = base ? `${base}/speedtest?size=2000000` : '/api/speedtest?size=2000000';
  const headers: Record<string, string> = {};
  if (activeTarget.apiKey) {
    headers['x-api-key'] = activeTarget.apiKey;
  }

  const latencies: number[] = [];
  for (let i = 0; i < 5; i++) {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 15000);
    const t0 = performance.now();
    const res = await fetch(healthUrl, {
      method: 'GET',
      headers,
      cache: 'no-store',
      signal: controller.signal,
    });
    clearTimeout(timeoutId);
    const dt = Math.max(1, Math.round(performance.now() - t0));
    if (!res.ok) {
      throw new Error(`Health ping ${i + 1}/5 failed (HTTP ${res.status})`);
    }
    latencies.push(dt);
  }

  const minLatencyMs = Math.min(...latencies);
  const maxLatencyMs = Math.max(...latencies);
  const avgLatencyMs = Math.round(latencies.reduce((a, b) => a + b, 0) / latencies.length);

  const dlController = new AbortController();
  const dlTimeoutId = setTimeout(() => dlController.abort(), 60000);
  const s0 = performance.now();
  const sRes = await fetch(speedUrl, {
    method: 'GET',
    headers,
    cache: 'no-store',
    signal: dlController.signal,
  });
  clearTimeout(dlTimeoutId);

  if (sRes.status === 404 || sRes.status === 405) {
    return {
      supported: false,
      serverRole: activeTarget.role,
      serverUrl: base || window.location.origin,
      avgLatencyMs,
      minLatencyMs,
      maxLatencyMs,
      mbps: 0,
      bytesDownloaded: 0,
      downloadElapsedMs: 0,
      verdict: avgLatencyMs < 1500 ? 'GOOD' : avgLatencyMs >= 4000 ? 'VERY SLOW' : 'SLOW',
      testedAt: Date.now(),
      error: 'Update your server to use the speed test',
    };
  }

  if (!sRes.ok) {
    throw new Error(`Speed test endpoint failed (HTTP ${sRes.status})`);
  }

  const buf = await sRes.arrayBuffer();
  const elapsedMs = Math.max(1, Math.round(performance.now() - s0));
  const bytesDownloaded = buf.byteLength;
  const mbps = Number(((bytesDownloaded * 8) / ((elapsedMs / 1000) * 1_000_000)).toFixed(2));

  const verdict: SpeedTestResult['verdict'] =
    avgLatencyMs < 1500 && mbps > 2
      ? 'GOOD'
      : avgLatencyMs >= 4000 || mbps < 0.75
      ? 'VERY SLOW'
      : 'SLOW';

  const result: SpeedTestResult = {
    supported: true,
    serverRole: activeTarget.role,
    serverUrl: base || window.location.origin,
    avgLatencyMs,
    minLatencyMs,
    maxLatencyMs,
    mbps,
    bytesDownloaded,
    downloadElapsedMs: elapsedMs,
    verdict,
    testedAt: Date.now(),
  };
  saveSpeedTestHistory(result);
  return result;
}
