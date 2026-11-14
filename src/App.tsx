/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Header } from './components/Header';
import { BottomNav, NavTab } from './components/BottomNav';
import { HomeScreen } from './components/HomeScreen';
import { VideosScreen } from './components/VideosScreen';
import { PhotosScreen } from './components/PhotosScreen';
import { SettingsScreen } from './components/SettingsScreen';
import { AboutScreen } from './components/AboutScreen';
import { ServerWakingBanner } from './components/ServerWakingBanner';
import { ClipboardPromptModal } from './components/ClipboardPromptModal';
import { BootSplash } from './components/BootSplash';
import { AppSettings, AppUpdateInfo, DownloadHistoryItem, PlatformType } from './types';
import { checkServerHealth, isSupportedUrl } from './services/api';
import { VideoDownloader } from './plugins/videoDownloader';
import {
  checkForAppUpdate,
  getCachedUpdateInfo,
  getSeenWhatsNewTag,
  markWhatsNewSeen,
} from './services/updateService';

const STORAGE_KEY_SETTINGS = 'omnidrop_settings_v1';
const STORAGE_KEY_HISTORY = 'omnidrop_history_v1';

const TAB_ORDER: NavTab[] = ['home', 'videos', 'photos', 'settings', 'about'];

const DEFAULT_SETTINGS: AppSettings = {
  backendUrl: '',
  apiKey: '',
  defaultQuality: 'best',
  floatingBubbleEnabled: false,
  persistentNotificationEnabled: false,
  autoDetectClipboard: true,
  autoDownloadOnPaste: false,
  autoPlayNext: true,
  repeatMode: 'off',
  floatingSide: 'right',
  floatingShowMs: true,
  floatingAutoClear: true,
  floatingVibrate: true,
  floatingHideInsideApp: true,
  autoCheckUpdates: true,
  accentColor: 'cyan',
  reduceAnimations: false,
};

function applyAppearanceToDom(settings: AppSettings) {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  root.setAttribute('data-accent', settings.accentColor || 'cyan');
  root.classList.toggle('reduce-motion', Boolean(settings.reduceAnimations));
}

