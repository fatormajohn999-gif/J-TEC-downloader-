import React, { useState, useEffect, useCallback } from 'react';
import {
  Clipboard,
  Download,
  Loader2,
  AlertCircle,
  CheckCircle2,
  Trash2,
  Play,
  RotateCcw,
  Check,
  Film,
  Images,
  Copy,
  X,
  HardDrive,
  Activity,
} from 'lucide-react';
import { Capacitor } from '@capacitor/core';
import { VideoInfo, AppSettings, PlatformType, DownloadHistoryItem, FailedDownloadItem } from '../types';
import { isSupportedUrl, fetchVideoInfo, downloadVideoFile, wakeUpServerUntilReady, normalizeUrl, resolveEffectiveQuality } from '../services/api';
import { VideoDownloader } from '../plugins/videoDownloader';
import { EmptyState } from './EmptyState';
import { SpeedArcGauge } from './SpeedArcGauge';

interface HomeScreenProps {
  settings: AppSettings;
  onDownloadCompleted: (item: {
    url: string;
    title: string;
    thumbnail: string;
    uploader: string;
    duration: number;
    quality: string;
    platform: PlatformType;
    fileName: string;
  }) => void;
  pendingUrl?: string;
  onClearPendingUrl?: () => void;
  onWakeServerStart: () => void;
  onWakeServerEnd: () => void;
  onOpenSettings?: () => void;
  recentDownloads?: DownloadHistoryItem[];
  serverStatus?: 'connected' | 'checking' | 'offline';
  serverLatency?: number | null;
}

const QUALITY_OPTIONS = [
  { id: 'best', label: 'BEST' },
  { id: '1080p', label: '1080P' },
  { id: '720p', label: '720P' },
  { id: 'audio', label: 'MP3' },
];

