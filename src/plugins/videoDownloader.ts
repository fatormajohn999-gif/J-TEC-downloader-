import { registerPlugin, PluginListenerHandle } from '@capacitor/core';
import { DownloadHistoryItem, VideoLibraryItem, PhotoLibraryItem } from '../types';

export interface VideoDownloaderPlugin {
  hasOverlayPermission(): Promise<{ granted: boolean }>;
  requestOverlayPermission(): Promise<{ requested: boolean }>;
  hasVideoPermission(): Promise<{ granted: boolean }>;
  requestVideoPermission(): Promise<{ requested: boolean }>;
  getVideoLibrary(): Promise<{ videos: VideoLibraryItem[] }>;
  deleteVideo(options: { uri: string }): Promise<{ deleted: boolean }>;
  getPhotoLibrary(): Promise<{ photos: PhotoLibraryItem[] }>;
  deletePhotos(options: { uris: string[] }): Promise<{ deleted: boolean; count: number }>;
  openPhotoViewer(options: { uris: string[]; index?: number; postTitle?: string }): Promise<{ started: boolean }>;
  renameVideo(options: { uri: string; newTitle: string; newPlatform: string }): Promise<{ success: boolean; title: string; platform: string }>;
  checkDuplicate(options: { url: string }): Promise<{ exists: boolean; uri?: string; title?: string; fileName?: string }>;
  playVideo(options: {
    uri: string;
    title: string;
    playlist?: Array<{
      uri: string;
      title: string;
      platform?: string;
    }>;
    initialIndex?: number;
  }): Promise<{ started: boolean }>;
  shareVideo(options: { uri: string; title?: string }): Promise<{ shared: boolean }>;
  startBubble(): Promise<{ status: string }>;
  stopBubble(): Promise<{ status: string }>;
  showPersistentNotification(options?: { title?: string; message?: string }): Promise<void>;
  dismissPersistentNotification(): Promise<void>;
  showDownloadNotification(options: { title: string; message: string; filePath?: string }): Promise<void>;
  getLastSharedUrl(): Promise<{ url: string }>;
  saveNativeSettings(options: {
    backendUrl: string;
    apiKey?: string;
    defaultQuality?: string;
    autoDownload?: boolean;
    autoDownloadOnPaste?: boolean;
    autoPlayNext?: boolean;
    repeatMode?: 'off' | 'one' | 'all';
    floatingSide?: 'left' | 'right';
    floatingShowMs?: boolean;
    floatingAutoClear?: boolean;
    floatingVibrate?: boolean;
  }): Promise<{ saved: boolean }>;
  clearMetadataCache(): Promise<{ cleared: boolean }>;
  getLastCrash(): Promise<{ crash: string }>;
  clearLastCrash(): Promise<{ cleared: boolean }>;
  downloadAndSave(options: {
    url: string;
    quality: string;
    title: string;
    backendUrl: string;
    apiKey?: string;
  }): Promise<{ fileName: string }>;
  getNativeHistory(): Promise<{ history: DownloadHistoryItem[] }>;
  clearNativeHistory(): Promise<{ cleared: boolean }>;
  addListener(
    eventName: 'onUrlReceived',
    listenerFunc: (data: { url: string }) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'onDownloadProgress',
    listenerFunc: (data: { percent: number; statusText: string }) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'nativeDownloadFinished',
    listenerFunc: (entry: DownloadHistoryItem) => void
  ): Promise<PluginListenerHandle>;
}

// Graceful web fallback implementation when running in browser or PWA
const VideoDownloaderWeb: VideoDownloaderPlugin = {
  async hasOverlayPermission() {
    return { granted: false };
  },
  async requestOverlayPermission() {
    return { requested: false };
  },
  async hasVideoPermission() {
    return { granted: true };
  },
  async requestVideoPermission() {
    return { requested: true };
  },
  async getVideoLibrary() {
    return { videos: [] };
  },
  async deleteVideo() {
    return { deleted: true };
  },
  async getPhotoLibrary() {
    return { photos: [] };
  },
  async deletePhotos(options) {
    return { deleted: true, count: options?.uris?.length || 0 };
  },
  async openPhotoViewer() {
    return { started: false };
  },
  async renameVideo(options) {
    return { success: true, title: options.newTitle, platform: options.newPlatform };
  },
  async checkDuplicate() {
    return { exists: false };
  },
  async playVideo() {
    return { started: false };
  },
  async shareVideo(options) {
    if (navigator.share) {
      try {
        await navigator.share({
          title: options.title || 'OmniDrop Video',
          url: options.uri,
        });
        return { shared: true };
      } catch {
        return { shared: false };
      }
    }
    return { shared: false };
  },
  async startBubble() {
    return { status: 'unsupported_web' };
  },
  async stopBubble() {
    return { status: 'stopped' };
  },
  async showPersistentNotification() {
    // Web persistent notifications not supported without web worker push
  },
  async dismissPersistentNotification() {
    // No-op on web
  },
  async showDownloadNotification(options) {
    if ('Notification' in window && Notification.permission === 'granted') {
      try {
        new Notification(options.title, {
          body: options.message,
          icon: '/icon.svg',
        });
      } catch {
        // Ignored
      }
    }
  },
  async getLastSharedUrl() {
    return { url: '' };
  },
  async saveNativeSettings() {
    return { saved: true };
  },
  async clearMetadataCache() {
    try {
      localStorage.removeItem('omnidrop_metadata_cache');
    } catch {}
    return { cleared: true };
  },
  async getLastCrash() {
    return { crash: '' };
  },
  async clearLastCrash() {
    return { cleared: true };
  },
  async downloadAndSave() {
    throw new Error('downloadAndSave native method is only available on Android platform.');
  },
  async getNativeHistory() {
    return { history: [] };
  },
  async clearNativeHistory() {
    return { cleared: true };
  },
  async addListener() {
    return {
      remove: async () => {},
    };
  },
};

export const VideoDownloader = registerPlugin<VideoDownloaderPlugin>('VideoDownloader', {
  web: () => VideoDownloaderWeb,
});

