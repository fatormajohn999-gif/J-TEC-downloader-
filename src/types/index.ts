export type PlatformType = 'tiktok' | 'youtube' | 'instagram' | 'facebook' | 'other' | string;

export interface VideoInfo {
  type?: 'video' | 'photo';
  title: string;
  thumbnail: string;
  duration: number;
  uploader: string;
  qualities: string[];
  platform: PlatformType;
  id?: string;
  url: string;
  imageCount?: number;
}

export interface VideoLibraryItem {
  id: string;
  uri: string;
  title: string;
  platform: PlatformType;
  size: number;
  durationMs: number;
  dateAdded: number;
  thumbnail: string;
  sourceUrl?: string;
  fileName?: string;
}

export interface PhotoLibraryItem {
  id: string;
  uri: string;
  fileName: string;
  size: number;
  dateAdded: number;
  width: number;
  height: number;
  postTitle: string;
  thumbnail: string;
  platform?: PlatformType;
  sourceUrl?: string;
}

export interface DownloadHistoryItem {
  id: string;
  url: string;
  title: string;
  thumbnail: string;
  uploader: string;
  duration: number;
  quality: string;
  platform: PlatformType;
  downloadedAt: number;
  fileName?: string;
  savedAt?: number;
  fileUri?: string;
}

export interface AppSettings {
  backendUrl: string;
  apiKey: string;
  defaultQuality: string;
  floatingBubbleEnabled: boolean;
  persistentNotificationEnabled: boolean;
  autoDetectClipboard: boolean;
  autoDownloadOnPaste?: boolean;
  autoPlayNext?: boolean;
  repeatMode?: 'off' | 'one' | 'all';
  floatingSide?: 'left' | 'right';
  floatingShowMs?: boolean;
  floatingAutoClear?: boolean;
  floatingVibrate?: boolean;
  floatingHideInsideApp?: boolean;
  autoCheckUpdates?: boolean;
  skippedUpdateVersion?: number;
  watchdogEnabled?: boolean;
  watchdogPaused?: boolean;
  watchdogInterval?: number;
  watchdogActiveFrom?: string;
  watchdogActiveTo?: string;
}

export interface AppUpdateInfo {
  versionName: string;
  versionCode: number;
  tagName: string;
  releaseNotes: string;
  publishedAt: string;
  downloadUrl: string;
  sizeBytes: number;
}

export interface UpdateProgressEvent {
  percent: number;
  bytesDownloaded: number;
  totalBytes: number;
  formattedMb: string;
}

export interface FailedDownloadItem {
  id: string;
  url: string;
  platform: PlatformType;
  title: string;
  reason: string;
  failedAt: number;
  attempts: number;
}

export interface CollectionItem {
  id: string;
  name: string;
  videoKeys: string[];
  createdAt: number;
}

export interface FavoritesAndCollectionsData {
  favorites: string[];
  collections: CollectionItem[];
}

export interface DownloadStatRecord {
  date: string; // YYYY-MM-DD
  platform: string;
  type: 'video' | 'photo' | 'audio';
  bytes: number;
}

export interface DownloadStatsData {
  totalDownloads: number;
  totalBytes: number;
  videoCount: number;
  photoCount: number;
  failedCount: number;
  successRate: number;
  platformCounts: Record<string, number>;
  dailyStats: Array<{
    date: string;
    downloads: number;
    bytes: number;
  }>;
  busiestDay: string;
  averageBytes: number;
}