function getLocalTodayKey(): string {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function formatDataMb(bytes: number): string {
  if (!bytes || bytes <= 0) return '0.0 MB';
  const mb = bytes / (1024 * 1024);
  if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`;
  return `${mb.toFixed(1)} MB`;
}

export const HomeScreen: React.FC<HomeScreenProps> = ({
  settings,
  onDownloadCompleted,
  pendingUrl,
  onClearPendingUrl,
  onWakeServerStart,
  onWakeServerEnd,
  onOpenSettings,
  recentDownloads = [],
  serverStatus = 'connected',
  serverLatency = null,
}) => {
  const isNative = Capacitor.isNativePlatform();
  const isBackendMissingOnNative = isNative && (!settings.backendUrl || !settings.backendUrl.trim());
  const [inputUrl, setInputUrl] = useState<string>('');
  const [isLoadingInfo, setIsLoadingInfo] = useState<boolean>(false);
  const [videoInfo, setVideoInfo] = useState<VideoInfo | null>(null);
  const [selectedQuality, setSelectedQuality] = useState<string>(settings.defaultQuality || 'best');
  const [isDownloading, setIsDownloading] = useState<boolean>(false);
  const [downloadProgress, setDownloadProgress] = useState<number>(0);
  const [downloadStatusText, setDownloadStatusText] = useState<string>('');
  const [downloadSpeedMb, setDownloadSpeedMb] = useState<number>(0);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Dashboard recorded today stats
  const [todayDownloads, setTodayDownloads] = useState<number>(0);
  const [todayBytes, setTodayBytes] = useState<number>(0);

  const [duplicateDialog, setDuplicateDialog] = useState<{
    open: boolean;
    uri: string;
    title: string;
    fileName: string;
  } | null>(null);

  const [isMobileData, setIsMobileData] = useState<boolean>(false);
  const [mobileDataConfirm, setMobileDataConfirm] = useState<{
    open: boolean;
    sizeMb: number;
    url: string;
    force: boolean;
  } | null>(null);

  // Failed downloads modal state
  const [failedLinks, setFailedLinks] = useState<FailedDownloadItem[]>([]);
  const [failedModalOpen, setFailedModalOpen] = useState<boolean>(false);
  const [copiedFailedId, setCopiedFailedId] = useState<string | null>(null);

  const refreshNetworkStatus = useCallback(async () => {
    try {
      const net = await VideoDownloader.getNetworkStatus();
      setIsMobileData(Boolean(net?.isMobileData));
      return Boolean(net?.isMobileData);
    } catch {
      return false;
    }
  }, []);

  const loadTodayStats = useCallback(async () => {
    try {
      const stats = await VideoDownloader.getDownloadStats();
      if (stats && Array.isArray(stats.dailyStats)) {
        const todayKey = getLocalTodayKey();
        const entry = stats.dailyStats.find((d) => d.date === todayKey);
        if (entry) {
          setTodayDownloads(entry.downloads || 0);
          setTodayBytes(entry.bytes || 0);
          return;
        }
      }
      setTodayDownloads(0);
      setTodayBytes(0);
    } catch {
      // Leave zeros when unavailable
    }
  }, []);

  const loadFailedLinks = useCallback(async () => {
    try {
      const res = await VideoDownloader.getFailedLinks();
      if (res && Array.isArray(res.failedLinks)) {
        setFailedLinks(res.failedLinks);
      }
    } catch (e) {
      console.warn('Failed to load failed links:', e);
    }
  }, []);

  useEffect(() => {
    loadTodayStats();
    loadFailedLinks();
    refreshNetworkStatus();

    let hFailed: any = null;
    let hFinished: any = null;
    (async () => {
      try {
        hFailed = await VideoDownloader.addListener('onFailedLinksChanged', () => {
          loadFailedLinks();
        });
        hFinished = await VideoDownloader.addListener('nativeDownloadFinished', () => {
          loadTodayStats();
        });
      } catch {}
    })();

    const onFocus = () => {
      loadTodayStats();
      refreshNetworkStatus();
    };
    window.addEventListener('focus', onFocus);

    return () => {
      window.removeEventListener('focus', onFocus);
      if (hFailed && handleRemove(hFailed)) {}
      if (hFinished && handleRemove(hFinished)) {}
    };
  }, [loadFailedLinks, loadTodayStats, refreshNetworkStatus]);

  function handleRemove(h: any): boolean {
    if (h && typeof h.remove === 'function') {
      h.remove();
    }
    return true;
  }

  // Sync if pendingUrl was passed from Share Target or Clipboard detector
  useEffect(() => {
    if (pendingUrl) {
      setInputUrl(pendingUrl);
      inspectUrl(pendingUrl);
      if (onClearPendingUrl) onClearPendingUrl();
    }
  }, [pendingUrl]);

  const formatDuration = (seconds: number) => {
    if (!seconds) return '0:00';
    const hrs = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);
    if (hrs > 0) {
      return `${hrs}:${mins < 10 ? '0' : ''}${mins}:${secs < 10 ? '0' : ''}${secs}`;
    }
    return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
  };

  const handlePaste = async () => {
    setErrorMessage(null);
    try {
      const text = await navigator.clipboard.readText();
      if (text) {
        const cleaned = normalizeUrl(text.trim());
        setInputUrl(cleaned);
        if (isSupportedUrl(cleaned)) {
          inspectUrl(cleaned);
        }
      }
    } catch {
      setErrorMessage('Unable to access clipboard. Please paste your link manually.');
    }
  };

  const handleClear = () => {
    setInputUrl('');
    setVideoInfo(null);
    setErrorMessage(null);
    setSuccessMessage(null);
    setDuplicateDialog(null);
  };

  const inspectUrl = async (targetUrl?: string) => {
    const raw = (targetUrl || inputUrl).trim();
    const url = normalizeUrl(raw);
    if (!url) {
      setErrorMessage('Please paste or enter a video link.');
      return;
    }
    setInputUrl(url);

    if (!isSupportedUrl(url)) {
      setErrorMessage('Unsupported link. Please paste a URL from TikTok, YouTube, Instagram, or Facebook.');
      return;
    }

    if (isBackendMissingOnNative) {
      setErrorMessage('Please set your Render Backend URL in Settings before downloading on mobile.');
      return;
    }

    setErrorMessage(null);
    setSuccessMessage(null);
    setDuplicateDialog(null);
    setIsLoadingInfo(true);

    try {
      const info = await fetchVideoInfo(url, settings);
      setVideoInfo(info);
    } catch (err: any) {
      const msg = err.message || 'Could not inspect video';
      if (msg.includes('waking up') || msg.includes('timeout') || msg.includes('Failed to fetch')) {
        onWakeServerStart();
        const wokeUp = await wakeUpServerUntilReady(settings);
        onWakeServerEnd();
        if (wokeUp) {
          try {
            const retryInfo = await fetchVideoInfo(url, settings);
            setVideoInfo(retryInfo);
            setIsLoadingInfo(false);
            return;
          } catch (retryErr: any) {
            setErrorMessage(retryErr.message || 'Inspection failed after waking server.');
          }
        } else {
          setErrorMessage('The backend service could not be reached. Check your backend URL in Settings.');
        }
      } else {
        setErrorMessage(msg);
      }
    } finally {
      setIsLoadingInfo(false);
    }
  };

  const handleStartDownload = async (force: boolean = false, skipMobilePrompt: boolean = false) => {
    const targetUrl = videoInfo ? videoInfo.url : normalizeUrl(inputUrl.trim());
    if (!targetUrl) {
      setErrorMessage('Please paste or inspect a video URL first.');
      return;
    }

    const onCellular = await refreshNetworkStatus();

    if (!force && isNative && !skipMobilePrompt) {
      try {
        const dup = await VideoDownloader.checkDuplicate({ url: targetUrl });
        if (dup && dup.exists) {
          setDuplicateDialog({
            open: true,
            uri: dup.uri || '',
            title: dup.title || (videoInfo ? videoInfo.title : 'Downloaded Video'),
            fileName: dup.fileName || '',
          });
          return;
        }
      } catch (e) {
        console.warn('Duplicate check skipped:', e);
      }
    }

    // Mobile data size alert check
    const limitSetting = settings.mobileDataPromptMb ?? 'off';
    if (!skipMobilePrompt && onCellular && limitSetting !== 'off') {
      const rawBytes = videoInfo?.filesize || videoInfo?.filesize_approx || 0;
      const limitMb = typeof limitSetting === 'number' ? limitSetting : Number(limitSetting);
      if (rawBytes > 0 && limitMb > 0) {
        const fileSizeMb = rawBytes / (1024 * 1024);
        if (fileSizeMb > limitMb) {
          setMobileDataConfirm({
            open: true,
            sizeMb: fileSizeMb,
            url: targetUrl,
            force,
          });
          return;
        }
      }
    }

    setIsDownloading(true);
    setDownloadProgress(10);
    setDownloadStatusText('Connecting to downloader...');
    setDownloadSpeedMb(0);
    setErrorMessage(null);
    setSuccessMessage(null);
    setDuplicateDialog(null);
    setMobileDataConfirm(null);

    const titleToUse = videoInfo?.title || 'video';
    const uploaderToUse = videoInfo?.uploader || 'Creator';
    const thumbToUse = videoInfo?.thumbnail || '';
    const durationToUse = videoInfo?.duration || 0;
    const platformToUse = videoInfo?.platform || 'other';

    let { effectiveQuality } = resolveEffectiveQuality(selectedQuality, Boolean(settings.dataSaver), onCellular);
    const fallbackAllowed = settings.fallbackToBestQuality !== false;
    if (
      fallbackAllowed &&
      videoInfo?.qualities &&
      videoInfo.qualities.length > 0 &&
      effectiveQuality !== 'best' &&
      effectiveQuality !== 'audio' &&
      !videoInfo.qualities.map((q) => q.toLowerCase()).includes(effectiveQuality.toLowerCase())
    ) {
      effectiveQuality = 'best';
    }

    const runDownload = async (qualityArg: string) =>
      downloadVideoFile(
        targetUrl,
        qualityArg,
        titleToUse,
        settings,
        (pct, text, speedMb) => {
          setDownloadProgress(pct);
          setDownloadStatusText(text);
          if (typeof speedMb === 'number' && speedMb >= 0) {
            setDownloadSpeedMb(speedMb);
          }
        }
      );

    try {
      let fileName: string;
      let usedQuality = effectiveQuality;
      try {
        fileName = await runDownload(effectiveQuality);
      } catch (firstErr: any) {
        if (fallbackAllowed && effectiveQuality !== 'best') {
          usedQuality = 'best';
          setDownloadStatusText('Fallback: retrying with best quality...');
          fileName = await runDownload('best');
        } else {
          throw firstErr;
        }
      }

      if (settings.notifyDownloadFinish !== false) {
        try {
          await VideoDownloader.showDownloadNotification({
            title: 'Download Completed',
            message: `${titleToUse.substring(0, 40)} saved to gallery!`,
          });
        } catch {}
      }

      onDownloadCompleted({
        url: targetUrl,
        title: titleToUse,
        thumbnail: thumbToUse,
        uploader: uploaderToUse,
        duration: durationToUse,
        quality: usedQuality,
        platform: platformToUse,
        fileName,
      });

      setSuccessMessage(`SAVED: ${fileName}`);
      loadTodayStats();
    } catch (err: any) {
      const msg = err?.message || 'Download failed. Please check network/backend.';
      setErrorMessage(msg);
      if (settings.notifyDownloadFail !== false) {
        try {
          await VideoDownloader.showDownloadFailedNotification({
            title: 'Download Failed',
            message: msg.substring(0, 120),
          });
        } catch {}
      }
    } finally {
      setIsDownloading(false);
      setDownloadSpeedMb(0);
    }
  };

  const handlePlayExisting = async (uri: string, title: string) => {
    try {
      const res = await VideoDownloader.playVideo({ uri, title });
      if (!res?.started) {
        setErrorMessage('Native video playback is available in the Android build.');
      }
    } catch (e: any) {
      setErrorMessage(e?.message || 'Unable to launch video player.');
    }
  };

  // Segmented 16-block progress + Live Speed Arc Gauge
  const renderDownloadingVisuals = () => {
    const totalBlocks = 16;
    const filledBlocks = Math.round((downloadProgress / 100) * totalBlocks);

    return (
      <div className="flex items-center gap-3 pt-1">
        <div className="flex-1 min-w-0 space-y-1.5">
          <div className="flex items-center justify-between gap-2 font-mono text-[11px]">
            <span className="text-accent font-bold tabular-nums w-10 whitespace-nowrap">
              {downloadProgress}%
            </span>
            <span className="text-slate-400 truncate text-right">{downloadStatusText}</span>
          </div>
          <div className="grid grid-cols-16 gap-1 p-1 bg-slate-950 rounded-md border border-accent">
            {Array.from({ length: totalBlocks }).map((_, i) => (
              <div
                key={i}
                className={`h-2.5 rounded-xs transition-colors duration-150 ${
                  i < filledBlocks
                    ? 'bg-accent glow-accent-sm'
                    : 'bg-slate-900 border border-slate-800'
                }`}
              />
            ))}
          </div>
        </div>

        <SpeedArcGauge speedMb={downloadSpeedMb} isActive={isDownloading} />
      </div>
    );
  };

  const handleRetryFailedItem = async (item: FailedDownloadItem) => {
    setFailedModalOpen(false);
    setInputUrl(item.url);
    if (isNative) {
      try {
        await VideoDownloader.retryFailedLink({ id: item.id });
      } catch {
        inspectUrl(item.url);
      }
    } else {
      inspectUrl(item.url);
    }
  };

  const handleCopyFailed = async (item: FailedDownloadItem) => {
    try {
      await navigator.clipboard.writeText(item.url);
      setCopiedFailedId(item.id);
      setTimeout(() => setCopiedFailedId(null), 2000);
    } catch {}
  };

  const handleDeleteFailed = async (id: string) => {
    try {
      await VideoDownloader.removeFailedLink({ id });
      loadFailedLinks();
    } catch {}
  };

  const handleClearAllFailed = async () => {
    try {
      await VideoDownloader.clearFailedLinks();
      setFailedLinks([]);
      setFailedModalOpen(false);
    } catch {}
  };

  const handleRetryAllFailed = async () => {
    if (failedLinks.length === 0) return;
    setFailedModalOpen(false);
    for (const item of failedLinks) {
      try {
        await VideoDownloader.retryFailedLink({ id: item.id });
      } catch {}
    }
    setTimeout(loadFailedLinks, 1000);
  };

  const formatFailedTime = (timestamp: number) => {
    if (!timestamp) return '';
    const diff = Date.now() - timestamp;
    const mins = Math.floor(diff / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return `${mins}m ago`;
    const hrs = Math.floor(mins / 60);
    if (hrs < 24) return `${hrs}h ago`;
    const days = Math.floor(hrs / 24);
    return `${days}d ago`;
  };

  const serverLedClass =
    serverStatus === 'connected'
      ? serverLatency && serverLatency > 3000
        ? 'led-amber'
        : 'led-emerald'
      : serverStatus === 'checking'
      ? 'led-amber animate-pulse'
      : 'led-rose';

  const serverStateText =
    serverStatus === 'connected'
      ? serverLatency && serverLatency > 3000
        ? 'SLEEP'
        : 'ONLINE'
      : serverStatus === 'checking'
      ? 'PING...'
      : 'OFFLINE';

  return (
    <div className="space-y-4 pb-28">
      {/* DASHBOARD STRIP: TODAY / DATA / SERVER */}
      <div
        className="grid grid-cols-3 gap-2 stagger-card"
        style={{ '--stagger': 0 } as React.CSSProperties}
      >
        <div className="machine-surface rounded-xl px-2.5 py-2.5 flex flex-col justify-between min-w-0">
          <div className="flex items-center justify-between gap-1 text-[10px] font-mono text-slate-400 uppercase whitespace-nowrap">
            <span>TODAY</span>
            <Download className="w-3 h-3 text-accent shrink-0" />
          </div>
          <div className="font-mono text-sm font-bold text-white tabular-nums whitespace-nowrap mt-1">
            {todayDownloads} <span className="text-[10px] text-slate-400 font-normal">DLS</span>
          </div>
        </div>

        <div className="machine-surface rounded-xl px-2.5 py-2.5 flex flex-col justify-between min-w-0">
          <div className="flex items-center justify-between gap-1 text-[10px] font-mono text-slate-400 uppercase whitespace-nowrap">
            <span>DATA</span>
            <HardDrive className="w-3 h-3 text-accent shrink-0" />
          </div>
          <div className="font-mono text-sm font-bold text-accent-soft tabular-nums whitespace-nowrap mt-1">
            {formatDataMb(todayBytes)}
          </div>
        </div>

        <div className="machine-surface rounded-xl px-2.5 py-2.5 flex flex-col justify-between min-w-0">
          <div className="flex items-center justify-between gap-1 text-[10px] font-mono text-slate-400 uppercase whitespace-nowrap">
            <span>SERVER</span>
            <Activity className="w-3 h-3 text-accent shrink-0" />
          </div>
          <div className="flex items-center gap-1.5 font-mono text-xs font-bold text-white whitespace-nowrap mt-1">
            <span className={`w-2 h-2 rounded-full shrink-0 ${serverLedClass}`} />
            <span className="truncate">{serverStateText}</span>
          </div>
        </div>
      </div>

      {/* Backend Required Warning if unconfigured */}
      {isBackendMissingOnNative && (
        <div className="machine-surface p-3.5 rounded-xl border border-amber-500/60 text-amber-200 space-y-2 stagger-card" style={{ '--stagger': 1 } as React.CSSProperties}>
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full led-amber shrink-0" />
            <h4 className="text-xs font-mono font-bold uppercase tracking-wider text-white truncate">
              CONFIGURATION REQUIRED // RENDER BACKEND
            </h4>
          </div>
          <p className="text-xs text-amber-200/90 leading-relaxed font-sans">
            Please configure your Render Backend URL in Settings before downloading on mobile.
          </p>
          {onOpenSettings && (
            <button
              onClick={onOpenSettings}
              className="machine-btn px-3 py-1 rounded-lg text-xs font-mono font-bold text-accent-soft whitespace-nowrap"
            >
              GOTO // SETTINGS
            </button>
          )}
        </div>
      )}

      {/* Duplicate Alert Dialog */}
      {duplicateDialog && duplicateDialog.open && (
        <div className="machine-surface p-4 rounded-xl border-accent-strong glow-accent space-y-3 animate-fade-in">
          <div className="flex items-center gap-2">
            <span className="w-2.5 h-2.5 rounded-full led-accent shrink-0" />
            <h3 className="font-mono text-xs font-bold uppercase tracking-wider text-white truncate">
              ALREADY DOWNLOADED // FILE EXISTS
            </h3>
          </div>
          <p className="text-xs text-slate-300 font-sans">
            This video was already downloaded and is stored in your gallery:
          </p>
          <div className="font-mono text-[11px] text-accent-soft p-2 rounded bg-slate-950 border border-slate-800 truncate">
            {duplicateDialog.fileName || duplicateDialog.title}
          </div>
          <div className="flex items-center gap-2 pt-1">
            <button
              onClick={() => handlePlayExisting(duplicateDialog.uri, duplicateDialog.title)}
              className="machine-btn-primary flex-1 py-2 rounded-lg font-mono text-xs uppercase flex items-center justify-center gap-1.5 whitespace-nowrap"
            >
              <Play className="w-3.5 h-3.5 fill-current" />
              <span>PLAY NOW</span>
            </button>
            <button
              onClick={() => handleStartDownload(true)}
              className="machine-btn flex-1 py-2 rounded-lg font-mono text-xs text-slate-300 uppercase flex items-center justify-center gap-1.5 whitespace-nowrap"
            >
              <RotateCcw className="w-3.5 h-3.5" />
              <span>DOWNLOAD AGAIN</span>
            </button>
          </div>
        </div>
      )}

      {/* HERO CARD: PASTE LINK // INGESTION UNIT */}
      <div
        className="machine-surface rounded-xl p-4 space-y-3.5 stagger-card"
        style={{ '--stagger': 1 } as React.CSSProperties}
      >
        <div className="flex items-center justify-between gap-2 pb-2 border-b border-accent">
          <div className="flex items-center gap-2 min-w-0">
            <span className="w-2 h-2 rounded-full led-accent shrink-0" />
            <h3 className="font-mono text-xs font-bold uppercase tracking-wider text-white truncate">
              PASTE LINK // INGESTION
            </h3>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            {failedLinks.length > 0 && (
              <button
                onClick={() => setFailedModalOpen(true)}
                className="px-2 py-0.5 rounded-full font-mono text-[10px] font-bold bg-red-950 text-red-400 border border-red-500/80 shadow-[0_0_8px_rgba(239,68,68,0.4)] hover:bg-red-900 transition-all flex items-center gap-1 animate-pulse whitespace-nowrap tabular-nums"
                title="View failed downloads"
              >
                <AlertCircle className="w-3 h-3 text-red-400" />
                <span>FAILED {failedLinks.length}</span>
              </button>
            )}
            <span className="font-mono text-[10px] text-slate-400 uppercase whitespace-nowrap">
              TT • YT • IG • FB
            </span>
          </div>
        </div>

        {/* Input & Paste Key */}
        <div className="space-y-2.5">
          <div className="flex items-center gap-2">
            <div className="relative flex-1 min-w-0">
              <input
                type="text"
                value={inputUrl}
                onChange={(e) => setInputUrl(e.target.value)}
                placeholder="https://tiktok.com/@... or youtu.be/..."
                className="machine-input w-full rounded-lg px-3 py-2.5 pr-14 text-xs font-mono text-slate-200 placeholder:text-slate-600"
              />
              {inputUrl && (
                <button
                  onClick={handleClear}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-500 hover:text-slate-300 font-mono text-[10px] whitespace-nowrap"
                >
                  CLEAR
                </button>
              )}
            </div>

            <button
              onClick={handlePaste}
              className="machine-btn px-3.5 py-2.5 rounded-lg flex items-center gap-1.5 text-xs font-mono font-bold text-accent-soft shrink-0 whitespace-nowrap"
              title="Paste link from clipboard"
            >
              <Clipboard className="w-3.5 h-3.5 text-accent" />
              <span>PASTE</span>
            </button>
          </div>

          {/* Quality Selector Segmented Switch */}
          {(!videoInfo || videoInfo.type !== 'photo') && (
            <div>
              <div className="flex items-center justify-between gap-2 mb-1">
                <div className="text-[10px] font-mono text-slate-400 uppercase tracking-wider">
                  STREAM QUALITY // ENCODE
                </div>
                {settings.dataSaver && isMobileData && (selectedQuality === 'best' || selectedQuality === '1080p') && (
                  <span className="font-mono text-[9px] font-bold text-amber-300 bg-amber-950/60 border border-amber-500/40 px-1.5 py-0.5 rounded whitespace-nowrap">
                    DATA SAVER · 720P CAP
                  </span>
                )}
              </div>
              <div className="grid grid-cols-4 gap-1 p-1 bg-slate-950 rounded-lg border border-slate-800">
                {QUALITY_OPTIONS.map((q) => {
                  const isSelected = selectedQuality === q.id;
                  return (
                    <button
                      key={q.id}
                      onClick={() => setSelectedQuality(q.id)}
                      className={`py-1.5 rounded text-center font-mono text-xs font-bold transition-all whitespace-nowrap ${
                        isSelected
                          ? 'machine-btn-primary text-slate-950 glow-accent-sm'
                          : 'text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      {q.label}
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* Download Action Key */}
          <button
            onClick={() => {
              if (!videoInfo && inputUrl.trim()) {
                inspectUrl();
              } else {
                handleStartDownload();
              }
            }}
            disabled={isLoadingInfo || isDownloading}
            className="machine-btn-primary w-full py-3 rounded-lg flex items-center justify-center gap-2 text-xs uppercase tracking-wider font-mono disabled:opacity-50 whitespace-nowrap"
          >
            {isLoadingInfo ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                <span>INSPECTING STREAM...</span>
              </>
            ) : isDownloading ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                <span>{videoInfo?.type === 'photo' ? 'SAVING PHOTOS...' : 'DOWNLOADING...'}</span>
              </>
            ) : (
              <>
                {videoInfo?.type === 'photo' ? <Images className="w-4 h-4" /> : <Download className="w-4 h-4" />}
                <span>
                  {videoInfo
                    ? videoInfo.type === 'photo'
                      ? 'SAVE PHOTOS'
                      : 'DOWNLOAD VIDEO FILE'
                    : 'INSPECT & DOWNLOAD'}
                </span>
              </>
            )}
          </button>
        </div>

        {/* Status Feedback Messages */}
        {errorMessage && (
          <div className="p-3 rounded-lg bg-rose-950/40 border border-rose-500/50 flex items-start gap-2 text-xs text-rose-200 font-mono animate-fade-in">
            <AlertCircle className="w-4 h-4 text-rose-400 shrink-0 mt-0.5" />
            <span className="leading-snug">{errorMessage}</span>
          </div>
        )}

        {successMessage && (
          <div className="p-3 rounded-lg bg-emerald-950/40 border border-emerald-500/50 flex items-start gap-2 text-xs text-emerald-200 font-mono animate-fade-in">
            <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />
            <span className="leading-snug">{successMessage}</span>
          </div>
        )}
      </div>

      {/* POST-FETCH MEDIA CARD (OR ACTIVE DOWNLOAD VISUAL HUD) */}
      {(videoInfo || isDownloading) && (
        <div
          className="machine-surface rounded-xl p-4 space-y-3 animate-fade-in stagger-card"
          style={{ '--stagger': 2 } as React.CSSProperties}
        >
          <div className="flex items-center justify-between gap-2 pb-2 border-b border-accent">
            <div className="flex items-center gap-2 min-w-0">
              <span className={`w-2 h-2 rounded-full shrink-0 ${isDownloading ? 'led-accent animate-pulse' : 'led-emerald'}`} />
              <h4 className="font-mono text-xs font-bold text-white uppercase tracking-wider truncate">
                {videoInfo
                  ? videoInfo.type === 'photo'
                    ? 'TIKTOK PHOTO POST LOCATED'
                    : 'VIDEO TARGET LOCATED'
                  : 'ACTIVE STREAM // TRANSFER'}
              </h4>
            </div>

            {videoInfo && (videoInfo.type === 'photo' ? (
              <div className="flex items-center gap-1.5 px-2 py-0.5 rounded bg-purple-950/80 border border-purple-500/50 font-mono text-[10px] text-purple-300 font-bold shrink-0 whitespace-nowrap">
                <Images className="w-3 h-3 text-purple-400" />
                <span>{videoInfo.imageCount ? `${videoInfo.imageCount} PHOTOS` : 'PHOTOS SLIDESHOW'}</span>
              </div>
            ) : (
              <div className="flex items-center gap-1.5 px-2 py-0.5 rounded bg-emerald-950/80 border border-emerald-500/40 font-mono text-[10px] text-emerald-300 font-bold shrink-0 whitespace-nowrap">
                <span className="w-1.5 h-1.5 rounded-full led-emerald" />
                <span>NO WATERMARK</span>
              </div>
            ))}
          </div>

          <div className="flex gap-3 items-center">
            {/* Thumbnail with animated vertical scan line while downloading */}
            <div className="relative w-24 h-24 rounded-lg overflow-hidden bg-slate-950 border border-slate-800 shrink-0">
              {videoInfo?.thumbnail ? (
                <img
                  src={videoInfo.thumbnail}
                  alt={videoInfo.title}
                  className="w-full h-full object-cover"
                />
              ) : (
                <div className="w-full h-full flex items-center justify-center text-slate-600">
                  {videoInfo?.type === 'photo' ? <Images className="w-8 h-8" /> : <Film className="w-8 h-8 text-accent" />}
                </div>
              )}

              {isDownloading && <div className="scan-line" />}

              {videoInfo && videoInfo.duration > 0 && (
                <span className="absolute bottom-1 right-1 px-1 py-0.2 rounded bg-black/80 font-mono text-[9px] text-white tabular-nums">
                  {formatDuration(videoInfo.duration)}
                </span>
              )}
              {videoInfo?.type === 'photo' && videoInfo.imageCount && (
                <span className="absolute bottom-1 right-1 px-1.5 py-0.5 rounded bg-black/90 font-mono text-[9px] text-purple-300 font-bold border border-purple-500/40 tabular-nums">
                  {videoInfo.imageCount}P
                </span>
              )}
            </div>

            {/* Info details */}
            <div className="flex-1 min-w-0 space-y-1">
              <h5 className="font-mono font-bold text-xs text-white line-clamp-2 leading-snug">
                {videoInfo?.title || 'Processing remote stream...'}
              </h5>
              <p className="font-mono text-[11px] text-accent truncate">
                @{videoInfo?.uploader || 'OmniDrop'}
              </p>
              <div className="flex items-center gap-2 pt-1 font-mono text-[10px] text-slate-400 whitespace-nowrap">
                <span className="uppercase px-1.5 py-0.2 rounded bg-slate-950 border border-slate-800 text-slate-300">
                  {videoInfo?.platform || 'STREAM'}
                </span>
                {videoInfo?.type === 'photo' ? (
                  <span className="text-purple-300 uppercase">PHOTOS ARCHIVE</span>
                ) : (
                  <span>{selectedQuality.toUpperCase()}</span>
                )}
              </div>
            </div>
          </div>

          {/* Segmented Progress + Live Speed Arc Gauge */}
          {isDownloading && renderDownloadingVisuals()}
        </div>
      )}

      {/* RECENT DOWNLOADS (LAST 3) OR UNIFIED EMPTY STATE */}
      <div
        className="machine-surface rounded-xl p-3.5 space-y-2.5 stagger-card"
        style={{ '--stagger': 3 } as React.CSSProperties}
      >
        <div className="flex items-center justify-between pb-1 border-b border-slate-800">
          <span className="font-mono text-xs font-bold text-slate-300 uppercase tracking-wider truncate">
            RECENT DOWNLOADS // QUICK PLAY
          </span>
          <span className="font-mono text-[10px] text-slate-500 whitespace-nowrap tabular-nums">
            {recentDownloads.length > 0 ? `LAST ${Math.min(3, recentDownloads.length)}` : '0 ITEMS'}
          </span>
        </div>

        {recentDownloads.length === 0 ? (
          <EmptyState
            compact
            icon={Film}
            title="NO RECENT DOWNLOADS"
            message="Paste a TikTok, YouTube, Instagram, or Facebook link above to get started."
          />
        ) : (
          <div className="space-y-2">
            {recentDownloads.slice(0, 3).map((item) => (
              <div
                key={item.id}
                className="p-2 rounded-lg bg-slate-950 border border-slate-800 flex items-center justify-between gap-2.5"
              >
                <div className="flex items-center gap-2.5 min-w-0">
                  <div className="w-10 h-10 rounded bg-slate-900 border border-slate-800 overflow-hidden shrink-0">
                    {item.thumbnail ? (
                      <img src={item.thumbnail} alt="" className="w-full h-full object-cover" />
                    ) : (
                      <div className="w-full h-full flex items-center justify-center text-slate-600">
                        <Film className="w-4 h-4" />
                      </div>
                    )}
                  </div>
                  <div className="min-w-0">
                    <h6 className="font-mono text-xs text-white truncate max-w-[200px]">
                      {item.title}
                    </h6>
                    <div className="font-mono text-[10px] text-slate-400 flex items-center gap-2 whitespace-nowrap">
                      <span className="text-accent uppercase">{item.platform}</span>
                      <span>•</span>
                      <span>{item.quality.toUpperCase()}</span>
                    </div>
                  </div>
                </div>

                <button
                  onClick={() => handlePlayExisting(item.fileUri || item.url, item.title)}
                  className="machine-btn px-2.5 py-1.5 rounded-md font-mono text-[11px] text-accent-soft flex items-center gap-1 shrink-0 whitespace-nowrap"
                >
                  <Play className="w-3 h-3 fill-current text-accent" />
                  <span>PLAY</span>
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* FAILED DOWNLOADS MODAL */}
      {failedModalOpen && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-end sm:items-center justify-center p-0 sm:p-4 animate-fade-in">
          <div className="w-full max-w-lg max-h-[85vh] bg-slate-950 border border-red-500/40 rounded-t-2xl sm:rounded-2xl flex flex-col overflow-hidden shadow-[0_0_30px_rgba(239,68,68,0.25)]">
            <div className="p-4 border-b border-red-500/20 flex items-center justify-between bg-gradient-to-r from-red-950/40 to-slate-950">
              <div className="flex items-center gap-2 min-w-0">
                <span className="w-2.5 h-2.5 rounded-full bg-red-500 shadow-[0_0_8px_rgba(239,68,68,0.9)] shrink-0" />
                <div className="min-w-0">
                  <h3 className="font-mono text-xs font-bold uppercase tracking-wider text-white truncate">
                    FAILED DOWNLOADS // RECOVERY
                  </h3>
                  <p className="text-[10px] font-mono text-red-400 tabular-nums whitespace-nowrap">
                    {failedLinks.length} failed link{failedLinks.length === 1 ? '' : 's'} recorded
                  </p>
                </div>
              </div>
              <button
                onClick={() => setFailedModalOpen(false)}
                className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition shrink-0"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {failedLinks.length > 0 && (
              <div className="p-3 border-b border-slate-800/80 bg-slate-900/60 flex items-center justify-between gap-2">
                <button
                  onClick={handleRetryAllFailed}
                  className="machine-btn-primary flex-1 py-1.5 rounded-lg text-xs font-mono font-bold flex items-center justify-center gap-1.5 whitespace-nowrap tabular-nums"
                >
                  <RotateCcw className="w-3.5 h-3.5" />
                  <span>RETRY ALL ({failedLinks.length})</span>
                </button>
                <button
                  onClick={handleClearAllFailed}
                  className="machine-btn px-3 py-1.5 rounded-lg text-xs font-mono text-red-400 hover:text-red-300 flex items-center gap-1.5 whitespace-nowrap"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  <span>CLEAR ALL</span>
                </button>
              </div>
            )}

            <div className="p-3 overflow-y-auto space-y-2.5 flex-1 max-h-[55vh]">
              {failedLinks.length === 0 ? (
                <EmptyState
                  compact
                  tone="emerald"
                  icon={CheckCircle2}
                  title="NO FAILED LINKS"
                  message="All queued downloads completed without errors."
                />
              ) : (
                failedLinks.map((item) => (
                  <div
                    key={item.id}
                    className="p-3 rounded-xl bg-slate-900/80 border border-slate-800 hover:border-red-500/40 space-y-2 transition"
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-1.5 mb-1 whitespace-nowrap">
                          <span className="px-1.5 py-0.5 rounded text-[9px] font-mono font-bold bg-slate-800 text-slate-300 border border-slate-700 uppercase">
                            {item.platform || 'OTHER'}
                          </span>
                          <span className="text-[10px] font-mono text-slate-500 tabular-nums">
                            {item.attempts > 1 ? `${item.attempts} attempts • ` : ''}
                            {formatFailedTime(item.failedAt)}
                          </span>
                        </div>
                        <h4 className="font-mono text-xs font-bold text-white truncate" title={item.title}>
                          {item.title && item.title !== 'video' ? item.title : item.url}
                        </h4>
                        <div className="font-mono text-[10px] text-slate-400 truncate mt-0.5">
                          {item.url}
                        </div>
                      </div>
                    </div>

                    <div className="px-2.5 py-1.5 rounded bg-red-950/40 border border-red-500/30 text-red-300 text-[11px] font-mono flex items-center gap-1.5">
                      <AlertCircle className="w-3.5 h-3.5 text-red-400 shrink-0" />
                      <span className="truncate">{item.reason || 'Download failed'}</span>
                    </div>

                    <div className="flex items-center gap-2 pt-1">
                      <button
                        onClick={() => handleRetryFailedItem(item)}
                        className="machine-btn-primary flex-1 py-1 rounded-md text-xs font-mono font-bold flex items-center justify-center gap-1.5 whitespace-nowrap"
                      >
                        <RotateCcw className="w-3 h-3" />
                        <span>RETRY</span>
                      </button>
                      <button
                        onClick={() => handleCopyFailed(item)}
                        className="machine-btn px-2.5 py-1 rounded-md text-xs font-mono text-slate-300 hover:text-white flex items-center gap-1 whitespace-nowrap"
                        title="Copy link"
                      >
                        {copiedFailedId === item.id ? (
                          <>
                            <Check className="w-3 h-3 text-accent" />
                            <span className="text-accent">COPIED</span>
                          </>
                        ) : (
                          <>
                            <Copy className="w-3 h-3" />
                            <span>COPY LINK</span>
                          </>
                        )}
                      </button>
                      <button
                        onClick={() => handleDeleteFailed(item.id)}
                        className="machine-btn p-1.5 rounded-md text-slate-400 hover:text-red-400 shrink-0"
                        title="Delete entry"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      )}

      {/* MOBILE DATA SIZE ALERT MODAL */}
      {mobileDataConfirm && mobileDataConfirm.open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in">
          <div className="w-full max-w-sm rounded-2xl bg-[#090D15] border border-amber-500/70 p-5 shadow-[0_0_25px_rgba(245,158,11,0.25)] space-y-4">
            <div className="flex items-center justify-between pb-2 border-b border-amber-500/30">
              <div className="flex items-center gap-2 min-w-0">
                <span className="w-2.5 h-2.5 rounded-full led-amber shrink-0" />
                <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider truncate">
                  MOBILE DATA ALERT // LARGE FILE
                </h3>
              </div>
              <button
                onClick={() => setMobileDataConfirm(null)}
                className="p-1 text-slate-400 hover:text-white shrink-0"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <p className="font-mono text-xs text-slate-300 leading-relaxed">
              You are on mobile data. This file is{' '}
              <span className="text-amber-300 font-bold tabular-nums">
                {mobileDataConfirm.sizeMb.toFixed(1)} MB
              </span>{' '}
              (above your{' '}
              <span className="text-white font-bold tabular-nums">
                {settings.mobileDataPromptMb} MB
              </span>{' '}
              limit). Continue downloading?
            </p>

            <div className="flex items-center justify-end gap-2 pt-1">
              <button
                onClick={() => setMobileDataConfirm(null)}
                className="machine-btn px-4 py-1.5 rounded-lg font-mono text-xs text-slate-300 whitespace-nowrap"
              >
                CANCEL
              </button>
              <button
                onClick={() => {
                  const wasForce = mobileDataConfirm.force;
                  setMobileDataConfirm(null);
                  handleStartDownload(wasForce, true);
                }}
                className="machine-btn-primary px-4 py-1.5 rounded-lg font-mono text-xs font-bold whitespace-nowrap"
              >
                DOWNLOAD ANYWAY
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
