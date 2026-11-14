import React, { useState, useEffect, useCallback, useRef } from 'react';
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
  ShieldCheck,
  Lock,
  Film,
  Download,
  Compass,
} from 'lucide-react';
import {
  AccentTheme,
  AppSettings,
  AppUpdateInfo,
  FileNameStyle,
  FloatingOpacity,
  FloatingSize,
  MobileDataPromptMb,
  UpdateProgressEvent,
} from '../types';
import { checkServerHealth } from '../services/api';
import { VideoDownloader, SystemChecksResult, StorageUsageResult } from '../plugins/videoDownloader';
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
  onOpenSetupGuide?: () => void;
}

const ACCENT_OPTIONS: Array<{ id: AccentTheme; label: string; dotColor: string; dotGlow: string }> = [
  { id: 'cyan', label: 'CYAN', dotColor: '#00f0ff', dotGlow: '0 0 8px #00f0ff' },
  { id: 'purple', label: 'PURPLE', dotColor: '#a855f7', dotGlow: '0 0 8px #a855f7' },
  { id: 'green', label: 'GREEN', dotColor: '#10b981', dotGlow: '0 0 8px #10b981' },
  { id: 'amber', label: 'AMBER', dotColor: '#f59e0b', dotGlow: '0 0 8px #f59e0b' },
  { id: 'red', label: 'RED', dotColor: '#f43f5e', dotGlow: '0 0 8px #f43f5e' },
];

const MOBILE_LIMIT_OPTIONS: Array<{ id: MobileDataPromptMb; label: string }> = [
  { id: 'off', label: 'OFF' },
  { id: 50, label: '50 MB' },
  { id: 100, label: '100 MB' },
  { id: 200, label: '200 MB' },
];

const FILE_NAME_STYLE_OPTIONS: Array<{ id: FileNameStyle; label: string }> = [
  { id: 'title_platform', label: 'Title [Platform] (Default)' },
  { id: 'platform_title_date', label: 'Platform - Title - Date' },
  { id: 'title_only', label: 'Title only' },
];

const PLAYER_SPEED_OPTIONS: Array<{ value: 0.5 | 1 | 1.5 | 2; label: string }> = [
  { value: 0.5, label: '0.5x' },
  { value: 1, label: '1x' },
  { value: 1.5, label: '1.5x' },
  { value: 2, label: '2x' },
];

const FLOATING_SIZE_OPTIONS: Array<{ id: FloatingSize; label: string }> = [
  { id: 'small', label: 'SMALL' },
  { id: 'medium', label: 'MEDIUM' },
  { id: 'large', label: 'LARGE' },
];

