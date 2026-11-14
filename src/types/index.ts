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
  filesize?: number;
  filesize_approx?: number;
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

export type AccentTheme = 'cyan' | 'purple' | 'green' | 'amber' | 'red';
export type FileNameStyle = 'title_platform' | 'platform_title_date' | 'title_only';
export type FloatingSize = 'small' | 'medium' | 'large';
export type FloatingOpacity = 30 | 45 | 70 | 100;
export type MobileDataPromptMb = 'off' | 50 | 100 | 200;

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
  accentColor?: AccentTheme;
  reduceAnimations?: boolean;

  // 1. Downloads
  dataSaver?: boolean;
  mobileDataPromptMb?: MobileDataPromptMb;
  fallbackToBestQuality?: boolean;
  fileNameStyle?: FileNameStyle;
  notifyDownloadFinish?: boolean;
  notifyDownloadFail?: boolean;

  // 2. Player
  playerDefaultSpeed?: 0.5 | 1 | 1.5 | 2;
  playerDefaultFitMode?: 'fit' | 'fill';
  playerResumePlayback?: boolean;
  playerKeepScreenOn?: boolean;
  playerAutoPip?: boolean;
  playerGesturesEnabled?: boolean;

  // 3. Floating Tab appearance
  floatingSize?: FloatingSize;
  floatingOpacity?: FloatingOpacity;

  // 4. Privacy
  hideInRecents?: boolean;
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
