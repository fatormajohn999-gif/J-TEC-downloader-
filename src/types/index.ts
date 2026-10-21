export type PlatformType = 'tiktok' | 'youtube' | 'instagram' | 'facebook' | 'video';

export interface VideoInfo {
  title: string;
  thumbnail: string;
  duration: number;
  uploader: string;
  qualities: string[];
  platform: PlatformType;
  id?: string;
  url: string;
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
}

export interface AppSettings {
  backendUrl: string;
  apiKey: string;
  defaultQuality: string;
  floatingBubbleEnabled: boolean;
  persistentNotificationEnabled: boolean;
  autoDetectClipboard: boolean;
}