export default function App() {
  const [settings, setSettings] = useState<AppSettings>(() => {
    let loaded = DEFAULT_SETTINGS;
    try {
      const saved = localStorage.getItem(STORAGE_KEY_SETTINGS);
      if (saved) loaded = { ...DEFAULT_SETTINGS, ...JSON.parse(saved) };
    } catch {}
    applyAppearanceToDom(loaded);
    return loaded;
  });

  const [showBootSplash, setShowBootSplash] = useState<boolean>(() => !settings.reduceAnimations);
  const [currentTab, setCurrentTab] = useState<NavTab>('home');
  const prevTabIndexRef = useRef<number>(0);
  const [tabDirection, setTabDirection] = useState<'left' | 'right' | 'none'>('none');

  const [hasUpdateWaiting, setHasUpdateWaiting] = useState(false);
  const [latestRelease, setLatestRelease] = useState<AppUpdateInfo | null>(() => getCachedUpdateInfo());
  const [seenWhatsNewTag, setSeenWhatsNewTag] = useState<string>(() => getSeenWhatsNewTag());

  const [history, setHistory] = useState<DownloadHistoryItem[]>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY_HISTORY);
      if (saved) return JSON.parse(saved);
    } catch {}
    return [];
  });

  const [serverStatus, setServerStatus] = useState<'connected' | 'checking' | 'offline'>('checking');
  const [serverLatency, setServerLatency] = useState<number | null>(null);
  const [isWakingServer, setIsWakingServer] = useState(false);
  const [wakingAttempt, setWakingAttempt] = useState(1);

  const [pendingUrl, setPendingUrl] = useState<string>('');
  const [detectedClipboardUrl, setDetectedClipboardUrl] = useState<string>('');

  // Keep DOM accent + reduce-motion attributes in sync with settings
  useEffect(() => {
    applyAppearanceToDom(settings);
  }, [settings.accentColor, settings.reduceAnimations]);

  // Tab switch with directional transition
  const handleSelectTab = useCallback((nextTab: NavTab) => {
    setCurrentTab((prev) => {
      if (prev === nextTab) return prev;
      const oldIdx = TAB_ORDER.indexOf(prev);
      const newIdx = TAB_ORDER.indexOf(nextTab);
      prevTabIndexRef.current = oldIdx;
      setTabDirection(newIdx >= oldIdx ? 'right' : 'left');
      return nextTab;
    });
  }, []);

  // Persist settings
  const handleUpdateSettings = (newSettings: AppSettings) => {
    setSettings(newSettings);
    applyAppearanceToDom(newSettings);
    try {
      localStorage.setItem(STORAGE_KEY_SETTINGS, JSON.stringify(newSettings));
      VideoDownloader.saveNativeSettings({
        backendUrl: newSettings.backendUrl,
        apiKey: newSettings.apiKey,
        defaultQuality: newSettings.defaultQuality,
        autoDownload: true,
        autoDownloadOnPaste: newSettings.autoDownloadOnPaste,
        autoPlayNext: newSettings.autoPlayNext,
        repeatMode: newSettings.repeatMode,
        floatingSide: newSettings.floatingSide,
        floatingShowMs: newSettings.floatingShowMs,
        floatingAutoClear: newSettings.floatingAutoClear ?? true,
        floatingVibrate: newSettings.floatingVibrate ?? true,
        floatingHideInsideApp: newSettings.floatingHideInsideApp ?? true,
        autoCheckUpdates: newSettings.autoCheckUpdates ?? true,
      }).catch(() => {});
    } catch {}
  };

  useEffect(() => {
    VideoDownloader.saveNativeSettings({
      backendUrl: settings.backendUrl,
      apiKey: settings.apiKey,
      defaultQuality: settings.defaultQuality,
      autoDownload: true,
      autoDownloadOnPaste: settings.autoDownloadOnPaste,
      autoPlayNext: settings.autoPlayNext,
      repeatMode: settings.repeatMode,
      floatingSide: settings.floatingSide,
      floatingShowMs: settings.floatingShowMs,
      floatingAutoClear: settings.floatingAutoClear ?? true,
      floatingVibrate: settings.floatingVibrate ?? true,
      floatingHideInsideApp: settings.floatingHideInsideApp ?? true,
      autoCheckUpdates: settings.autoCheckUpdates ?? true,
    }).catch(() => {});

    // Check updates & latest release notes on app open (throttled to at most once per hour)
    checkForAppUpdate(false)
      .then((res) => {
        if (res.updateInfo) {
          setLatestRelease(res.updateInfo);
        }
        if (res.hasUpdate && !res.isSkipped) {
          setHasUpdateWaiting(true);
        }
      })
      .catch(() => {});

    // Auto-start floating tab service on app open if toggle is ON, permission granted, and not running
    if (settings.floatingBubbleEnabled) {
      VideoDownloader.hasOverlayPermission()
        .then((perm) => {
          if (perm.granted) {
            VideoDownloader.isBubbleRunning()
              .then((runningRes) => {
                if (!runningRes.running) {
                  VideoDownloader.startBubble().catch(() => {});
                }
              })
              .catch(() => {});
          }
        })
        .catch(() => {});
    }
  }, []);

  // Listen to native openSettingsTab event (from notification click)
  useEffect(() => {
    let handle: any = null;
    VideoDownloader.addListener('openSettingsTab', () => {
      handleSelectTab('settings');
    })
      .then((h) => {
        handle = h;
      })
      .catch(() => {});

    return () => {
      if (handle && handle.remove) handle.remove();
    };
  }, [handleSelectTab]);

  const mergeNativeItems = useCallback((nativeItems: DownloadHistoryItem[]) => {
    if (!nativeItems || nativeItems.length === 0) return;
    setHistory((prev) => {
      const merged = [...prev];
      for (const n of nativeItems) {
        const nTime = n.savedAt || n.downloadedAt || Date.now();
        const exists = merged.some((m) => {
          if (m.id && n.id && m.id === n.id) return true;
          const mTime = m.savedAt || m.downloadedAt;
          return m.url === n.url && Math.abs(mTime - nTime) < 3000;
        });
        if (!exists) {
          merged.push({
            ...n,
            id: n.id || Math.random().toString(36).substring(2, 9),
            downloadedAt: nTime,
            savedAt: nTime,
          });
        }
      }
      merged.sort((a, b) => (b.downloadedAt || 0) - (a.downloadedAt || 0));
      const capped = merged.slice(0, 200);
      try {
        localStorage.setItem(STORAGE_KEY_HISTORY, JSON.stringify(capped));
      } catch {}
      return capped;
    });
  }, []);

  const syncNativeHistory = useCallback(async () => {
    try {
      const res = await VideoDownloader.getNativeHistory();
      if (res && res.history && res.history.length > 0) {
        mergeNativeItems(res.history);
      }
    } catch {}
  }, [mergeNativeItems]);

  useEffect(() => {
    syncNativeHistory();
  }, [syncNativeHistory]);

  useEffect(() => {
    const handleFocus = () => syncNativeHistory();
    const handleVisibility = () => {
      if (document.visibilityState === 'visible') syncNativeHistory();
    };
    window.addEventListener('focus', handleFocus);
    document.addEventListener('visibilitychange', handleVisibility);
    return () => {
      window.removeEventListener('focus', handleFocus);
      document.removeEventListener('visibilitychange', handleVisibility);
    };
  }, [syncNativeHistory]);

  useEffect(() => {
    let handle: any = null;
    (async () => {
      try {
        handle = await VideoDownloader.addListener('nativeDownloadFinished', (entry: any) => {
          if (entry && entry.url) {
            mergeNativeItems([entry]);
          }
        });
      } catch {}
    })();
    return () => {
      if (handle && handle.remove) handle.remove();
    };
  }, [mergeNativeItems]);

  const handleDownloadCompleted = (item: {
    url: string;
    title: string;
    thumbnail: string;
    uploader: string;
    duration: number;
    quality: string;
    platform: PlatformType;
    fileName: string;
  }) => {
    const newItem: DownloadHistoryItem = {
      ...item,
      id: Math.random().toString(36).substring(2, 9),
      downloadedAt: Date.now(),
      savedAt: Date.now(),
    };
    const updated = [newItem, ...history.filter((h) => h.url !== item.url)].slice(0, 200);
    setHistory(updated);
    try {
      localStorage.setItem(STORAGE_KEY_HISTORY, JSON.stringify(updated));
    } catch {}
  };

  // Health check
  const refreshHealth = useCallback(async () => {
    setServerStatus('checking');
    const res = await checkServerHealth(settings);
    if (res.ok) {
      setServerStatus('connected');
      setServerLatency(res.latencyMs);
    } else {
      setServerStatus('offline');
      setServerLatency(null);
    }
  }, [settings]);

  useEffect(() => {
    refreshHealth();
    const interval = setInterval(refreshHealth, 60000);
    return () => clearInterval(interval);
  }, [refreshHealth]);

  // Handle Share Target query params (?text=... or ?url=...)
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const sharedText = params.get('text') || params.get('url') || '';
    if (sharedText) {
      window.history.replaceState({}, document.title, window.location.pathname);
      const match = sharedText.match(/(https?:\/\/[^\s]+)/i);
      const urlToUse = match ? match[1] : sharedText;
      if (isSupportedUrl(urlToUse)) {
        setPendingUrl(urlToUse);
        handleSelectTab('home');
      }
    }
  }, [handleSelectTab]);

  // Native Capacitor share / bridge intent listener
  useEffect(() => {
    let handle: any = null;
    (async () => {
      try {
        const last = await VideoDownloader.getLastSharedUrl();
        if (last && last.url && isSupportedUrl(last.url)) {
          setPendingUrl(last.url);
          handleSelectTab('home');
        }

        handle = await VideoDownloader.addListener('onUrlReceived', (data) => {
          if (data && data.url && isSupportedUrl(data.url)) {
            setPendingUrl(data.url);
            handleSelectTab('home');
          }
        });
      } catch {}
    })();

    return () => {
      if (handle && handle.remove) handle.remove();
    };
  }, [handleSelectTab]);

  // Auto-detect link in clipboard on app open or window focus
  const checkClipboardForLink = useCallback(async () => {
    if (!settings.autoDetectClipboard) return;
    try {
      if (navigator.clipboard && navigator.clipboard.readText) {
        const clip = await navigator.clipboard.readText();
        if (clip) {
          const match = clip.trim().match(/(https?:\/\/[^\s]+)/i);
          const link = match ? match[1] : clip.trim();
          if (isSupportedUrl(link) && link !== pendingUrl && link !== detectedClipboardUrl) {
            setDetectedClipboardUrl(link);
          }
        }
      }
    } catch {}
  }, [settings.autoDetectClipboard, pendingUrl, detectedClipboardUrl]);

  useEffect(() => {
    checkClipboardForLink();
    window.addEventListener('focus', checkClipboardForLink);
    return () => window.removeEventListener('focus', checkClipboardForLink);
  }, [checkClipboardForLink]);

  const handleMarkWhatsNewSeen = useCallback((tagName: string) => {
    if (!tagName) return;
    markWhatsNewSeen(tagName);
    setSeenWhatsNewTag(tagName);
  }, []);

  const handleRefreshRelease = useCallback(async () => {
    const res = await checkForAppUpdate(true);
    if (res.updateInfo) {
      setLatestRelease(res.updateInfo);
    }
    if (res.hasUpdate && !res.isSkipped) {
      setHasUpdateWaiting(true);
    }
    return res;
  }, []);

  const hasUnreadWhatsNew = Boolean(
    latestRelease?.tagName && latestRelease.tagName !== seenWhatsNewTag
  );

  const tabTransitionClass =
    settings.reduceAnimations || tabDirection === 'none'
      ? ''
      : tabDirection === 'right'
      ? 'tab-enter-right'
      : 'tab-enter-left';

  return (
    <div className="min-h-screen bg-[var(--od-bg-canvas)] bg-grid-machine text-slate-100 flex flex-col font-sans antialiased">
      {/* 1.2s Boot Splash */}
      {showBootSplash && (
        <BootSplash
          reduceAnimations={Boolean(settings.reduceAnimations)}
          onFinish={() => setShowBootSplash(false)}
        />
      )}

      {/* Top Header */}
      <Header
        serverStatus={serverStatus}
        serverLatency={serverLatency}
        onRefreshHealth={refreshHealth}
        onOpenSettings={() => handleSelectTab('settings')}
      />

      {/* Main View Area */}
      <main className="flex-1 w-full max-w-md mx-auto px-4 pt-3 overflow-x-clip">
        <ServerWakingBanner isWaking={isWakingServer} attempt={wakingAttempt} />

        <div key={currentTab} className={tabTransitionClass}>
          {currentTab === 'home' && (
            <HomeScreen
              settings={settings}
              onDownloadCompleted={handleDownloadCompleted}
              pendingUrl={pendingUrl}
              onClearPendingUrl={() => setPendingUrl('')}
              onWakeServerStart={() => {
                setIsWakingServer(true);
                setWakingAttempt(1);
              }}
              onWakeServerEnd={() => {
                setIsWakingServer(false);
                refreshHealth();
              }}
              onOpenSettings={() => handleSelectTab('settings')}
              recentDownloads={history}
              serverStatus={serverStatus}
              serverLatency={serverLatency}
            />
          )}

          {currentTab === 'videos' && <VideosScreen />}

          {currentTab === 'photos' && <PhotosScreen />}

          {currentTab === 'settings' && (
            <SettingsScreen
              settings={settings}
              onUpdateSettings={handleUpdateSettings}
              onRefreshHealth={refreshHealth}
              onUpdateAvailableChange={setHasUpdateWaiting}
            />
          )}

          {currentTab === 'about' && (
            <AboutScreen
              latestRelease={latestRelease}
              hasUnreadWhatsNew={hasUnreadWhatsNew}
              onMarkWhatsNewSeen={handleMarkWhatsNewSeen}
              onRefreshRelease={handleRefreshRelease}
            />
          )}
        </div>
      </main>

      {/* Auto-detected clipboard link modal prompt */}
      <ClipboardPromptModal
        detectedUrl={detectedClipboardUrl}
        onConfirm={(url) => {
          setDetectedClipboardUrl('');
          setPendingUrl(url);
          handleSelectTab('home');
        }}
        onDismiss={() => setDetectedClipboardUrl('')}
      />

      {/* Bottom Navigation */}
      <BottomNav
        currentTab={currentTab}
        onSelectTab={handleSelectTab}
        videoCount={history.length}
        hasUpdate={hasUpdateWaiting}
        hasUnreadWhatsNew={hasUnreadWhatsNew}
      />
    </div>
  );
}
