import React, { useState, useEffect } from 'react';
import {
  Server,
  Sliders,
  CheckCircle2,
  AlertCircle,
  Loader2,
  Layers,
  HardDrive,
  Trash2,
  RefreshCw,
  Cpu,
  ArrowDownToLine,
  AlertTriangle,
  X,
  Sparkles,
  Activity,
  Palette,
} from 'lucide-react';
import { AccentTheme, AppSettings, AppUpdateInfo, UpdateProgressEvent } from '../types';
import { checkServerHealth } from '../services/api';
import { VideoDownloader } from '../plugins/videoDownloader';
import {
  checkForAppUpdate,
  getLastUpdateCheckTime,
  setSkippedUpdateVersion,
  getCachedUpdateInfo,
} from '../services/updateService';
import { Capacitor } from '@capacitor/core';

interface SettingsScreenProps {
  settings: AppSettings;
  onUpdateSettings: (newSettings: AppSettings) => void;
  onRefreshHealth: () => void;
  onUpdateAvailableChange?: (available: boolean) => void;
}

const ACCENT_OPTIONS: Array<{ id: AccentTheme; label: string; dotColor: string; dotGlow: string }> = [
  { id: 'cyan', label: 'CYAN', dotColor: '#00f0ff', dotGlow: '0 0 8px #00f0ff' },
  { id: 'purple', label: 'PURPLE', dotColor: '#a855f7', dotGlow: '0 0 8px #a855f7' },
  { id: 'green', label: 'GREEN', dotColor: '#10b981', dotGlow: '0 0 8px #10b981' },
  { id: 'amber', label: 'AMBER', dotColor: '#f59e0b', dotGlow: '0 0 8px #f59e0b' },
  { id: 'red', label: 'RED', dotColor: '#f43f5e', dotGlow: '0 0 8px #f43f5e' },
];

