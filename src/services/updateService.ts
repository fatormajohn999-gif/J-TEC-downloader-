import { VideoDownloader } from '../plugins/videoDownloader';
import { AppUpdateInfo } from '../types';

export const GITHUB_REPO = 'fatormajohn999-gif/J-TEC-downloader-';
const STORAGE_LAST_CHECK = 'omnidrop_last_update_check';
const STORAGE_SKIPPED_VERSION = 'omnidrop_skipped_update_version';
const STORAGE_CACHED_UPDATE = 'omnidrop_cached_update_info';
const ONE_HOUR_MS = 60 * 60 * 1000;

export interface CheckUpdateResult {
  hasUpdate: boolean;
  installedVersionName: string;
  installedVersionCode: number;
  updateInfo: AppUpdateInfo | null;
  isSkipped: boolean;
  lastCheckedTime: number;
  error?: string;
}

/**
 * Extracts numeric build number N after the last dot in tag_name (e.g. "v1.0.25" -> 25)
 */
export function extractVersionCodeFromTag(tagName: string): number {
  if (!tagName) return 0;
  const parts = tagName.split('.');
  const lastPart = parts[parts.length - 1];
  const digits = lastPart.replace(/\D/g, '');
  return parseInt(digits, 10) || 0;
}

export function getSkippedUpdateVersion(): number {
  try {
    const val = localStorage.getItem(STORAGE_SKIPPED_VERSION);
    return val ? parseInt(val, 10) : 0;
  } catch {
    return 0;
  }
}

export function setSkippedUpdateVersion(versionCode: number): void {
  try {
    localStorage.setItem(STORAGE_SKIPPED_VERSION, String(versionCode));
  } catch {}
}

export function getLastUpdateCheckTime(): number {
  try {
    const val = localStorage.getItem(STORAGE_LAST_CHECK);
    return val ? parseInt(val, 10) : 0;
  } catch {
    return 0;
  }
}

export function getCachedUpdateInfo(): AppUpdateInfo | null {
  try {
    const raw = localStorage.getItem(STORAGE_CACHED_UPDATE);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/**
 * Checks GitHub Releases for new APK update
 */
export async function checkForAppUpdate(force = false): Promise<CheckUpdateResult> {
  let installedVersionName = '1.0.0';
  let installedVersionCode = 1;

  try {
    const versionRes = await VideoDownloader.getAppVersion();
    if (versionRes && versionRes.versionCode) {
      installedVersionName = versionRes.versionName || `1.0.${versionRes.versionCode}`;
      installedVersionCode = versionRes.versionCode;
    }
  } catch {
    // Keep fallback
  }

  const now = Date.now();
  const lastCheck = getLastUpdateCheckTime();

  // If not forced and checked within the last hour, use cached result if available
  if (!force && lastCheck > 0 && now - lastCheck < ONE_HOUR_MS) {
    const cached = getCachedUpdateInfo();
    const skippedCode = getSkippedUpdateVersion();
    const hasUpdate = cached ? cached.versionCode > installedVersionCode : false;
    return {
      hasUpdate,
      installedVersionName,
      installedVersionCode,
      updateInfo: cached,
      isSkipped: cached ? cached.versionCode === skippedCode : false,
      lastCheckedTime: lastCheck,
    };
  }

  try {
    const response = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/latest`, {
      headers: {
        Accept: 'application/vnd.github.v3+json',
      },
    });

    if (!response.ok) {
      throw new Error(`GitHub API returned HTTP ${response.status}`);
    }

    const release = await response.json();
    const tagName = release.tag_name || '';
    const remoteVersionCode = extractVersionCodeFromTag(tagName);
    const releaseNotes = release.body || 'No release notes provided.';
    const publishedAt = release.published_at || new Date().toISOString();

    const assets: any[] = Array.isArray(release.assets) ? release.assets : [];
    const apkAsset = assets.find((a: any) => typeof a.name === 'string' && a.name.toLowerCase().endsWith('.apk'));

    if (!apkAsset) {
      throw new Error('No .apk release asset found in latest GitHub release');
    }

    const downloadUrl = apkAsset.browser_download_url;
    const sizeBytes = apkAsset.size || 0;

    const updateInfo: AppUpdateInfo = {
      versionName: tagName,
      versionCode: remoteVersionCode,
      tagName,
      releaseNotes,
      publishedAt,
      downloadUrl,
      sizeBytes,
    };

    try {
      localStorage.setItem(STORAGE_LAST_CHECK, String(now));
      localStorage.setItem(STORAGE_CACHED_UPDATE, JSON.stringify(updateInfo));
    } catch {}

    const hasUpdate = remoteVersionCode > installedVersionCode;
    const skippedCode = getSkippedUpdateVersion();

    return {
      hasUpdate,
      installedVersionName,
      installedVersionCode,
      updateInfo,
      isSkipped: remoteVersionCode === skippedCode,
      lastCheckedTime: now,
    };
  } catch (err: any) {
    return {
      hasUpdate: false,
      installedVersionName,
      installedVersionCode,
      updateInfo: getCachedUpdateInfo(),
      isSkipped: false,
      lastCheckedTime: lastCheck,
      error: err?.message || 'Failed to check GitHub releases',
    };
  }
}
