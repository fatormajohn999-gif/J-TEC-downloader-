import { registerPlugin, PluginListenerHandle } from '@capacitor/core';
import { DownloadHistoryItem } from '../types';

export interface VideoDownloaderPlugin {
  hasOverlayPermission(): Promise<{ granted: boolean }>;
  requestOverlayPermission(): Promise<{ requested: boolean }>;
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
  }): Promise<{ saved: boolean }>;
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