export const SettingsScreen: React.FC<SettingsScreenProps> = ({
  settings,
  onUpdateSettings,
  onRefreshHealth,
  onUpdateAvailableChange,
}) => {
  const isNative = Capacitor.isNativePlatform();

  // Appearance state
  const [accentColor, setAccentColor] = useState<AccentTheme>(settings.accentColor || 'cyan');
  const [reduceAnimations, setReduceAnimations] = useState<boolean>(Boolean(settings.reduceAnimations));

  // Settings state
  const [backendUrl, setBackendUrl] = useState(settings.backendUrl || '');
  const [apiKey, setApiKey] = useState(settings.apiKey || '');
  const [defaultQuality, setDefaultQuality] = useState(settings.defaultQuality || 'best');
  const [autoDetectClipboard, setAutoDetectClipboard] = useState(settings.autoDetectClipboard ?? true);
  const [autoDownloadOnPaste, setAutoDownloadOnPaste] = useState(settings.autoDownloadOnPaste ?? false);
  const [autoPlayNext, setAutoPlayNext] = useState(settings.autoPlayNext ?? true);
  const [repeatMode, setRepeatMode] = useState<'off' | 'one' | 'all'>(settings.repeatMode || 'off');

  // Floating tab settings
  const [isBubbleActive, setIsBubbleActive] = useState(settings.floatingBubbleEnabled || false);
  const [floatingSide, setFloatingSide] = useState<'left' | 'right'>(settings.floatingSide || 'right');
  const [floatingShowMs, setFloatingShowMs] = useState(settings.floatingShowMs ?? true);
  const [floatingAutoClear, setFloatingAutoClear] = useState(settings.floatingAutoClear ?? true);
  const [floatingVibrate, setFloatingVibrate] = useState(settings.floatingVibrate ?? true);
  const [floatingHideInsideApp, setFloatingHideInsideApp] = useState(settings.floatingHideInsideApp ?? true);
  const [tabServiceStatus, setTabServiceStatus] = useState<'RUNNING' | 'STOPPED' | 'NO PERMISSION'>('STOPPED');
  const [isTestingTab, setIsTestingTab] = useState<boolean>(false);
  const [isAddingTile, setIsAddingTile] = useState<boolean>(false);

  // Automatic Keep-Alive read-only status (tied to Floating Tab)
  const [keepAliveStatus, setKeepAliveStatus] = useState<{
    isRunning: boolean;
    lastPingTime: number;
    lastPingMs: number;
    lastPingStatus: string;
    nextPingTime: number;
    pingsToday: number;
    estimatedHoursThisMonth: number;
  }>({
    isRunning: false,
    lastPingTime: 0,
    lastPingMs: -1,
    lastPingStatus: 'IDLE',
    nextPingTime: 0,
    pingsToday: 0,
    estimatedHoursThisMonth: 0,
  });

  // Update firmware state
  const [installedVersion, setInstalledVersion] = useState<{ versionName: string; versionCode: number }>({
    versionName: '1.0.0',
    versionCode: 1,
  });
  const [updateStatus, setUpdateStatus] = useState<'idle' | 'checking' | 'up_to_date' | 'update_available' | 'error'>('idle');
  const [updateInfo, setUpdateInfo] = useState<AppUpdateInfo | null>(() => getCachedUpdateInfo());
  const [lastCheckedTime, setLastCheckedTime] = useState<number>(() => getLastUpdateCheckTime());
  const [updateErrorMessage, setUpdateErrorMessage] = useState<string>('');
  const [autoCheckUpdates, setAutoCheckUpdates] = useState<boolean>(settings.autoCheckUpdates ?? true);
  const [isSkipped, setIsSkipped] = useState<boolean>(false);
  const [isDownloadingUpdate, setIsDownloadingUpdate] = useState<boolean>(false);
  const [downloadProgress, setDownloadProgress] = useState<UpdateProgressEvent>({
    percent: 0,
    bytesDownloaded: 0,
    totalBytes: 0,
    formattedMb: '0 MB',
  });

  // Dark robotic modal state
  const [modalState, setModalState] = useState<{
    isOpen: boolean;
    title: string;
    message: string;
    isError?: boolean;
    actionLabel?: string;
    cancelLabel?: string;
    onAction?: () => void;
    onCancel?: () => void;
  }>({
    isOpen: false,
    title: '',
    message: '',
  });

  // Connection test state
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'success' | 'error'>('idle');
  const [testResult, setTestResult] = useState<string>('');
  const [isSaved, setIsSaved] = useState(false);

  // Storage calculation state
  const [librarySizeMb, setLibrarySizeMb] = useState<number>(0);
  const [libraryCount, setLibraryCount] = useState<number>(0);
  const [isClearingCache, setIsClearingCache] = useState(false);
  const [cacheClearMsg, setCacheClearMsg] = useState<string | null>(null);

  const refreshKeepAliveStatus = async () => {
    try {
      const res = await VideoDownloader.getKeepAliveStatus();
      if (res) {
        setKeepAliveStatus(res);
      }
    } catch {}
  };

  const refreshTabServiceStatus = async () => {
    try {
      const perm = await VideoDownloader.hasOverlayPermission();
      if (!perm.granted) {
        setTabServiceStatus('NO PERMISSION');
        return;
      }
      const runningRes = await VideoDownloader.isBubbleRunning();
      const isRunning = !!runningRes.running;
      setIsBubbleActive(isRunning);
      setTabServiceStatus(isRunning ? 'RUNNING' : 'STOPPED');
    } catch {
      setTabServiceStatus('STOPPED');
    }
  };

  const loadStorageStats = async () => {
    try {
      const res = await VideoDownloader.getVideoLibrary();
      if (res && res.videos) {
        const totalBytes = res.videos.reduce((acc, v) => acc + (v.size || 0), 0);
        setLibrarySizeMb(Math.round(totalBytes / (1024 * 1024)));
        setLibraryCount(res.videos.length);
      }
    } catch {}
  };

  useEffect(() => {
    refreshTabServiceStatus();
    loadStorageStats();
    refreshKeepAliveStatus();

    VideoDownloader.getAppVersion()
      .then((ver) => {
        if (ver && ver.versionCode) {
          setInstalledVersion(ver);
        }
      })
      .catch(() => {});

    performUpdateCheck(false);

    const handleResume = () => {
      refreshTabServiceStatus();
      loadStorageStats();
      refreshKeepAliveStatus();
    };

    const handleVisibility = () => {
      if (document.visibilityState === 'visible') {
        handleResume();
      }
    };

    window.addEventListener('focus', handleResume);
    document.addEventListener('visibilitychange', handleVisibility);
    const kaTimer = setInterval(refreshKeepAliveStatus, 4000);

    return () => {
      window.removeEventListener('focus', handleResume);
      document.removeEventListener('visibilitychange', handleVisibility);
      clearInterval(kaTimer);
    };
  }, []);

  const performUpdateCheck = async (force: boolean) => {
    setUpdateStatus('checking');
    setUpdateErrorMessage('');

    try {
      const res = await checkForAppUpdate(force);
      setLastCheckedTime(res.lastCheckedTime);
      setInstalledVersion({
        versionName: res.installedVersionName,
        versionCode: res.installedVersionCode,
      });
      setUpdateInfo(res.updateInfo);
      setIsSkipped(res.isSkipped);

      if (res.hasUpdate && !res.isSkipped) {
        setUpdateStatus('update_available');
        if (onUpdateAvailableChange) onUpdateAvailableChange(true);
      } else if (res.hasUpdate && res.isSkipped) {
        setUpdateStatus('update_available');
        if (onUpdateAvailableChange) onUpdateAvailableChange(false);
      } else if (res.error) {
        setUpdateStatus('error');
        setUpdateErrorMessage(res.error);
        if (onUpdateAvailableChange) onUpdateAvailableChange(false);
      } else {
        setUpdateStatus('up_to_date');
        if (onUpdateAvailableChange) onUpdateAvailableChange(false);
      }
    } catch (err: any) {
      setUpdateStatus('error');
      setUpdateErrorMessage(err?.message || 'Failed to check GitHub releases');
    }
  };

  const handleStartDownloadUpdate = async () => {
    if (!updateInfo || !updateInfo.downloadUrl) return;
    setIsDownloadingUpdate(true);
    setDownloadProgress({
      percent: 0,
      bytesDownloaded: 0,
      totalBytes: updateInfo.sizeBytes || 0,
      formattedMb: '0 MB',
    });

    let progressHandle: any = null;
    try {
      progressHandle = await VideoDownloader.addListener('updateProgress', (ev) => {
        setDownloadProgress(ev);
      });

      const res = await VideoDownloader.downloadAndInstallUpdate({
        url: updateInfo.downloadUrl,
        size: updateInfo.sizeBytes,
      });

      if (res && res.needPermission) {
        setModalState({
          isOpen: true,
          title: 'PERMISSION REQUIRED',
          message: res.message || 'Turn on Allow from this source, then come back and tap Install again',
          actionLabel: 'OK',
          onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
        });
      }
    } catch (err: any) {
      setModalState({
        isOpen: true,
        title: 'UPDATE INSTALL FAILED',
        message: err?.message || 'Failed to download or install package update.',
        isError: true,
        actionLabel: 'DISMISS',
        onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
      });
    } finally {
      setIsDownloadingUpdate(false);
      if (progressHandle && typeof progressHandle.remove === 'function') {
        try {
          progressHandle.remove();
        } catch {}
      }
    }
  };

  const handleSkipVersion = () => {
    if (updateInfo) {
      setSkippedUpdateVersion(updateInfo.versionCode);
      setIsSkipped(true);
      if (onUpdateAvailableChange) {
        onUpdateAvailableChange(false);
      }
    }
  };

  const persistSettings = (overrides?: Partial<AppSettings>) => {
    const updated: AppSettings = {
      ...settings,
      backendUrl: backendUrl.trim(),
      apiKey: apiKey.trim(),
      defaultQuality,
      autoDetectClipboard,
      autoDownloadOnPaste,
      autoPlayNext,
      repeatMode,
      floatingBubbleEnabled: isBubbleActive,
      floatingSide,
      floatingShowMs,
      floatingAutoClear,
      floatingVibrate,
      floatingHideInsideApp,
      autoCheckUpdates,
      accentColor,
      reduceAnimations,
      ...overrides,
    };
    onUpdateSettings(updated);

    VideoDownloader.saveNativeSettings({
      backendUrl: (overrides?.backendUrl ?? backendUrl).trim(),
      apiKey: (overrides?.apiKey ?? apiKey).trim(),
      defaultQuality: overrides?.defaultQuality ?? defaultQuality,
      autoDownload: true,
      autoDownloadOnPaste: overrides?.autoDownloadOnPaste ?? autoDownloadOnPaste,
      autoPlayNext: overrides?.autoPlayNext ?? autoPlayNext,
      repeatMode: overrides?.repeatMode ?? repeatMode,
      floatingSide: overrides?.floatingSide ?? floatingSide,
      floatingShowMs: overrides?.floatingShowMs ?? floatingShowMs,
      floatingAutoClear: overrides?.floatingAutoClear ?? floatingAutoClear,
      floatingVibrate: overrides?.floatingVibrate ?? floatingVibrate,
      floatingHideInsideApp: overrides?.floatingHideInsideApp ?? floatingHideInsideApp,
      autoCheckUpdates: overrides?.autoCheckUpdates ?? autoCheckUpdates,
    }).catch(() => {});
  };

  const handleAccentChange = (theme: AccentTheme) => {
    setAccentColor(theme);
    persistSettings({ accentColor: theme });
  };

  const handleReduceAnimationsToggle = () => {
    const next = !reduceAnimations;
    setReduceAnimations(next);
    persistSettings({ reduceAnimations: next });
  };

  const handleSave = () => {
    persistSettings();
    setIsSaved(true);
    setTimeout(() => setIsSaved(false), 2200);
    onRefreshHealth();
  };

  const handleAddToQuickSettings = async () => {
    setIsAddingTile(true);
    try {
      const res = await VideoDownloader.requestAddQuickTile();
      if (res && res.supported) {
        if (res.result === 'added') {
          setModalState({
            isOpen: true,
            title: 'QUICK SETTINGS TILE ADDED',
            message: '"OmniDrop Tab" tile has been added to your Android Quick Settings panel. You can now toggle the edge tab directly from your status bar.',
            actionLabel: 'OK',
            onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
          });
        } else if (res.result === 'already_added') {
          setModalState({
            isOpen: true,
            title: 'TILE ALREADY PRESENT',
            message: '"OmniDrop Tab" is already added to your Quick Settings panel.',
            actionLabel: 'OK',
            onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
          });
        } else {
          setModalState({
            isOpen: true,
            title: 'REQUEST DECLINED',
            message: 'The quick settings tile addition request was dismissed or cancelled.',
            actionLabel: 'DISMISS',
            onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
          });
        }
      } else {
        setModalState({
          isOpen: true,
          title: 'ADD TO QUICK SETTINGS',
          message:
            'To add the OmniDrop Tab tile manually on your device:\n\n1. Pull down your notification bar twice to expand full Quick Settings.\n2. Tap the edit button (the pencil ✎ icon or the three-dot ⋮ menu).\n3. Find "OmniDrop Tab" in the available tile list.\n4. Drag "OmniDrop Tab" up into your active quick tile grid.',
          actionLabel: 'GOT IT',
          onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
        });
      }
    } catch {
      setModalState({
        isOpen: true,
        title: 'MANUAL SETUP',
        message: 'Pull down Quick Settings, tap the edit (pencil or ⋮) button, and drag "OmniDrop Tab" into the active grid.',
        actionLabel: 'OK',
        onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
      });
    } finally {
      setIsAddingTile(false);
    }
  };

  const handleTestConnection = async () => {
    setTestStatus('testing');
    setTestResult('');
    const tempSettings: AppSettings = {
      ...settings,
      backendUrl: backendUrl.trim(),
      apiKey: apiKey.trim(),
    };
    const res = await checkServerHealth(tempSettings);
    if (res.ok) {
      setTestStatus('success');
      setTestResult(`ONLINE • ${res.latencyMs}ms`);
    } else {
      setTestStatus('error');
      setTestResult(`FAILED • ${res.error || 'Server unreachable'}`);
    }
  };

  const handleToggleBubble = async () => {
    try {
      if (!isBubbleActive) {
        const perm = await VideoDownloader.hasOverlayPermission();
        if (!perm.granted) {
          await VideoDownloader.requestOverlayPermission();
          setTabServiceStatus('NO PERMISSION');
          setModalState({
            isOpen: true,
            title: 'SYSTEM OVERLAY ACCESS',
            message: 'Please grant "Display over other apps" permission in system settings, then toggle again.',
            actionLabel: 'OK',
            onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
          });
          return;
        }

        try {
          const res = await VideoDownloader.startBubble();
          if (res.status === 'no_permission') {
            setTabServiceStatus('NO PERMISSION');
            setModalState({
              isOpen: true,
              title: 'SYSTEM OVERLAY ACCESS',
              message: 'Please grant "Display over other apps" permission in system settings, then toggle again.',
              actionLabel: 'OK',
              onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
            });
            return;
          }
          setIsBubbleActive(true);
          setTabServiceStatus('RUNNING');
          onUpdateSettings({ ...settings, floatingBubbleEnabled: true });

          setModalState({
            isOpen: true,
            title: 'FLOATING TAB ACTIVE',
            message: 'Floating tab is ON. It appears when you leave OmniDrop.',
            actionLabel: 'GOT IT',
            onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
          });
        } catch (err: any) {
          setModalState({
            isOpen: true,
            title: 'SERVICE ERROR',
            message: `Could not start floating edge tab: ${err?.message || 'Unknown error'}`,
            isError: true,
            actionLabel: 'DISMISS',
            onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
          });
        }
      } else {
        await VideoDownloader.stopBubble();
        setIsBubbleActive(false);
        setTabServiceStatus('STOPPED');
        onUpdateSettings({ ...settings, floatingBubbleEnabled: false });
      }
      refreshKeepAliveStatus();
    } catch (e: any) {
      setModalState({
        isOpen: true,
        title: 'TOGGLE FAILED',
        message: e?.message || 'Unable to update floating tab state.',
        isError: true,
        actionLabel: 'DISMISS',
        onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
      });
    }
  };

  const handleTestTabNow = async () => {
    try {
      setIsTestingTab(true);
      await VideoDownloader.testBubbleNow();
      setTimeout(() => setIsTestingTab(false), 5000);
    } catch (err: any) {
      setIsTestingTab(false);
      setModalState({
        isOpen: true,
        title: 'TEST FAILED',
        message: `Could not test floating tab: ${err?.message || 'Is tab service running?'}`,
        isError: true,
        actionLabel: 'DISMISS',
        onAction: () => setModalState((prev) => ({ ...prev, isOpen: false })),
      });
    }
  };

  const handleGrantOverlay = async () => {
    try {
      await VideoDownloader.requestOverlayPermission();
      setTimeout(refreshTabServiceStatus, 1500);
    } catch {}
  };

  const handleClearMetadataCache = () => {
    setModalState({
      isOpen: true,
      title: 'CLEAR METADATA CACHE',
      message: 'Clear cached titles and preview thumbnails? Downloaded video files will not be deleted.',
      actionLabel: 'CLEAR CACHE',
      cancelLabel: 'CANCEL',
      onCancel: () => setModalState((prev) => ({ ...prev, isOpen: false })),
      onAction: async () => {
        setModalState((prev) => ({ ...prev, isOpen: false }));
        setIsClearingCache(true);
        setCacheClearMsg(null);
        try {
          await VideoDownloader.clearMetadataCache();
          try {
            localStorage.removeItem('omnidrop_metadata_cache');
          } catch {}
          setCacheClearMsg('Metadata cache cleared successfully!');
          setTimeout(() => setCacheClearMsg(null), 3000);
        } catch (e: any) {
          setCacheClearMsg(`Notice: ${e?.message || 'Cache cleared'}`);
        } finally {
          setIsClearingCache(false);
        }
      },
    });
  };

  const formatLastChecked = (timeMs: number): string => {
    if (!timeMs || timeMs <= 0) return 'Never';
    const diffSec = Math.floor((Date.now() - timeMs) / 1000);
    if (diffSec < 60) return 'Just now';
    if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
    const date = new Date(timeMs);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  const formatKeepAliveClock = (timeMs: number): string => {
    if (!timeMs || timeMs <= 0) return '—';
    return new Date(timeMs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
  };

  const renderMechanicalToggle = (
    label: string,
    sublabel: string,
    checked: boolean,
    onChange: () => void
  ) => {
    return (
      <div className="flex items-center justify-between py-2 border-b border-slate-800/60 last:border-0">
        <div className="min-w-0 pr-3">
          <div className="font-mono text-xs font-bold text-white uppercase">{label}</div>
          <div className="text-[11px] text-slate-400 font-sans mt-0.5">{sublabel}</div>
        </div>

        <button
          onClick={onChange}
          className={`machine-btn relative w-12 h-6.5 rounded-full p-0.5 flex items-center transition-all duration-200 shrink-0 ${
            checked ? 'border-accent-strong bg-accent-soft' : 'border-slate-700 bg-slate-950'
          }`}
        >
          <div
            className={`w-5 h-5 rounded-full flex items-center justify-center transition-all duration-200 transform ${
              checked
                ? 'translate-x-5.5 bg-accent led-accent text-slate-950'
                : 'translate-x-0 bg-slate-700 text-slate-400'
            }`}
          >
            <div className={`w-1.5 h-1.5 rounded-full ${checked ? 'bg-slate-950' : 'bg-slate-400'}`} />
          </div>
        </button>
      </div>
    );
  };

  return (
    <div className="space-y-4 pb-28">
      {/* Top Header & Mechanical Save Key */}
      <div className="flex items-center justify-between pt-1 gap-2">
        <div className="min-w-0">
          <h2 className="text-xs font-mono font-bold text-accent uppercase tracking-widest flex items-center gap-2 whitespace-nowrap">
            <span className="w-2 h-2 rounded-full led-accent shrink-0" />
            SETTINGS // CONTROL
          </h2>
          <p className="text-xs text-slate-400 mt-0.5 truncate">Hardware preferences & firmware configuration</p>
        </div>

        <button
          onClick={handleSave}
          className={`machine-btn px-4 py-1.5 rounded-lg font-mono text-xs font-bold uppercase tracking-wider transition-all duration-200 shrink-0 whitespace-nowrap ${
            isSaved
              ? 'bg-emerald-500 border-emerald-400 text-slate-950 shadow-[0_0_12px_rgba(16,185,129,0.8)]'
              : 'text-accent-soft'
          }`}
        >
          {isSaved ? 'SAVED' : 'SAVE'}
        </button>
      </div>

      {/* 0. APPEARANCE // THEME & MOTION */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3 stagger-card"
        style={{ '--stagger': 0 } as React.CSSProperties}
      >
        <div className="flex items-center justify-between pb-2 border-b border-accent">
          <div className="flex items-center gap-2">
            <Palette className="w-4 h-4 text-accent" />
            <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
              APPEARANCE // DISPLAY
            </h3>
          </div>
          <span className="font-mono text-[10px] text-accent-soft uppercase whitespace-nowrap">
            {accentColor.toUpperCase()}
          </span>
        </div>

        <div className="space-y-3">
          <div>
            <div className="text-[10px] font-mono text-slate-400 uppercase tracking-wider mb-1.5">
              ACCENT COLOR
            </div>
            <div className="grid grid-cols-5 gap-1.5">
              {ACCENT_OPTIONS.map((opt) => {
                const isSelected = accentColor === opt.id;
                return (
                  <button
                    key={opt.id}
                    onClick={() => handleAccentChange(opt.id)}
                    className={`py-2 px-1 rounded-lg flex flex-col items-center justify-center gap-1.5 font-mono text-[10px] font-bold transition-all whitespace-nowrap ${
                      isSelected
                        ? 'bg-slate-950 border-accent-strong border glow-accent-sm text-white'
                        : 'machine-btn text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    <span
                      className="w-3 h-3 rounded-full shrink-0"
                      style={{
                        backgroundColor: opt.dotColor,
                        boxShadow: isSelected ? opt.dotGlow : 'none',
                      }}
                    />
                    <span>{opt.label}</span>
                  </button>
                );
              })}
            </div>
          </div>

          {renderMechanicalToggle(
            'REDUCE ANIMATIONS',
            'Disable boot splash, page slides, stagger effects, and scan sweep',
            reduceAnimations,
            handleReduceAnimationsToggle
          )}
        </div>
      </div>

      {/* 1. APP UPDATE // FIRMWARE */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3.5 border border-accent stagger-card"
        style={{ '--stagger': 1 } as React.CSSProperties}
      >
        <div className="flex items-center justify-between gap-2 pb-2 border-b border-accent">
          <div className="flex items-center gap-2 min-w-0">
            <Cpu className="w-4 h-4 text-accent shrink-0" />
            <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider truncate">
              APP UPDATE // FIRMWARE
            </h3>
          </div>
          <div className="flex items-center gap-2 shrink-0 whitespace-nowrap">
            {updateStatus === 'checking' && (
              <div className="flex items-center gap-1.5 font-mono text-[11px] text-accent-soft">
                <span className="w-2 h-2 rounded-full led-accent animate-pulse" />
                <span>CHECKING...</span>
              </div>
            )}
            {updateStatus === 'up_to_date' && (
              <div className="flex items-center gap-1.5 font-mono text-[11px] text-emerald-300">
                <span className="w-2 h-2 rounded-full led-emerald" />
                <span>UP TO DATE</span>
              </div>
            )}
            {updateStatus === 'update_available' && (
              <div className="flex items-center gap-1.5 font-mono text-[11px] text-amber-300 font-bold">
                <span className="w-2 h-2 rounded-full bg-amber-400 shadow-[0_0_8px_rgba(245,158,11,0.9)] animate-pulse" />
                <span>UPDATE AVAILABLE</span>
              </div>
            )}
            {updateStatus === 'error' && (
              <div className="flex items-center gap-1.5 font-mono text-[11px] text-rose-300">
                <span className="w-2 h-2 rounded-full led-rose" />
                <span>ERROR</span>
              </div>
            )}
          </div>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 p-3 rounded-lg bg-slate-950 border border-slate-800 font-mono text-xs">
          <div className="min-w-0">
            <div className="text-slate-400 text-[10px] uppercase tracking-wider">INSTALLED FIRMWARE</div>
            <div className="text-white font-bold text-sm tracking-wide mt-0.5 tabular-nums whitespace-nowrap">
              v1.0.{installedVersion.versionCode}{' '}
              <span className="text-slate-500 text-xs">({installedVersion.versionName})</span>
            </div>
            <div className="text-[10px] text-slate-500 mt-0.5 whitespace-nowrap">
              Last checked: <span className="text-slate-300">{formatLastChecked(lastCheckedTime)}</span>
            </div>
          </div>

          <button
            onClick={() => performUpdateCheck(true)}
            disabled={updateStatus === 'checking' || isDownloadingUpdate}
            className="machine-btn px-3 py-1.5 rounded-lg text-xs font-mono text-accent-soft flex items-center gap-1.5 shrink-0 hover:border-accent whitespace-nowrap"
          >
            {updateStatus === 'checking' ? (
              <Loader2 className="w-3.5 h-3.5 animate-spin text-accent" />
            ) : (
              <RefreshCw className="w-3.5 h-3.5 text-accent" />
            )}
            <span>CHECK FOR UPDATES</span>
          </button>
        </div>

        {updateStatus === 'error' && updateErrorMessage && (
          <div className="p-2.5 rounded-lg bg-rose-950/40 border border-rose-500/40 text-rose-300 font-mono text-xs flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0 mt-0.5" />
            <span>{updateErrorMessage}</span>
          </div>
        )}

        {updateInfo && updateInfo.versionCode > installedVersion.versionCode && Boolean(updateInfo.downloadUrl) && (
          <div className="p-3.5 rounded-xl bg-slate-900/90 border border-amber-500/40 space-y-3 shadow-[0_0_15px_rgba(245,158,11,0.15)]">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2 min-w-0">
                <span className="w-2.5 h-2.5 rounded-full bg-amber-400 shadow-[0_0_8px_rgba(245,158,11,0.9)] animate-pulse shrink-0" />
                <span className="font-mono text-xs font-bold text-white uppercase tracking-wider truncate">
                  NEW VERSION: <span className="text-amber-300">{updateInfo.tagName}</span>
                </span>
              </div>
              <span className="font-mono text-[11px] px-2 py-0.5 rounded bg-slate-950 border border-slate-700 text-accent-soft font-bold tabular-nums whitespace-nowrap shrink-0">
                {updateInfo.sizeBytes > 0 ? `${(updateInfo.sizeBytes / (1024 * 1024)).toFixed(1)} MB` : 'APK'}
              </span>
            </div>

            <div className="p-2.5 rounded-lg bg-slate-950 border border-slate-800 max-h-36 overflow-y-auto font-mono text-[11px] text-slate-300 whitespace-pre-wrap leading-relaxed select-text">
              <div className="text-[10px] text-slate-500 mb-1 tracking-wider uppercase font-bold flex items-center gap-1.5">
                <Sparkles className="w-3 h-3 text-amber-400" />
                <span>CHANGELOG // NOTES:</span>
              </div>
              {updateInfo.releaseNotes}
            </div>

            {isDownloadingUpdate && (
              <div className="space-y-1.5 pt-1">
                <div className="flex items-center justify-between font-mono text-[11px]">
                  <span className="text-accent-soft font-bold flex items-center gap-1.5 whitespace-nowrap">
                    <Loader2 className="w-3 h-3 animate-spin text-accent" />
                    <span>DOWNLOADING FIRMWARE...</span>
                  </span>
                  <span className="text-accent font-bold tabular-nums whitespace-nowrap">
                    {downloadProgress.percent}% <span className="text-slate-400">({downloadProgress.formattedMb})</span>
                  </span>
                </div>
                <div className="grid grid-cols-20 gap-1 h-3.5 bg-slate-950 p-0.5 rounded border border-accent">
                  {Array.from({ length: 20 }).map((_, idx) => {
                    const active = (idx / 20) * 100 < downloadProgress.percent;
                    return (
                      <div
                        key={idx}
                        className={`h-full rounded-sm transition-all duration-100 ${
                          active ? 'bg-accent glow-accent-sm' : 'bg-slate-900 border border-slate-800/80'
                        }`}
                      />
                    );
                  })}
                </div>
              </div>
            )}

            <div className="flex flex-wrap items-center justify-between gap-2 pt-1">
              <button
                onClick={handleStartDownloadUpdate}
                disabled={isDownloadingUpdate}
                className="machine-btn-primary px-4 py-2 rounded-lg font-mono text-xs font-bold text-slate-950 flex items-center justify-center gap-2 flex-1 hover:brightness-110 whitespace-nowrap"
              >
                {isDownloadingUpdate ? (
                  <Loader2 className="w-4 h-4 animate-spin text-slate-950" />
                ) : (
                  <ArrowDownToLine className="w-4 h-4 text-slate-950" />
                )}
                <span>{isDownloadingUpdate ? 'INSTALLING FIRMWARE...' : 'DOWNLOAD & INSTALL'}</span>
              </button>

              {!isDownloadingUpdate && !isSkipped && (
                <button
                  onClick={handleSkipVersion}
                  className="px-3 py-1.5 font-mono text-[11px] text-slate-400 hover:text-amber-400 underline underline-offset-2 transition-colors whitespace-nowrap"
                >
                  Skip this version
                </button>
              )}

              {isSkipped && (
                <span className="font-mono text-[10px] text-slate-500 italic">
                  (Version skipped from auto-notifications)
                </span>
              )}
            </div>
          </div>
        )}

        <div className="pt-1">
          {renderMechanicalToggle(
            'CHECK FOR UPDATES AUTOMATICALLY',
            'Background polling every 4 hours and instant notification when new APK is released',
            autoCheckUpdates,
            () => {
              const next = !autoCheckUpdates;
              setAutoCheckUpdates(next);
              persistSettings({ autoCheckUpdates: next });
            }
          )}
        </div>
      </div>

      {/* 2. SERVER // CONFIG */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3 stagger-card"
        style={{ '--stagger': 2 } as React.CSSProperties}
      >
        <div className="flex items-center gap-2 pb-2 border-b border-accent">
          <Server className="w-4 h-4 text-accent" />
          <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
            SERVER // CONFIG
          </h3>
        </div>

        <div className="space-y-3">
          <div>
            <label className="block font-mono text-xs text-slate-300 uppercase mb-1">
              BACKEND SERVER URL
            </label>
            <input
              type="url"
              value={backendUrl}
              onChange={(e) => setBackendUrl(e.target.value)}
              placeholder="https://your-omni-server.onrender.com"
              className="machine-input w-full px-3 py-2 rounded-lg font-mono text-xs"
            />
            <p className="text-[11px] text-slate-400 mt-1">
              FastAPI download backend address. Leave blank for default local host.
            </p>
          </div>

          <div>
            <label className="block font-mono text-xs text-slate-300 uppercase mb-1">
              API SECURITY KEY (OPTIONAL)
            </label>
            <input
              type="password"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              placeholder="••••••••••••••••"
              className="machine-input w-full px-3 py-2 rounded-lg font-mono text-xs"
            />
            <p className="text-[11px] text-slate-400 mt-1">
              Required only if you configured a private key on your server.
            </p>
          </div>

          <div className="flex items-center justify-between pt-1 gap-2">
            <button
              onClick={handleTestConnection}
              disabled={testStatus === 'testing'}
              className="machine-btn px-3 py-1.5 rounded-lg text-xs font-mono text-accent-soft flex items-center gap-1.5 whitespace-nowrap shrink-0"
            >
              {testStatus === 'testing' ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <RefreshCw className="w-3.5 h-3.5" />
              )}
              <span>TEST LINK</span>
            </button>

            {testStatus === 'success' && (
              <span className="font-mono text-xs text-emerald-400 flex items-center gap-1 tabular-nums whitespace-nowrap">
                <CheckCircle2 className="w-3.5 h-3.5 shrink-0" />
                {testResult}
              </span>
            )}

            {testStatus === 'error' && (
              <span className="font-mono text-xs text-rose-400 flex items-center gap-1 truncate">
                <AlertCircle className="w-3.5 h-3.5 shrink-0" />
                {testResult}
              </span>
            )}
          </div>
        </div>
      </div>

      {/* 3. MEDIA // PREFERENCES */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3 stagger-card"
        style={{ '--stagger': 3 } as React.CSSProperties}
      >
        <div className="flex items-center gap-2 pb-2 border-b border-accent">
          <Sliders className="w-4 h-4 text-accent" />
          <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
            MEDIA // PREFERENCES
          </h3>
        </div>

        <div className="space-y-3">
          <div>
            <label className="block font-mono text-xs text-slate-300 uppercase mb-1">
              DEFAULT VIDEO QUALITY
            </label>
            <select
              value={defaultQuality}
              onChange={(e) => setDefaultQuality(e.target.value)}
              className="machine-input w-full px-3 py-2 rounded-lg font-mono text-xs bg-slate-950"
            >
              <option value="best">Best Available (Highest Resolution)</option>
              <option value="1080p">1080p Full HD</option>
              <option value="720p">720p HD (Balanced Data)</option>
              <option value="480p">480p SD (Data Saver)</option>
              <option value="audio">Audio Only (MP3)</option>
            </select>
          </div>

          <div>
            <label className="block font-mono text-xs text-slate-300 uppercase mb-1">
              AUTO-PLAY REPEAT MODE
            </label>
            <select
              value={repeatMode}
              onChange={(e) => setRepeatMode(e.target.value as any)}
              className="machine-input w-full px-3 py-2 rounded-lg font-mono text-xs bg-slate-950"
            >
              <option value="off">Repeat Off (Advance to next video)</option>
              <option value="one">Repeat One (Loop current video)</option>
              <option value="all">Repeat All (Loop playlist)</option>
            </select>
          </div>

          {renderMechanicalToggle(
            'AUTO-PLAY NEXT VIDEO',
            'Seamlessly start the next video when playback finishes',
            autoPlayNext,
            () => setAutoPlayNext(!autoPlayNext)
          )}

          {renderMechanicalToggle(
            'CLIPBOARD AUTO-DETECTION',
            'Suggest download prompt when supported video links are copied',
            autoDetectClipboard,
            () => setAutoDetectClipboard(!autoDetectClipboard)
          )}

          {renderMechanicalToggle(
            'AUTO-DOWNLOAD ON PASTE',
            'Start instant queue download as soon as paste button is pressed',
            autoDownloadOnPaste,
            () => setAutoDownloadOnPaste(!autoDownloadOnPaste)
          )}
        </div>
      </div>

      {/* 4. FLOATING EDGE TAB // DRAWER */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3 stagger-card"
        style={{ '--stagger': 4 } as React.CSSProperties}
      >
        <div className="flex items-center gap-2 pb-2 border-b border-accent">
          <Layers className="w-4 h-4 text-accent" />
          <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
            FLOATING EDGE TAB // DRAWER
          </h3>
        </div>

        <div className="space-y-3">
          {renderMechanicalToggle(
            'SYSTEM FLOATING TAB',
            'Display hardware edge tab across all Android apps for instant downloads',
            isBubbleActive,
            handleToggleBubble
          )}

          {renderMechanicalToggle(
            'ANCHOR SCREEN EDGE',
            `Currently positioned on the ${floatingSide.toUpperCase()} edge`,
            floatingSide === 'left',
            () => setFloatingSide(floatingSide === 'left' ? 'right' : 'left')
          )}

          {renderMechanicalToggle(
            'SHOW DUAL PING READOUT',
            'Display real-time server and network latency in milliseconds',
            floatingShowMs,
            () => setFloatingShowMs(!floatingShowMs)
          )}

          {renderMechanicalToggle(
            'AUTO-CLEAR AFTER DOWNLOAD',
            'Automatically clear the link field and progress bar 4s after completion',
            floatingAutoClear,
            () => setFloatingAutoClear(!floatingAutoClear)
          )}

          {renderMechanicalToggle(
            'HAPTIC MOTOR FEEDBACK',
            'Vibrate short pulse on successful download and double pulse on failure',
            floatingVibrate,
            () => setFloatingVibrate(!floatingVibrate)
          )}

          {renderMechanicalToggle(
            'HIDE FLOATING TAB INSIDE OMNIDROP',
            'Automatically hide edge tab and drawer while using OmniDrop or media player',
            floatingHideInsideApp,
            () => setFloatingHideInsideApp(!floatingHideInsideApp)
          )}

          <div className="p-3 rounded-lg bg-slate-950 border border-slate-800 flex items-center justify-between gap-2">
            <div className="min-w-0 pr-2">
              <div className="font-mono text-xs text-white uppercase font-bold flex items-center gap-2 whitespace-nowrap">
                <span
                  className={`w-2.5 h-2.5 rounded-full shrink-0 ${
                    tabServiceStatus === 'RUNNING'
                      ? 'led-emerald'
                      : tabServiceStatus === 'NO PERMISSION'
                      ? 'led-amber'
                      : 'bg-slate-700'
                  }`}
                />
                <span>TAB SERVICE: {tabServiceStatus}</span>
              </div>
              <div className="text-[11px] text-slate-400 mt-0.5">
                {tabServiceStatus === 'RUNNING'
                  ? 'Edge tab active • Appears when leaving OmniDrop'
                  : tabServiceStatus === 'NO PERMISSION'
                  ? 'Display over other apps permission required'
                  : 'Tab service stopped • Toggle ON above to start'}
              </div>
            </div>

            <div className="flex items-center gap-2 shrink-0">
              {tabServiceStatus === 'NO PERMISSION' ? (
                <button
                  onClick={handleGrantOverlay}
                  className="machine-btn px-2.5 py-1 rounded text-[11px] font-mono text-accent-soft font-bold whitespace-nowrap"
                >
                  GRANT ACCESS
                </button>
              ) : (
                <button
                  onClick={handleTestTabNow}
                  disabled={isTestingTab || tabServiceStatus !== 'RUNNING'}
                  className={`machine-btn px-3 py-1.5 rounded-lg text-xs font-mono font-bold transition-all duration-200 whitespace-nowrap ${
                    isTestingTab
                      ? 'machine-btn-primary text-slate-950 glow-accent'
                      : tabServiceStatus === 'RUNNING'
                      ? 'text-accent-soft border-accent hover:border-accent-strong'
                      : 'text-slate-600 border-slate-800 cursor-not-allowed opacity-50'
                  }`}
                >
                  {isTestingTab ? 'TESTING (5s)...' : 'TEST TAB NOW'}
                </button>
              )}
            </div>
          </div>

          <div className="p-3 rounded-lg bg-slate-950 border border-slate-800 flex items-center justify-between gap-2">
            <div className="min-w-0 pr-2">
              <div className="font-mono text-xs text-white uppercase font-bold flex items-center gap-2 whitespace-nowrap">
                <Sliders className="w-3.5 h-3.5 text-accent shrink-0" />
                <span>QUICK SETTINGS TILE</span>
              </div>
              <div className="text-[11px] text-slate-400 mt-0.5">
                Toggle the OmniDrop edge tab directly from Android notification bar
              </div>
            </div>

            <button
              onClick={handleAddToQuickSettings}
              disabled={isAddingTile}
              className="machine-btn px-3 py-1.5 rounded-lg text-xs font-mono font-bold text-accent-soft border-accent hover:border-accent-strong shrink-0 whitespace-nowrap"
            >
              {isAddingTile ? 'ADDING...' : 'ADD TO QUICK SETTINGS'}
            </button>
          </div>
        </div>
      </div>

      {/* 4B. SERVER KEEP-ALIVE (READ-ONLY CARD TIED TO FLOATING TAB) */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3 stagger-card"
        style={{ '--stagger': 5 } as React.CSSProperties}
      >
        <div className="flex items-center justify-between gap-2 pb-2 border-b border-accent">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <Activity className="w-4 h-4 text-accent shrink-0" />
              <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider truncate">
                SERVER KEEP-ALIVE
              </h3>
            </div>
            <p className="text-[11px] text-slate-400 mt-0.5">
              Automatic while the floating tab is ON
            </p>
          </div>

          <div className="flex items-center gap-1.5 px-2.5 py-1 rounded bg-slate-950 border border-slate-800 font-mono text-[11px] font-bold shrink-0 whitespace-nowrap">
            <span
              className={`w-2 h-2 rounded-full ${
                keepAliveStatus.isRunning ? 'led-emerald animate-pulse' : 'bg-slate-600'
              }`}
            />
            <span className={keepAliveStatus.isRunning ? 'text-emerald-400' : 'text-slate-400'}>
              {keepAliveStatus.isRunning ? 'RUNNING' : 'STOPPED'}
            </span>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-2 font-mono text-xs">
          <div className="p-2.5 rounded-lg bg-slate-950 border border-slate-800">
            <div className="text-[10px] text-slate-500 uppercase">LAST PING</div>
            <div className="text-white font-bold mt-0.5 truncate tabular-nums">
              {keepAliveStatus.lastPingStatus === 'WAKING'
                ? 'Waking...'
                : keepAliveStatus.lastPingTime > 0
                ? `${formatKeepAliveClock(keepAliveStatus.lastPingTime)} · ${
                    keepAliveStatus.lastPingStatus === 'OK' && keepAliveStatus.lastPingMs >= 0
                      ? `${keepAliveStatus.lastPingMs}ms`
                      : keepAliveStatus.lastPingStatus === 'SLEEP'
                      ? 'Sleeping'
                      : keepAliveStatus.lastPingStatus === 'OFFLINE'
                      ? 'Offline'
                      : 'Failed'
                  }`
                : 'Never'}
            </div>
          </div>

          <div className="p-2.5 rounded-lg bg-slate-950 border border-slate-800">
            <div className="text-[10px] text-slate-500 uppercase">NEXT PING</div>
            <div className="text-accent-soft font-bold mt-0.5 truncate tabular-nums">
              {keepAliveStatus.isRunning && keepAliveStatus.nextPingTime > 0
                ? formatKeepAliveClock(keepAliveStatus.nextPingTime)
                : '—'}
            </div>
          </div>

          <div className="p-2.5 rounded-lg bg-slate-950 border border-slate-800">
            <div className="text-[10px] text-slate-500 uppercase">PINGS TODAY</div>
            <div className="text-white font-bold mt-0.5 tabular-nums">{keepAliveStatus.pingsToday}</div>
          </div>

          <div className="p-2.5 rounded-lg bg-slate-950 border border-slate-800">
            <div className="text-[10px] text-slate-500 uppercase">EST. RENDER HOURS</div>
            <div className="text-emerald-300 font-bold mt-0.5 tabular-nums whitespace-nowrap">
              {keepAliveStatus.estimatedHoursThisMonth.toFixed(1)}h{' '}
              <span className="text-[10px] text-slate-500 font-normal">this month</span>
            </div>
          </div>
        </div>
      </div>

      {/* 5. STORAGE // CACHE CONTROL */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3 stagger-card"
        style={{ '--stagger': 6 } as React.CSSProperties}
      >
        <div className="flex items-center gap-2 pb-2 border-b border-accent">
          <HardDrive className="w-4 h-4 text-accent" />
          <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
            STORAGE // CACHE CONTROL
          </h3>
        </div>

        <div className="space-y-3">
          <div className="flex items-center justify-between gap-2 p-3 rounded-lg bg-slate-950 border border-slate-800 font-mono text-xs">
            <span className="text-slate-400 truncate">TOTAL GALLERY USAGE:</span>
            <span className="text-accent-soft font-bold tabular-nums whitespace-nowrap shrink-0">
              {librarySizeMb > 1024
                ? `${(librarySizeMb / 1024).toFixed(2)} GB`
                : `${librarySizeMb} MB`}{' '}
              <span className="text-slate-500">({libraryCount} videos)</span>
            </span>
          </div>

          <div className="flex items-center justify-between gap-2">
            <div className="min-w-0 pr-2">
              <div className="font-mono text-xs text-white uppercase font-bold">
                METADATA & THUMBNAILS
              </div>
              <div className="text-[11px] text-slate-400">
                Resets cached titles and base64 video previews
              </div>
            </div>

            <button
              onClick={handleClearMetadataCache}
              disabled={isClearingCache}
              className="machine-btn px-3 py-1.5 rounded-lg text-xs font-mono text-rose-300 flex items-center gap-1.5 shrink-0 whitespace-nowrap"
            >
              {isClearingCache ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Trash2 className="w-3.5 h-3.5 text-rose-400" />
              )}
              <span>CLEAR CACHE</span>
            </button>
          </div>

          {cacheClearMsg && (
            <div className="p-2.5 rounded-lg bg-emerald-950/50 border border-emerald-500/40 text-emerald-300 font-mono text-xs">
              {cacheClearMsg}
            </div>
          )}
        </div>
      </div>

      {/* Styled Dark Cybernetic Modal */}
      {modalState.isOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in">
          <div className="w-full max-w-sm rounded-2xl bg-[#090D15] border border-accent-strong p-5 glow-accent space-y-4">
            <div className="flex items-center justify-between pb-2 border-b border-accent">
              <div className="flex items-center gap-2 min-w-0">
                <span className={`w-2 h-2 rounded-full shrink-0 ${modalState.isError ? 'led-rose' : 'led-accent'}`} />
                <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider truncate">
                  {modalState.title}
                </h3>
              </div>
              <button
                onClick={() => setModalState((prev) => ({ ...prev, isOpen: false }))}
                className="p-1 text-slate-400 hover:text-white shrink-0"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <p className="font-mono text-xs text-slate-300 leading-relaxed select-text whitespace-pre-line">
              {modalState.message}
            </p>

            <div className="flex items-center justify-end gap-2 pt-2">
              {modalState.cancelLabel && (
                <button
                  onClick={() => {
                    if (modalState.onCancel) modalState.onCancel();
                    else setModalState((prev) => ({ ...prev, isOpen: false }));
                  }}
                  className="px-3.5 py-1.5 rounded-lg font-mono text-xs text-slate-400 hover:text-white border border-slate-700 bg-slate-900"
                >
                  {modalState.cancelLabel}
                </button>
              )}
              <button
                onClick={() => {
                  if (modalState.onAction) modalState.onAction();
                  else setModalState((prev) => ({ ...prev, isOpen: false }));
                }}
                className={`px-4 py-1.5 rounded-lg font-mono text-xs font-bold transition-all ${
                  modalState.isError
                    ? 'bg-rose-600 text-white shadow-[0_0_8px_rgba(239,68,68,0.6)]'
                    : 'machine-btn-primary text-slate-950 glow-accent-sm'
                }`}
              >
                {modalState.actionLabel || 'OK'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