const FLOATING_OPACITY_OPTIONS: Array<{ value: FloatingOpacity; label: string }> = [
  { value: 30, label: '30%' },
  { value: 45, label: '45%' },
  { value: 70, label: '70%' },
  { value: 100, label: '100%' },
];

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 MB';
  const mb = bytes / (1024 * 1024);
  if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`;
  if (mb >= 1) return `${mb.toFixed(1)} MB`;
  const kb = bytes / 1024;
  return `${kb.toFixed(0)} KB`;
}

export const SettingsScreen: React.FC<SettingsScreenProps> = ({
  settings,
  onUpdateSettings,
  onRefreshHealth,
  onUpdateAvailableChange,
  onOpenSetupGuide,
}) => {
  const isNative = Capacitor.isNativePlatform();
  const serverConfigRef = useRef<HTMLDivElement | null>(null);

  // System Check Diagnostics state
  const [serverReachable, setServerReachable] = useState<'checking' | 'online' | 'offline'>('checking');
  const [serverPingMs, setServerPingMs] = useState<number | null>(null);
  const [systemChecks, setSystemChecks] = useState<SystemChecksResult>({
    overlayGranted: false,
    notificationsGranted: false,
    mediaGranted: false,
    installUpdatesGranted: false,
    batteryUnrestricted: false,
    floatingServiceRunning: false,
  });
  const [isRunningChecks, setIsRunningChecks] = useState<boolean>(false);

  // Appearance state
  const [accentColor, setAccentColor] = useState<AccentTheme>(settings.accentColor || 'cyan');
  const [reduceAnimations, setReduceAnimations] = useState<boolean>(Boolean(settings.reduceAnimations));

  // Server state
  const [backendUrl, setBackendUrl] = useState(settings.backendUrl || '');
  const [apiKey, setApiKey] = useState(settings.apiKey || '');

  // Downloads state
  const [defaultQuality, setDefaultQuality] = useState(settings.defaultQuality || 'best');
  const [dataSaver, setDataSaver] = useState<boolean>(Boolean(settings.dataSaver));
  const [mobileDataPromptMb, setMobileDataPromptMb] = useState<MobileDataPromptMb>(settings.mobileDataPromptMb ?? 'off');
  const [fallbackToBestQuality, setFallbackToBestQuality] = useState<boolean>(settings.fallbackToBestQuality ?? true);
  const [fileNameStyle, setFileNameStyle] = useState<FileNameStyle>(settings.fileNameStyle || 'title_platform');
  const [notifyDownloadFinish, setNotifyDownloadFinish] = useState<boolean>(settings.notifyDownloadFinish ?? true);
  const [notifyDownloadFail, setNotifyDownloadFail] = useState<boolean>(settings.notifyDownloadFail ?? true);
  const [autoDetectClipboard, setAutoDetectClipboard] = useState(settings.autoDetectClipboard ?? true);
  const [autoDownloadOnPaste, setAutoDownloadOnPaste] = useState(settings.autoDownloadOnPaste ?? false);

  // Player state
  const [playerDefaultSpeed, setPlayerDefaultSpeed] = useState<0.5 | 1 | 1.5 | 2>(settings.playerDefaultSpeed ?? 1);
  const [playerDefaultFitMode, setPlayerDefaultFitMode] = useState<'fit' | 'fill'>(settings.playerDefaultFitMode || 'fit');
  const [playerResumePlayback, setPlayerResumePlayback] = useState<boolean>(settings.playerResumePlayback ?? true);
  const [playerKeepScreenOn, setPlayerKeepScreenOn] = useState<boolean>(settings.playerKeepScreenOn ?? true);
  const [playerAutoPip, setPlayerAutoPip] = useState<boolean>(settings.playerAutoPip ?? true);
  const [playerGesturesEnabled, setPlayerGesturesEnabled] = useState<boolean>(settings.playerGesturesEnabled ?? true);
  const [autoPlayNext, setAutoPlayNext] = useState(settings.autoPlayNext ?? true);
  const [repeatMode, setRepeatMode] = useState<'off' | 'one' | 'all'>(settings.repeatMode || 'off');

  // Floating tab settings
  const [isBubbleActive, setIsBubbleActive] = useState(settings.floatingBubbleEnabled || false);
  const [floatingSide, setFloatingSide] = useState<'left' | 'right'>(settings.floatingSide || 'right');
  const [floatingSize, setFloatingSize] = useState<FloatingSize>(settings.floatingSize || 'medium');
  const [floatingOpacity, setFloatingOpacity] = useState<FloatingOpacity>(settings.floatingOpacity ?? 45);
  const [floatingShowMs, setFloatingShowMs] = useState(settings.floatingShowMs ?? true);
  const [floatingAutoClear, setFloatingAutoClear] = useState(settings.floatingAutoClear ?? true);
  const [floatingVibrate, setFloatingVibrate] = useState(settings.floatingVibrate ?? true);
  const [floatingHideInsideApp, setFloatingHideInsideApp] = useState(settings.floatingHideInsideApp ?? true);
  const [tabServiceStatus, setTabServiceStatus] = useState<'RUNNING' | 'STOPPED' | 'NO PERMISSION'>('STOPPED');
  const [isTestingTab, setIsTestingTab] = useState<boolean>(false);
  const [isAddingTile, setIsAddingTile] = useState<boolean>(false);

  // Privacy state
  const [hideInRecents, setHideInRecents] = useState<boolean>(Boolean(settings.hideInRecents));

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

  // Storage usage breakdown state
  const [storageUsage, setStorageUsage] = useState<StorageUsageResult>({
    videoBytes: 0,
    videoCount: 0,
    photoBytes: 0,
    photoCount: 0,
    audioBytes: 0,
    audioCount: 0,
    totalBytes: 0,
  });
  const [isClearingCache, setIsClearingCache] = useState(false);
  const [cacheClearMsg, setCacheClearMsg] = useState<string | null>(null);

  const runAllSystemChecks = useCallback(async (checkServerAlso: boolean = true) => {
    setIsRunningChecks(true);
    try {
      const checksPromise = VideoDownloader.getSystemChecks().catch(() => null);
      const healthPromise = checkServerAlso
        ? (() => {
            setServerReachable('checking');
            return checkServerHealth({
              ...settings,
              backendUrl: backendUrl.trim(),
              apiKey: apiKey.trim(),
            });
          })()
        : Promise.resolve(null);

      const [nativeRes, healthRes] = await Promise.all([checksPromise, healthPromise]);
      if (nativeRes) {
        setSystemChecks(nativeRes);
        if (!nativeRes.overlayGranted) {
          setTabServiceStatus('NO PERMISSION');
        } else {
          setIsBubbleActive(nativeRes.floatingServiceRunning);
          setTabServiceStatus(nativeRes.floatingServiceRunning ? 'RUNNING' : 'STOPPED');
        }
      }
      if (healthRes) {
        if (healthRes.ok) {
          setServerReachable('online');
          setServerPingMs(healthRes.latencyMs);
        } else {
          setServerReachable('offline');
          setServerPingMs(null);
        }
      }
    } finally {
      setIsRunningChecks(false);
    }
  }, [settings, backendUrl, apiKey]);

  const refreshKeepAliveStatus = useCallback(async () => {
    try {
      const res = await VideoDownloader.getKeepAliveStatus();
      if (res) {
        setKeepAliveStatus(res);
      }
    } catch {}
  }, []);

  const refreshTabServiceStatus = useCallback(async () => {
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
  }, []);

  const loadStorageStats = useCallback(async () => {
    try {
      const usage = await VideoDownloader.getStorageUsage();
      if (usage && typeof usage.totalBytes === 'number') {
        setStorageUsage(usage);
      }
    } catch {}
  }, []);

  useEffect(() => {
    runAllSystemChecks(true);
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
      runAllSystemChecks(true);
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
      dataSaver,
      mobileDataPromptMb,
      fallbackToBestQuality,
      fileNameStyle,
      notifyDownloadFinish,
      notifyDownloadFail,
      playerDefaultSpeed,
      playerDefaultFitMode,
      playerResumePlayback,
      playerKeepScreenOn,
      playerAutoPip,
      playerGesturesEnabled,
      autoDetectClipboard,
      autoDownloadOnPaste,
      autoPlayNext,
      repeatMode,
      floatingBubbleEnabled: isBubbleActive,
      floatingSide,
      floatingSize,
      floatingOpacity,
      floatingShowMs,
      floatingAutoClear,
      floatingVibrate,
      floatingHideInsideApp,
      hideInRecents,
      autoCheckUpdates,
      accentColor,
      reduceAnimations,
      ...overrides,
    };
    onUpdateSettings(updated);
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
    runAllSystemChecks(true);
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
    persistSettings();
    const tempSettings: AppSettings = {
      ...settings,
      backendUrl: backendUrl.trim(),
      apiKey: apiKey.trim(),
    };
    const res = await checkServerHealth(tempSettings);
    if (res.ok) {
      setTestStatus('success');
      setTestResult(`ONLINE • ${res.latencyMs}ms`);
      setServerReachable('online');
      setServerPingMs(res.latencyMs);
    } else {
      setTestStatus('error');
      setTestResult(`FAILED • ${res.error || 'Server unreachable'}`);
      setServerReachable('offline');
      setServerPingMs(null);
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
          persistSettings({ floatingBubbleEnabled: true });

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
        persistSettings({ floatingBubbleEnabled: false });
      }
      refreshKeepAliveStatus();
      runAllSystemChecks(false);
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
      setTimeout(() => {
        refreshTabServiceStatus();
        runAllSystemChecks(false);
      }, 1500);
    } catch {}
  };

  const handleFixBattery = async () => {
    try {
      await VideoDownloader.requestBatteryUnrestricted();
    } catch {}
    setModalState({
      isOpen: true,
      title: 'BATTERY OPTIMIZATION',
      message: "Choose OmniDrop and set it to Unrestricted/Don't optimize.",
      actionLabel: 'GOT IT',
      onAction: () => {
        setModalState((prev) => ({ ...prev, isOpen: false }));
        runAllSystemChecks(false);
      },
    });
  };

  const handleClearMetadataCache = () => {
    setModalState({
      isOpen: true,
      title: 'CLEAR THUMBNAIL CACHE',
      message: 'Clear cached titles and preview thumbnails? Downloaded videos, photos, and audio will NOT be deleted.',
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
          setCacheClearMsg('Thumbnail & metadata cache cleared successfully!');
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

  const renderDiagnosticRow = (
    label: string,
    statusText: string,
    ok: boolean,
    isPending: boolean,
    onFix?: () => void,
    fixLabel: string = 'FIX'
  ) => (
    <div className="p-2.5 rounded-lg bg-slate-950 border border-slate-800 flex items-center justify-between gap-2">
      <div className="flex items-center gap-2.5 min-w-0">
        <span
          className={`w-2.5 h-2.5 rounded-full shrink-0 ${
            isPending
              ? 'led-amber animate-pulse'
              : ok
              ? 'led-emerald'
              : 'led-rose'
          }`}
        />
        <div className="min-w-0">
          <div className="font-mono text-xs font-bold text-white truncate">{label}</div>
          <div
            className={`font-mono text-[10px] truncate ${
              isPending ? 'text-amber-300' : ok ? 'text-emerald-400' : 'text-rose-400'
            }`}
          >
            {statusText}
          </div>
        </div>
      </div>
      {!ok && onFix && (
        <button
          onClick={onFix}
          className="machine-btn px-2.5 py-1 rounded text-[11px] font-mono font-bold text-accent-soft border-accent hover:border-accent-strong shrink-0 whitespace-nowrap"
        >
          {fixLabel}
        </button>
      )}
    </div>
  );

  const diagnosticPassedCount = [
    serverReachable === 'online',
    systemChecks.overlayGranted,
    systemChecks.notificationsGranted,
    systemChecks.mediaGranted,
    systemChecks.installUpdatesGranted,
    systemChecks.batteryUnrestricted,
    systemChecks.floatingServiceRunning,
  ].filter(Boolean).length;

  return (
    <div className="space-y-4 pb-28">
      {/* Top Header & Mechanical Save Key */}
      <div className="flex items-center justify-between pt-1 gap-2">
        <div className="min-w-0">
          <h2 className="text-xs font-mono font-bold text-accent uppercase tracking-widest flex items-center gap-2 whitespace-nowrap">
            <span className="w-2 h-2 rounded-full led-accent shrink-0" />
            SETTINGS // CONTROL
          </h2>
          <p className="text-xs text-slate-400 mt-0.5 truncate">Hardware preferences &amp; diagnostics</p>
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

      {/* 0. SYSTEM CHECK // DIAGNOSTICS */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3 border border-accent stagger-card"
        style={{ '--stagger': 0 } as React.CSSProperties}
      >
        <div className="flex items-center justify-between gap-2 pb-2 border-b border-accent">
          <div className="flex items-center gap-2 min-w-0">
            <ShieldCheck className="w-4 h-4 text-accent shrink-0" />
            <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider truncate">
              SYSTEM CHECK // DIAGNOSTICS
            </h3>
          </div>
          <span className="font-mono text-[10px] font-bold text-accent-soft tabular-nums whitespace-nowrap">
            {diagnosticPassedCount}/7 READY
          </span>
        </div>

        <div className="space-y-2">
          {renderDiagnosticRow(
            'Server reachable',
            serverReachable === 'checking'
              ? 'PINGING BACKEND...'
              : serverReachable === 'online'
              ? `ONLINE${serverPingMs != null ? ` · ${serverPingMs}ms` : ''}`
              : backendUrl.trim()
              ? 'UNREACHABLE · CHECK URL OR SERVER'
              : 'NO SERVER URL CONFIGURED',
            serverReachable === 'online',
            serverReachable === 'checking',
            () => serverConfigRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })
          )}

          {renderDiagnosticRow(
            'Display over other apps',
            systemChecks.overlayGranted ? 'GRANTED' : isNative ? 'REQUIRED FOR FLOATING TAB' : 'ANDROID DEVICE ONLY',
            systemChecks.overlayGranted,
            false,
            () => VideoDownloader.requestOverlayPermission().catch(() => {})
          )}

          {renderDiagnosticRow(
            'Notifications',
            systemChecks.notificationsGranted ? 'GRANTED' : 'DISABLED · TAP FIX TO ALLOW',
            systemChecks.notificationsGranted,
            false,
            () => VideoDownloader.requestNotificationPermission().then(() => setTimeout(() => runAllSystemChecks(false), 800)).catch(() => {})
          )}

          {renderDiagnosticRow(
            'Media access',
            systemChecks.mediaGranted ? 'GRANTED' : 'REQUIRED FOR VIDEOS & PHOTOS',
            systemChecks.mediaGranted,
            false,
            () => VideoDownloader.requestVideoPermission().then(() => setTimeout(() => runAllSystemChecks(false), 800)).catch(() => {})
          )}

          {renderDiagnosticRow(
            'Install updates',
            systemChecks.installUpdatesGranted ? 'ALLOWED' : isNative ? 'ALLOW UNKNOWN APPS REQUIRED' : 'ANDROID DEVICE ONLY',
            systemChecks.installUpdatesGranted,
            false,
            () => VideoDownloader.requestInstallPermission().catch(() => {})
          )}

          {renderDiagnosticRow(
            'Battery unrestricted',
            systemChecks.batteryUnrestricted ? 'UNRESTRICTED' : 'RESTRICTED · MAY STOP BACKGROUND DL',
            systemChecks.batteryUnrestricted,
            false,
            handleFixBattery
          )}

          {renderDiagnosticRow(
            'Floating tab service',
            systemChecks.floatingServiceRunning ? 'RUNNING' : 'STOPPED',
            systemChecks.floatingServiceRunning,
            false,
            handleToggleBubble,
            systemChecks.overlayGranted ? 'START' : 'FIX'
          )}
        </div>

        <div className="flex items-center gap-2 pt-1">
          <button
            onClick={() => runAllSystemChecks(true)}
            disabled={isRunningChecks}
            className="machine-btn-primary flex-1 py-2 rounded-lg font-mono text-xs font-bold flex items-center justify-center gap-1.5 whitespace-nowrap"
          >
            {isRunningChecks ? (
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
            ) : (
              <RefreshCw className="w-3.5 h-3.5" />
            )}
            <span>RUN ALL CHECKS</span>
          </button>

          {onOpenSetupGuide && (
            <button
              onClick={onOpenSetupGuide}
              className="machine-btn px-3 py-2 rounded-lg font-mono text-xs font-bold text-accent-soft flex items-center gap-1.5 shrink-0 whitespace-nowrap"
            >
              <Compass className="w-3.5 h-3.5 text-accent" />
              <span>SETUP GUIDE</span>
            </button>
          )}
        </div>
      </div>

      {/* 1. DOWNLOADS // ENGINE */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3 stagger-card"
        style={{ '--stagger': 1 } as React.CSSProperties}
      >
        <div className="flex items-center gap-2 pb-2 border-b border-accent">
          <Download className="w-4 h-4 text-accent" />
          <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
            DOWNLOADS // ENGINE
          </h3>
        </div>

        <div className="space-y-3">
          <div>
            <label className="block font-mono text-xs text-slate-300 uppercase mb-1">
              DEFAULT VIDEO QUALITY
            </label>
            <select
              value={defaultQuality}
              onChange={(e) => {
                const v = e.target.value;
                setDefaultQuality(v);
                persistSettings({ defaultQuality: v });
              }}
              className="machine-input w-full px-3 py-2 rounded-lg font-mono text-xs bg-slate-950"
            >
              <option value="best">Best Available (Highest Resolution)</option>
              <option value="1080p">1080p Full HD</option>
              <option value="720p">720p HD (Balanced Data)</option>
              <option value="480p">480p SD (Data Saver)</option>
              <option value="audio">Audio Only (MP3)</option>
            </select>
          </div>

          {renderMechanicalToggle(
            'DATA SAVER',
            'Limit downloads to 720p on mobile data; use default quality on Wi-Fi',
            dataSaver,
            () => {
              const next = !dataSaver;
              setDataSaver(next);
              persistSettings({ dataSaver: next });
            }
          )}

          <div className="py-1 border-b border-slate-800/60 pb-3">
            <div className="font-mono text-xs font-bold text-white uppercase mb-1">
              ASK BEFORE MOBILE DATA IF LARGER THAN
            </div>
            <div className="text-[11px] text-slate-400 font-sans mb-2">
              Prompt before downloading large files over cellular data (when size is known)
            </div>
            <div className="grid grid-cols-4 gap-1 p-1 bg-slate-950 rounded-lg border border-slate-800">
              {MOBILE_LIMIT_OPTIONS.map((opt) => {
                const isSelected = String(mobileDataPromptMb) === String(opt.id);
                return (
                  <button
                    key={String(opt.id)}
                    onClick={() => {
                      setMobileDataPromptMb(opt.id);
                      persistSettings({ mobileDataPromptMb: opt.id });
                    }}
                    className={`py-1.5 rounded text-center font-mono text-[11px] font-bold transition-all whitespace-nowrap tabular-nums ${
                      isSelected
                        ? 'machine-btn-primary text-slate-950 glow-accent-sm'
                        : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {opt.label}
                  </button>
                );
              })}
            </div>
          </div>

          {renderMechanicalToggle(
            'IF CHOSEN QUALITY UNAVAILABLE, USE BEST',
            'Automatically fall back to the best stream when requested resolution fails',
            fallbackToBestQuality,
            () => {
              const next = !fallbackToBestQuality;
              setFallbackToBestQuality(next);
              persistSettings({ fallbackToBestQuality: next });
            }
          )}

          <div>
            <label className="block font-mono text-xs text-slate-300 uppercase mb-1">
              FILE NAME STYLE
            </label>
            <select
              value={fileNameStyle}
              onChange={(e) => {
                const v = e.target.value as FileNameStyle;
                setFileNameStyle(v);
                persistSettings({ fileNameStyle: v });
              }}
              className="machine-input w-full px-3 py-2 rounded-lg font-mono text-xs bg-slate-950"
            >
              {FILE_NAME_STYLE_OPTIONS.map((opt) => (
                <option key={opt.id} value={opt.id}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>

          {renderMechanicalToggle(
            'NOTIFY WHEN A DOWNLOAD FINISHES',
            'Show completion notification when media finishes saving',
            notifyDownloadFinish,
            () => {
              const next = !notifyDownloadFinish;
              setNotifyDownloadFinish(next);
              persistSettings({ notifyDownloadFinish: next });
            }
          )}

          {renderMechanicalToggle(
            'NOTIFY WHEN A DOWNLOAD FAILS',
            'Show error alert notification if a download fails',
            notifyDownloadFail,
            () => {
              const next = !notifyDownloadFail;
              setNotifyDownloadFail(next);
              persistSettings({ notifyDownloadFail: next });
            }
          )}

          {renderMechanicalToggle(
            'CLIPBOARD AUTO-DETECTION',
            'Suggest download prompt when supported video links are copied',
            autoDetectClipboard,
            () => {
              const next = !autoDetectClipboard;
              setAutoDetectClipboard(next);
              persistSettings({ autoDetectClipboard: next });
            }
          )}

          {renderMechanicalToggle(
            'AUTO-DOWNLOAD ON PASTE',
            'Start instant queue download as soon as paste button is pressed',
            autoDownloadOnPaste,
            () => {
              const next = !autoDownloadOnPaste;
              setAutoDownloadOnPaste(next);
              persistSettings({ autoDownloadOnPaste: next });
            }
          )}
        </div>
      </div>

      {/* 2. PLAYER // HARDWARE */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3 stagger-card"
        style={{ '--stagger': 2 } as React.CSSProperties}
      >
        <div className="flex items-center gap-2 pb-2 border-b border-accent">
          <Film className="w-4 h-4 text-accent" />
          <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
            PLAYER // HARDWARE
          </h3>
        </div>

        <div className="space-y-3">
          <div>
            <div className="text-[10px] font-mono text-slate-400 uppercase tracking-wider mb-1.5">
              DEFAULT PLAYBACK SPEED
            </div>
            <div className="grid grid-cols-4 gap-1 p-1 bg-slate-950 rounded-lg border border-slate-800">
              {PLAYER_SPEED_OPTIONS.map((opt) => {
                const isSelected = playerDefaultSpeed === opt.value;
                return (
                  <button
                    key={opt.value}
                    onClick={() => {
                      setPlayerDefaultSpeed(opt.value);
                      persistSettings({ playerDefaultSpeed: opt.value });
                    }}
                    className={`py-1.5 rounded text-center font-mono text-xs font-bold transition-all whitespace-nowrap tabular-nums ${
                      isSelected
                        ? 'machine-btn-primary text-slate-950 glow-accent-sm'
                        : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {opt.label}
                  </button>
                );
              })}
            </div>
          </div>

          <div>
            <div className="text-[10px] font-mono text-slate-400 uppercase tracking-wider mb-1.5">
              DEFAULT VIDEO SCALING
            </div>
            <div className="grid grid-cols-2 gap-1 p-1 bg-slate-950 rounded-lg border border-slate-800">
              {(['fit', 'fill'] as const).map((mode) => {
                const isSelected = playerDefaultFitMode === mode;
                return (
                  <button
                    key={mode}
                    onClick={() => {
                      setPlayerDefaultFitMode(mode);
                      persistSettings({ playerDefaultFitMode: mode });
                    }}
                    className={`py-1.5 rounded text-center font-mono text-xs font-bold uppercase transition-all whitespace-nowrap ${
                      isSelected
                        ? 'machine-btn-primary text-slate-950 glow-accent-sm'
                        : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {mode.toUpperCase()}
                  </button>
                );
              })}
            </div>
          </div>

          {renderMechanicalToggle(
            'RESUME WHERE I STOPPED',
            'Automatically seek back to your last saved timestamp',
            playerResumePlayback,
            () => {
              const next = !playerResumePlayback;
              setPlayerResumePlayback(next);
              persistSettings({ playerResumePlayback: next });
            }
          )}

          {renderMechanicalToggle(
            'KEEP SCREEN ON',
            'Prevent display timeout while playing videos',
            playerKeepScreenOn,
            () => {
              const next = !playerKeepScreenOn;
              setPlayerKeepScreenOn(next);
              persistSettings({ playerKeepScreenOn: next });
            }
          )}

          {renderMechanicalToggle(
            'AUTO PICTURE-IN-PICTURE ON HOME',
            'Shrink video into floating mini-player when pressing Home',
            playerAutoPip,
            () => {
              const next = !playerAutoPip;
              setPlayerAutoPip(next);
              persistSettings({ playerAutoPip: next });
            }
          )}

          {renderMechanicalToggle(
            'BRIGHTNESS AND VOLUME GESTURES',
            'Swipe vertically on left/right sides of video to adjust levels',
            playerGesturesEnabled,
            () => {
              const next = !playerGesturesEnabled;
              setPlayerGesturesEnabled(next);
              persistSettings({ playerGesturesEnabled: next });
            }
          )}

          {renderMechanicalToggle(
            'AUTO-PLAY NEXT VIDEO',
            'Seamlessly start the next video when playback finishes',
            autoPlayNext,
            () => {
              const next = !autoPlayNext;
              setAutoPlayNext(next);
              persistSettings({ autoPlayNext: next });
            }
          )}

          <div>
            <label className="block font-mono text-xs text-slate-300 uppercase mb-1">
              AUTO-PLAY REPEAT MODE
            </label>
            <select
              value={repeatMode}
              onChange={(e) => {
                const v = e.target.value as 'off' | 'one' | 'all';
                setRepeatMode(v);
                persistSettings({ repeatMode: v });
              }}
              className="machine-input w-full px-3 py-2 rounded-lg font-mono text-xs bg-slate-950"
            >
              <option value="off">Repeat Off (Advance to next video)</option>
              <option value="one">Repeat One (Loop current video)</option>
              <option value="all">Repeat All (Loop playlist)</option>
            </select>
          </div>
        </div>
      </div>

      {/* 3. FLOATING EDGE TAB // DRAWER */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3 stagger-card"
        style={{ '--stagger': 3 } as React.CSSProperties}
      >
        <div className="flex items-center gap-2 pb-2 border-b border-accent">
          <Layers className="w-4 h-4 text-accent" />
          <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
            FLOATING TAB // DRAWER
          </h3>
        </div>

        <div className="space-y-3">
          {renderMechanicalToggle(
            'SYSTEM FLOATING TAB',
            'Display hardware edge tab across all Android apps for instant downloads',
            isBubbleActive,
            handleToggleBubble
          )}

          <div>
            <div className="text-[10px] font-mono text-slate-400 uppercase tracking-wider mb-1.5">
              TAB SIZE
            </div>
            <div className="grid grid-cols-3 gap-1 p-1 bg-slate-950 rounded-lg border border-slate-800">
              {FLOATING_SIZE_OPTIONS.map((opt) => {
                const isSelected = floatingSize === opt.id;
                return (
                  <button
                    key={opt.id}
                    onClick={() => {
                      setFloatingSize(opt.id);
                      persistSettings({ floatingSize: opt.id });
                    }}
                    className={`py-1.5 rounded text-center font-mono text-xs font-bold transition-all whitespace-nowrap ${
                      isSelected
                        ? 'machine-btn-primary text-slate-950 glow-accent-sm'
                        : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {opt.label}
                  </button>
                );
              })}
            </div>
          </div>

          <div>
            <div className="text-[10px] font-mono text-slate-400 uppercase tracking-wider mb-1.5">
              IDLE OPACITY
            </div>
            <div className="grid grid-cols-4 gap-1 p-1 bg-slate-950 rounded-lg border border-slate-800">
              {FLOATING_OPACITY_OPTIONS.map((opt) => {
                const isSelected = floatingOpacity === opt.value;
                return (
                  <button
                    key={opt.value}
                    onClick={() => {
                      setFloatingOpacity(opt.value);
                      persistSettings({ floatingOpacity: opt.value });
                    }}
                    className={`py-1.5 rounded text-center font-mono text-xs font-bold transition-all whitespace-nowrap tabular-nums ${
                      isSelected
                        ? 'machine-btn-primary text-slate-950 glow-accent-sm'
                        : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {opt.label}
                  </button>
                );
              })}
            </div>
          </div>

          <div>
            <div className="text-[10px] font-mono text-slate-400 uppercase tracking-wider mb-1.5">
              EDGE SIDE
            </div>
            <div className="grid grid-cols-2 gap-1 p-1 bg-slate-950 rounded-lg border border-slate-800">
              {(['left', 'right'] as const).map((side) => {
                const isSelected = floatingSide === side;
                return (
                  <button
                    key={side}
                    onClick={() => {
                      setFloatingSide(side);
                      persistSettings({ floatingSide: side });
                    }}
                    className={`py-1.5 rounded text-center font-mono text-xs font-bold uppercase transition-all whitespace-nowrap ${
                      isSelected
                        ? 'machine-btn-primary text-slate-950 glow-accent-sm'
                        : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {side.toUpperCase()}
                  </button>
                );
              })}
            </div>
          </div>

          {renderMechanicalToggle(
            'SHOW DUAL PING READOUT',
            'Display real-time server and network latency in milliseconds',
            floatingShowMs,
            () => {
              const next = !floatingShowMs;
              setFloatingShowMs(next);
              persistSettings({ floatingShowMs: next });
            }
          )}

          {renderMechanicalToggle(
            'AUTO-CLEAR AFTER DOWNLOAD',
            'Automatically clear the link field and progress bar 4s after completion',
            floatingAutoClear,
            () => {
              const next = !floatingAutoClear;
              setFloatingAutoClear(next);
              persistSettings({ floatingAutoClear: next });
            }
          )}

          {renderMechanicalToggle(
            'HAPTIC MOTOR FEEDBACK',
            'Vibrate short pulse on successful download and double pulse on failure',
            floatingVibrate,
            () => {
              const next = !floatingVibrate;
              setFloatingVibrate(next);
              persistSettings({ floatingVibrate: next });
            }
          )}

          {renderMechanicalToggle(
            'HIDE FLOATING TAB INSIDE OMNIDROP',
            'Automatically hide edge tab and drawer while using OmniDrop or media player',
            floatingHideInsideApp,
            () => {
              const next = !floatingHideInsideApp;
              setFloatingHideInsideApp(next);
              persistSettings({ floatingHideInsideApp: next });
            }
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

      {/* 4. SERVER KEEP-ALIVE (READ-ONLY CARD TIED TO FLOATING TAB) */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3 stagger-card"
        style={{ '--stagger': 4 } as React.CSSProperties}
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

      {/* 5. PRIVACY // SECURITY */}
      <div
        className="machine-surface rounded-xl p-4 space-y-2 stagger-card"
        style={{ '--stagger': 5 } as React.CSSProperties}
      >
        <div className="flex items-center gap-2 pb-2 border-b border-accent">
          <Lock className="w-4 h-4 text-accent" />
          <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
            PRIVACY // SECURITY
          </h3>
        </div>

        {renderMechanicalToggle(
          'HIDE OMNIDROP IN RECENT APPS PREVIEW',
          'Blank screen thumbnail and block screenshots in Android app switcher',
          hideInRecents,
          () => {
            const next = !hideInRecents;
            setHideInRecents(next);
            persistSettings({ hideInRecents: next });
          }
        )}
      </div>

      {/* 6. STORAGE // CACHE CONTROL */}
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

        <div className="space-y-2.5">
          <div className="grid grid-cols-3 gap-2 font-mono text-xs">
            <div className="p-2.5 rounded-lg bg-slate-950 border border-slate-800">
              <div className="text-[10px] text-slate-500 uppercase">VIDEOS</div>
              <div className="text-white font-bold mt-0.5 tabular-nums whitespace-nowrap">
                {formatBytes(storageUsage.videoBytes)}
              </div>
              <div className="text-[10px] text-slate-500 tabular-nums">
                {storageUsage.videoCount} files
              </div>
            </div>

            <div className="p-2.5 rounded-lg bg-slate-950 border border-slate-800">
              <div className="text-[10px] text-slate-500 uppercase">PHOTOS</div>
              <div className="text-white font-bold mt-0.5 tabular-nums whitespace-nowrap">
                {formatBytes(storageUsage.photoBytes)}
              </div>
              <div className="text-[10px] text-slate-500 tabular-nums">
                {storageUsage.photoCount} files
              </div>
            </div>

            <div className="p-2.5 rounded-lg bg-slate-950 border border-slate-800">
              <div className="text-[10px] text-slate-500 uppercase">AUDIO</div>
              <div className="text-white font-bold mt-0.5 tabular-nums whitespace-nowrap">
                {formatBytes(storageUsage.audioBytes)}
              </div>
              <div className="text-[10px] text-slate-500 tabular-nums">
                {storageUsage.audioCount} files
              </div>
            </div>
          </div>

          <div className="flex items-center justify-between gap-2 p-3 rounded-lg bg-slate-950 border border-slate-800 font-mono text-xs">
            <span className="text-slate-400 truncate">TOTAL OMNIDROP STORAGE:</span>
            <span className="text-accent-soft font-bold tabular-nums whitespace-nowrap shrink-0">
              {formatBytes(storageUsage.totalBytes)}
            </span>
          </div>

          <div className="flex items-center justify-between gap-2 pt-1">
            <div className="min-w-0 pr-2">
              <div className="font-mono text-xs text-white uppercase font-bold">
                THUMBNAIL CACHE
              </div>
              <div className="text-[11px] text-slate-400">
                Clears cached previews &amp; titles without deleting saved media
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
              <span>CLEAR THUMBNAIL CACHE</span>
            </button>
          </div>

          {cacheClearMsg && (
            <div className="p-2.5 rounded-lg bg-emerald-950/50 border border-emerald-500/40 text-emerald-300 font-mono text-xs">
              {cacheClearMsg}
            </div>
          )}
        </div>
      </div>

      {/* 7. APPEARANCE // THEME & MOTION */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3 stagger-card"
        style={{ '--stagger': 7 } as React.CSSProperties}
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

      {/* 8. APP UPDATE // FIRMWARE */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3.5 border border-accent stagger-card"
        style={{ '--stagger': 8 } as React.CSSProperties}
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

      {/* 9. SERVER // CONFIG */}
      <div
        ref={serverConfigRef}
        className="machine-surface rounded-xl p-4 space-y-3 stagger-card"
        style={{ '--stagger': 9 } as React.CSSProperties}
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
