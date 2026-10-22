/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useEffect, useCallback } from 'react';
import { Header } from './components/Header';
import { BottomNav, NavTab } from './components/BottomNav';
import { HomeScreen } from './components/HomeScreen';
import { HistoryScreen } from './components/HistoryScreen';
import { SettingsScreen } from './components/SettingsScreen';
import { ServerWakingBanner } from './components/ServerWakingBanner';
import { ClipboardPromptModal } from './components/ClipboardPromptModal';
import { AppSettings, DownloadHistoryItem, PlatformType } from './types';
import { checkServerHealth, isSupportedUrl } from './services/api';
import { VideoDownloader } from './plugins/videoDownloader';

const STORAGE_KEY_SETTINGS = 'omnidrop_settings_v1';
const STORAGE_KEY_HISTORY = 'omnidrop_history_v1';

const DEFAULT_SETTINGS: AppSettings = {
  backendUrl: '',
  apiKey: '',
  defaultQuality: 'best',
  floatingBubbleEnabled: false,
  persistentNotificationEnabled: false,
  autoDetectClipboard: true,
};

export default function App() {
  const [currentTab, setCurrentTab] = useState<NavTab>('home');
  const [settings, setSettings] = useState<AppSettings>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY_SETTINGS);
      if (saved) return { ...DEFAULT_SETTINGS, ...JSON.parse(saved) };
    } catch {}
    return DEFAULT_SETTINGS;
  });

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

  // Persist settings
  const handleUpdateSettings = (newSettings: AppSettings) => {
    setSettings(newSettings);
    try {
      localStorage.setItem(STORAGE_KEY_SETTINGS, JSON.stringify(newSettings));
      VideoDownloader.saveNativeSettings({
        backendUrl: newSettings.backendUrl,
        apiKey: newSettings.apiKey,
        defaultQuality: newSettings.defaultQuality,
        autoDownload: true,
      }).catch(() => {});
    } catch {}
  };

  useEffect(() => {
    VideoDownloader.saveNativeSettings({
      backendUrl: settings.backendUrl,
      apiKey: settings.apiKey,
      defaultQuality: settings.defaultQuality,
      autoDownload: true,
    }).catch(() => {});
  }, []);

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

  // Sync native history on mount
  useEffect(() => {
    syncNativeHistory();
  }, [syncNativeHistory]);

  // Sync native history when coming to foreground
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

  // Listen to nativeDownloadFinished event
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

  // Persist history
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

  const handleDeleteHistoryItem = (id: string) => {
    const updated = history.filter((h) => h.id !== id);
    setHistory(updated);
    try {
      localStorage.setItem(STORAGE_KEY_HISTORY, JSON.stringify(updated));
    } catch {}
  };

  const handleClearHistory = () => {
    if (confirm('Clear all download history?')) {
      setHistory([]);
      try {
        localStorage.removeItem(STORAGE_KEY_HISTORY);
        VideoDownloader.clearNativeHistory().catch(() => {});
      } catch {}
    }
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
      // Clean query from browser bar
      window.history.replaceState({}, document.title, window.location.pathname);
      // Extract URL if text has words
      const match = sharedText.match(/(https?:\/\/[^\s]+)/i);
      const urlToUse = match ? match[1] : sharedText;
      if (isSupportedUrl(urlToUse)) {
        setPendingUrl(urlToUse);
        setCurrentTab('home');
      }
    }
  }, []);

  // Native Capacitor share / bridge intent listener
  useEffect(() => {
    let handle: any = null;
    (async () => {
      try {
        const last = await VideoDownloader.getLastSharedUrl();
        if (last && last.url && isSupportedUrl(last.url)) {
          setPendingUrl(last.url);
          setCurrentTab('home');
        }

        handle = await VideoDownloader.addListener('onUrlReceived', (data) => {
          if (data && data.url && isSupportedUrl(data.url)) {
            setPendingUrl(data.url);
            setCurrentTab('home');
          }
        });
      } catch {
        // Plugin might be running on web
      }
    })();

    return () => {
      if (handle && handle.remove) handle.remove();
    };
  }, []);

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
    } catch {
      // Clipboard read permission might not be granted; silent pass
    }
  }, [settings.autoDetectClipboard, pendingUrl, detectedClipboardUrl]);

  useEffect(() => {
    checkClipboardForLink();
    window.addEventListener('focus', checkClipboardForLink);
    return () => window.removeEventListener('focus', checkClipboardForLink);
  }, [checkClipboardForLink]);

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col font-sans antialiased">
      {/* Top Header */}
      <Header
        serverStatus={serverStatus}
        serverLatency={serverLatency}
        onRefreshHealth={refreshHealth}
        onOpenSettings={() => setCurrentTab('settings')}
      />

      {/* Main View Area */}
      <main className="flex-1 w-full max-w-md mx-auto px-4 pt-3">
        {/* Server Waking Indicator (for Render free tier spin-up) */}
        <ServerWakingBanner isWaking={isWakingServer} attempt={wakingAttempt} />

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
            onOpenSettings={() => setCurrentTab('settings')}
          />
        )}

        {currentTab === 'history' && (
          <HistoryScreen
            history={history}
            onReDownload={(url) => {
              setPendingUrl(url);
              setCurrentTab('home');
            }}
            onDeleteItem={handleDeleteHistoryItem}
            onClearAll={handleClearHistory}
          />
        )}

        {currentTab === 'settings' && (
          <SettingsScreen
            settings={settings}
            onUpdateSettings={handleUpdateSettings}
            onRefreshHealth={refreshHealth}
          />
        )}
      </main>

      {/* Auto-detected clipboard link modal prompt */}
      <ClipboardPromptModal
        detectedUrl={detectedClipboardUrl}
        onConfirm={(url) => {
          setDetectedClipboardUrl('');
          setPendingUrl(url);
          setCurrentTab('home');
        }}
        onDismiss={() => setDetectedClipboardUrl('')}
      />

      {/* Modern Bottom Navigation */}
      <BottomNav
        currentTab={currentTab}
        onSelectTab={setCurrentTab}
        historyCount={history.length}
      />
    </div>
  );
}
