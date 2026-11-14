import { registerPlugin, PluginListenerHandle } from '@capacitor/core';
import { DownloadHistoryItem, VideoLibraryItem, PhotoLibraryItem, FailedDownloadItem, CollectionItem, DownloadStatsData } from '../types';

export interface VideoDownloaderPlugin {
  hasOverlayPermission(): Promise<{ granted: boolean }>;
  requestOverlayPermission(): Promise<{ requested: boolean }>;
  hasVideoPermission(): Promise<{ granted: boolean }>;
  requestVideoPermission(): Promise<{ requested: boolean }>;
  getVideoLibrary(): Promise<{ videos: VideoLibraryItem[] }>;
  deleteVideo(options: { uri?: string; uris?: string[] }): Promise<{ deleted: number | boolean; count?: number; failed?: number; cancelled?: boolean }>;
  getPhotoLibrary(): Promise<{ photos: PhotoLibraryItem[] }>;
  deletePhotos(options: { uris?: string[]; uri?: string }): Promise<{ deleted: number | boolean; count?: number; failed?: number; cancelled?: boolean }>;
  openPhotoViewer(options: { uris: string[]; index?: number; postTitle?: string }): Promise<{ started: boolean }>;
  renameVideo(options: { uri: string; newTitle: string; newPlatform: string }): Promise<{ success: boolean; title?: string; platform?: string; cancelled?: boolean }>;
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
  startBubble(): Promise<{ status: string; needPermission?: boolean }>;
  stopBubble(): Promise<{ status: string }>;
  isBubbleRunning(): Promise<{ running: boolean }>;
  testBubbleNow(): Promise<{ success: boolean }>;
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
    floatingHideInsideApp?: boolean;
    autoCheckUpdates?: boolean;
  }): Promise<{ saved: boolean }>;
  clearMetadataCache(): Promise<{ cleared: boolean }>;
  getLastCrash(): Promise<{ crash: string }>;
  clearLastCrash(): Promise<{ cleared: boolean }>;
  getAppVersion(): Promise<{ versionName: string; versionCode: number }>;
  downloadAndInstallUpdate(options: {
    url: string;
    size?: number;
  }): Promise<{ started: boolean; needPermission?: boolean; message?: string }>;
  downloadAndSave(options: {
    url: string;
    quality: string;
    title: string;
    backendUrl: string;
    apiKey?: string;
  }): Promise<{ fileName: string }>;
  getNativeHistory(): Promise<{ history: DownloadHistoryItem[] }>;
  clearNativeHistory(): Promise<{ cleared: boolean }>;
  getFailedLinks(): Promise<{ failedLinks: FailedDownloadItem[] }>;
  removeFailedLink(options: { id: string }): Promise<{ removed: boolean }>;
  clearFailedLinks(): Promise<{ cleared: boolean }>;
  retryFailedLink(options: { id: string }): Promise<{ started: boolean }>;
  getFavoritesAndCollections(): Promise<{ favorites: string[]; collections: CollectionItem[] }>;
  toggleFavorite(options: { mediaKey: string }): Promise<{ mediaKey: string; isFavorite: boolean }>;
  createCollection(options: { name: string }): Promise<{ collection: CollectionItem }>;
  renameCollection(options: { id: string; name: string }): Promise<{ renamed: boolean }>;
  deleteCollection(options: { id: string }): Promise<{ deleted: boolean }>;
  addToCollection(options: { collectionId: string; mediaKeys: string[] }): Promise<{ success: boolean }>;
  removeFromCollection(options: { collectionId: string; mediaKeys: string[] }): Promise<{ success: boolean }>;
  getDownloadStats(): Promise<DownloadStatsData>;
  resetDownloadStats(): Promise<{ reset: boolean }>;
  extractAudio(options: { uri: string; title?: string }): Promise<{
    success: boolean;
    uri?: string;
    fileName?: string;
    mimeType?: string;
    title?: string;
    relativePath?: string;
    error?: string;
  }>;
  openAudio(options: { uri: string }): Promise<{ success: boolean }>;
  shareAudio(options: { uri: string; title?: string }): Promise<{ shared: boolean }>;
  requestAddQuickTile(): Promise<{
    supported: boolean;
    result: 'added' | 'already_added' | 'declined' | 'manual';
    error?: string;
  }>;
  getKeepAliveStatus(): Promise<{
    isRunning: boolean;
    lastPingTime: number;
    lastPingMs: number;
    lastPingStatus: string;
    nextPingTime: number;
    pingsToday: number;
    estimatedHoursThisMonth: number;
  }>;
  addListener(
    eventName: 'onUrlReceived',
    listenerFunc: (data: { url: string }) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'onDownloadProgress',
    listenerFunc: (data: { percent: number; statusText: string; speedMb?: number }) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'nativeDownloadFinished',
    listenerFunc: (entry: DownloadHistoryItem) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'updateProgress',
    listenerFunc: (data: { percent: number; bytesDownloaded: number; totalBytes: number; formattedMb: string }) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'openSettingsTab',
    listenerFunc: (data: { tab: string }) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'onFailedLinksChanged',
    listenerFunc: () => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'onFavoritesChanged',
    listenerFunc: (data: { mediaKey: string; isFavorite: boolean }) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'onCollectionsChanged',
    listenerFunc: () => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'audioExtractProgress',
    listenerFunc: (data: { percent: number; title: string }) => void
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
  async isBubbleRunning() {
    return { running: false };
  },
  async testBubbleNow() {
    return { success: true };
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
  async getAppVersion() {
    return { versionName: '1.0.1', versionCode: 1 };
  },
  async downloadAndInstallUpdate() {
    return { started: false, needPermission: false, message: 'In-app update installation is only supported on Android native devices.' };
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
  async getFailedLinks() {
    try {
      const raw = localStorage.getItem('omnidrop_failed_links');
      return { failedLinks: raw ? JSON.parse(raw) : [] };
    } catch {
      return { failedLinks: [] };
    }
  },
  async removeFailedLink(options: { id: string }) {
    try {
      const raw = localStorage.getItem('omnidrop_failed_links');
      const list: FailedDownloadItem[] = raw ? JSON.parse(raw) : [];
      const filtered = list.filter((item) => item.id !== options.id);
      localStorage.setItem('omnidrop_failed_links', JSON.stringify(filtered));
      return { removed: true };
    } catch {
      return { removed: false };
    }
  },
  async clearFailedLinks() {
    try {
      localStorage.removeItem('omnidrop_failed_links');
    } catch {}
    return { cleared: true };
  },
  async retryFailedLink() {
    return { started: true };
  },
  async getFavoritesAndCollections() {
    try {
      const favsRaw = localStorage.getItem('omnidrop_favorites');
      const colsRaw = localStorage.getItem('omnidrop_collections');
      return {
        favorites: favsRaw ? JSON.parse(favsRaw) : [],
        collections: colsRaw ? JSON.parse(colsRaw) : [],
      };
    } catch {
      return { favorites: [], collections: [] };
    }
  },
  async toggleFavorite(options: { mediaKey: string }) {
    try {
      const favsRaw = localStorage.getItem('omnidrop_favorites');
      const favs: string[] = favsRaw ? JSON.parse(favsRaw) : [];
      const exists = favs.includes(options.mediaKey);
      const updated = exists ? favs.filter((k) => k !== options.mediaKey) : [...favs, options.mediaKey];
      localStorage.setItem('omnidrop_favorites', JSON.stringify(updated));
      return { mediaKey: options.mediaKey, isFavorite: !exists };
    } catch {
      return { mediaKey: options.mediaKey, isFavorite: false };
    }
  },
  async createCollection(options: { name: string }) {
    const newCol: CollectionItem = {
      id: 'col_' + Date.now(),
      name: options.name,
      videoKeys: [],
      createdAt: Date.now(),
    };
    try {
      const colsRaw = localStorage.getItem('omnidrop_collections');
      const cols: CollectionItem[] = colsRaw ? JSON.parse(colsRaw) : [];
      cols.push(newCol);
      localStorage.setItem('omnidrop_collections', JSON.stringify(cols));
    } catch {}
    return { collection: newCol };
  },
  async renameCollection(options: { id: string; name: string }) {
    try {
      const colsRaw = localStorage.getItem('omnidrop_collections');
      const cols: CollectionItem[] = colsRaw ? JSON.parse(colsRaw) : [];
      const item = cols.find((c) => c.id === options.id);
      if (item) {
        item.name = options.name;
        localStorage.setItem('omnidrop_collections', JSON.stringify(cols));
        return { renamed: true };
      }
    } catch {}
    return { renamed: false };
  },
  async deleteCollection(options: { id: string }) {
    try {
      const colsRaw = localStorage.getItem('omnidrop_collections');
      const cols: CollectionItem[] = colsRaw ? JSON.parse(colsRaw) : [];
      const filtered = cols.filter((c) => c.id !== options.id);
      localStorage.setItem('omnidrop_collections', JSON.stringify(filtered));
      return { deleted: true };
    } catch {
      return { deleted: false };
    }
  },
  async addToCollection(options: { collectionId: string; mediaKeys: string[] }) {
    try {
      const colsRaw = localStorage.getItem('omnidrop_collections');
      const cols: CollectionItem[] = colsRaw ? JSON.parse(colsRaw) : [];
      const col = cols.find((c) => c.id === options.collectionId);
      if (col) {
        const set = new Set([...col.videoKeys, ...options.mediaKeys]);
        col.videoKeys = Array.from(set);
        localStorage.setItem('omnidrop_collections', JSON.stringify(cols));
        return { success: true };
      }
    } catch {}
    return { success: false };
  },
  async removeFromCollection(options: { collectionId: string; mediaKeys: string[] }) {
    try {
      const colsRaw = localStorage.getItem('omnidrop_collections');
      const cols: CollectionItem[] = colsRaw ? JSON.parse(colsRaw) : [];
      const col = cols.find((c) => c.id === options.collectionId);
      if (col) {
        const removeSet = new Set(options.mediaKeys);
        col.videoKeys = col.videoKeys.filter((k) => !removeSet.has(k));
        localStorage.setItem('omnidrop_collections', JSON.stringify(cols));
        return { success: true };
      }
    } catch {}
    return { success: false };
  },
  async getDownloadStats() {
    try {
      const raw = localStorage.getItem('omnidrop_stats');
      if (raw) return JSON.parse(raw);
    } catch {}
    return {
      totalDownloads: 0,
      totalBytes: 0,
      videoCount: 0,
      photoCount: 0,
      failedCount: 0,
      successRate: 100,
      platformCounts: {},
      dailyStats: [],
      busiestDay: 'None',
      averageBytes: 0,
    };
  },
  async resetDownloadStats() {
    try {
      localStorage.removeItem('omnidrop_stats');
    } catch {}
    return { reset: true };
  },
  async extractAudio(options: { uri: string; title?: string }) {
    const clean = (options.title || 'extracted_audio').replace(/[^a-zA-Z0-9_-]/g, '_');
    return {
      success: true,
      uri: options.uri,
      fileName: `${clean}.m4a`,
      mimeType: 'audio/mp4',
      title: clean,
      relativePath: 'Music/OmniDrop',
    };
  },
  async openAudio() {
    return { success: true };
  },
  async shareAudio() {
    return { shared: true };
  },
  async requestAddQuickTile() {
    return { supported: false, result: 'manual' as const };
  },
  async getKeepAliveStatus() {
    try {
      const raw = localStorage.getItem('omnidrop_keep_alive_state');
      const parsed = raw ? JSON.parse(raw) : {};
      return {
        isRunning: !!parsed.isRunning,
        lastPingTime: parsed.lastPingTime || 0,
        lastPingMs: parsed.lastPingMs ?? -1,
        lastPingStatus: parsed.lastPingStatus || 'IDLE',
        nextPingTime: parsed.nextPingTime || 0,
        pingsToday: parsed.pingsToday || 0,
        estimatedHoursThisMonth: parsed.estimatedHoursThisMonth || 0,
      };
    } catch {
      return {
        isRunning: false,
        lastPingTime: 0,
        lastPingMs: -1,
        lastPingStatus: 'IDLE',
        nextPingTime: 0,
        pingsToday: 0,
        estimatedHoursThisMonth: 0,
      };
    }
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

