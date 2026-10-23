import React, { useState, useEffect } from 'react';
import {
  Clipboard,
  Download,
  Loader2,
  AlertCircle,
  Sparkles,
  CheckCircle2,
  Trash2,
  Play,
  RotateCcw,
  Check,
  Film,
  Music,
  ExternalLink,
} from 'lucide-react';
import { Capacitor } from '@capacitor/core';
import { VideoInfo, AppSettings, PlatformType, DownloadHistoryItem } from '../types';
import { isSupportedUrl, fetchVideoInfo, downloadVideoFile, wakeUpServerUntilReady, normalizeUrl } from '../services/api';
import { VideoDownloader } from '../plugins/videoDownloader';

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
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const [duplicateDialog, setDuplicateDialog] = useState<{
    open: boolean;
    uri: string;
    title: string;
    fileName: string;
  } | null>(null);

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

  const handleStartDownload = async (force: boolean = false) => {
    const targetUrl = videoInfo ? videoInfo.url : normalizeUrl(inputUrl.trim());
    if (!targetUrl) {
      setErrorMessage('Please paste or inspect a video URL first.');
      return;
    }

    // Check duplicate before starting download
    if (!force && isNative) {
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

    setIsDownloading(true);
    setDownloadProgress(10);
    setDownloadStatusText('Connecting to downloader...');
    setErrorMessage(null);
    setSuccessMessage(null);
    setDuplicateDialog(null);

    const titleToUse = videoInfo?.title || 'video';
    const uploaderToUse = videoInfo?.uploader || 'Creator';
    const thumbToUse = videoInfo?.thumbnail || '';
    const durationToUse = videoInfo?.duration || 0;
    const platformToUse = videoInfo?.platform || 'other';

    try {
      const fileName = await downloadVideoFile(
        targetUrl,
        selectedQuality,
        titleToUse,
        settings,
        (pct, text) => {
          setDownloadProgress(pct);
          setDownloadStatusText(text);
        }
      );

      try {
        await VideoDownloader.showDownloadNotification({
          title: 'Download Completed',
          message: `${titleToUse.substring(0, 40)} saved to gallery!`,
        });
      } catch {}

      onDownloadCompleted({
        url: targetUrl,
        title: titleToUse,
        thumbnail: thumbToUse,
        uploader: uploaderToUse,
        duration: durationToUse,
        quality: selectedQuality,
        platform: platformToUse,
        fileName,
      });

      setSuccessMessage(`SAVED: ${fileName}`);
    } catch (err: any) {
      setErrorMessage(err.message || 'Download failed. Please check network/backend.');
    } finally {
      setIsDownloading(false);
    }
  };

  const handlePlayExisting = async (uri: string, title: string) => {
    try {
      await VideoDownloader.playVideo({ uri, title });
    } catch (e: any) {
      alert(`Play notice: ${e?.message || 'Player available in Android build.'}`);
    }
  };

  // Render 16 segmented progress blocks
  const renderSegmentedProgress = () => {
    const totalBlocks = 16;
    const filledBlocks = Math.round((downloadProgress / 100) * totalBlocks);

    return (
      <div className="space-y-1.5 pt-1">
        <div className="flex items-center justify-between font-mono text-[11px]">
          <span className="text-cyan-400 font-bold">{downloadProgress}%</span>
          <span className="text-slate-400 truncate max-w-[200px]">{downloadStatusText}</span>
        </div>
        <div className="grid grid-cols-16 gap-1 p-1 bg-slate-950 rounded-md border border-cyan-500/30">
          {Array.from({ length: totalBlocks }).map((_, i) => (
            <div
              key={i}
              className={`h-2.5 rounded-xs transition-colors duration-150 ${
                i < filledBlocks
                  ? 'bg-cyan-400 led-cyan shadow-[0_0_6px_rgba(0,240,255,0.8)]'
                  : 'bg-slate-900 border border-slate-800'
              }`}
            />
          ))}
        </div>
      </div>
    );
  };

  return (
    <div className="space-y-4 pb-28">
      {/* Backend Required Warning if unconfigured */}
      {isBackendMissingOnNative && (
        <div className="machine-surface p-3.5 rounded-xl border border-amber-500/60 text-amber-200 space-y-2">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full led-amber" />
            <h4 className="text-xs font-mono font-bold uppercase tracking-wider text-white">
              CONFIGURATION REQUIRED // RENDER BACKEND
            </h4>
          </div>
          <p className="text-xs text-amber-200/90 leading-relaxed font-sans">
            Please configure your Render Backend URL in Settings before downloading on mobile.
          </p>
          {onOpenSettings && (
            <button
              onClick={onOpenSettings}
              className="machine-btn px-3 py-1 rounded-lg text-xs font-mono font-bold text-cyan-300"
            >
              GOTO // SETTINGS
            </button>
          )}
        </div>
      )}

      {/* Duplicate Alert Dialog */}
      {duplicateDialog && duplicateDialog.open && (
        <div className="machine-surface p-4 rounded-xl border border-cyan-400/80 space-y-3 animate-fade-in shadow-[0_0_20px_rgba(0,240,255,0.25)]">
          <div className="flex items-center gap-2">
            <span className="w-2.5 h-2.5 rounded-full led-cyan" />
            <h3 className="font-mono text-xs font-bold uppercase tracking-wider text-white">
              ALREADY DOWNLOADED // FILE EXISTS
            </h3>
          </div>
          <p className="text-xs text-slate-300 font-sans">
            This video was already downloaded and is stored in your gallery:
          </p>
          <div className="font-mono text-[11px] text-cyan-300 p-2 rounded bg-slate-950 border border-slate-800 truncate">
            {duplicateDialog.fileName || duplicateDialog.title}
          </div>
          <div className="flex items-center gap-2 pt-1">
            <button
              onClick={() => handlePlayExisting(duplicateDialog.uri, duplicateDialog.title)}
              className="machine-btn-primary flex-1 py-2 rounded-lg font-mono text-xs uppercase flex items-center justify-center gap-1.5"
            >
              <Play className="w-3.5 h-3.5 fill-current" />
              <span>PLAY NOW</span>
            </button>
            <button
              onClick={() => handleStartDownload(true)}
              className="machine-btn flex-1 py-2 rounded-lg font-mono text-xs text-slate-300 uppercase flex items-center justify-center gap-1.5"
            >
              <RotateCcw className="w-3.5 h-3.5" />
              <span>DOWNLOAD AGAIN</span>
            </button>
          </div>
        </div>
      )}

      {/* HERO CARD: PASTE LINK // INGESTION UNIT */}
      <div className="machine-surface rounded-xl p-4 space-y-3.5">
        <div className="flex items-center justify-between pb-2 border-b border-cyan-500/20">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full led-cyan" />
            <h3 className="font-mono text-xs font-bold uppercase tracking-wider text-white">
              PASTE LINK // INGESTION UNIT
            </h3>
          </div>
          <span className="font-mono text-[10px] text-slate-400 uppercase">TIKTOK • YT • IG • FB</span>
        </div>

        {/* Input & Paste Key */}
        <div className="space-y-2.5">
          <div className="flex items-center gap-2">
            <div className="relative flex-1">
              <input
                type="text"
                value={inputUrl}
                onChange={(e) => setInputUrl(e.target.value)}
                placeholder="https://tiktok.com/@... or youtu.be/..."
                className="w-full rounded-lg bg-slate-950 border border-slate-800 focus:border-cyan-400 focus:ring-1 focus:ring-cyan-400 px-3 py-2.5 text-xs font-mono text-slate-200 placeholder:text-slate-600 outline-none transition"
              />
              {inputUrl && (
                <button
                  onClick={handleClear}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-500 hover:text-slate-300 font-mono text-xs"
                >
                  CLEAR
                </button>
              )}
            </div>

            {/* Big Mechanical Paste Key */}
            <button
              onClick={handlePaste}
              className="machine-btn px-3.5 py-2.5 rounded-lg flex items-center gap-1.5 text-xs font-mono font-bold text-cyan-300 shrink-0"
              title="Paste link from clipboard"
            >
              <Clipboard className="w-3.5 h-3.5 text-cyan-400" />
              <span>PASTE</span>
            </button>
          </div>

          {/* Quality Selector Segmented Switch */}
          <div>
            <div className="text-[10px] font-mono text-slate-400 mb-1 uppercase tracking-wider">
              STREAM QUALITY // ENCODE
            </div>
            <div className="grid grid-cols-4 gap-1 p-1 bg-slate-950 rounded-lg border border-slate-800">
              {QUALITY_OPTIONS.map((q) => {
                const isSelected = selectedQuality === q.id;
                return (
                  <button
                    key={q.id}
                    onClick={() => setSelectedQuality(q.id)}
                    className={`py-1.5 rounded text-center font-mono text-xs font-bold transition-all ${
                      isSelected
                        ? 'machine-btn-primary text-slate-950 shadow-[0_0_10px_rgba(0,240,255,0.5)]'
                        : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {q.label}
                  </button>
                );
              })}
            </div>
          </div>

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
            className="machine-btn-primary w-full py-3 rounded-lg flex items-center justify-center gap-2 text-xs uppercase tracking-wider font-mono disabled:opacity-50"
          >
            {isLoadingInfo ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                <span>INSPECTING STREAM...</span>
              </>
            ) : isDownloading ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                <span>DOWNLOADING...</span>
              </>
            ) : (
              <>
                <Download className="w-4 h-4" />
                <span>{videoInfo ? 'DOWNLOAD VIDEO FILE' : 'INSPECT & DOWNLOAD'}</span>
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

      {/* POST-FETCH VIDEO CARD WITH "NO WATERMARK" LED BADGE */}
      {videoInfo && (
        <div className="machine-surface rounded-xl p-4 space-y-3 animate-fade-in">
          <div className="flex items-center justify-between pb-2 border-b border-cyan-500/20">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full led-emerald" />
              <h4 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
                VIDEO TARGET LOCATED
              </h4>
            </div>

            {/* "NO WATERMARK" LED BADGE */}
            <div className="flex items-center gap-1.5 px-2 py-0.5 rounded bg-emerald-950/80 border border-emerald-500/40 font-mono text-[10px] text-emerald-300 font-bold">
              <span className="w-1.5 h-1.5 rounded-full led-emerald" />
              <span>NO WATERMARK</span>
            </div>
          </div>

          <div className="flex gap-3">
            {/* Thumbnail */}
            <div className="relative w-24 h-24 rounded-lg overflow-hidden bg-slate-950 border border-slate-800 shrink-0">
              {videoInfo.thumbnail ? (
                <img
                  src={videoInfo.thumbnail}
                  alt={videoInfo.title}
                  className="w-full h-full object-cover"
                />
              ) : (
                <div className="w-full h-full flex items-center justify-center text-slate-600">
                  <Film className="w-8 h-8" />
                </div>
              )}
              {videoInfo.duration > 0 && (
                <span className="absolute bottom-1 right-1 px-1 py-0.2 rounded bg-black/80 font-mono text-[9px] text-white">
                  {formatDuration(videoInfo.duration)}
                </span>
              )}
            </div>

            {/* Info details */}
            <div className="flex-1 min-w-0 space-y-1">
              <h5 className="font-mono font-bold text-xs text-white line-clamp-2 leading-snug">
                {videoInfo.title}
              </h5>
              <p className="font-mono text-[11px] text-cyan-400 truncate">
                @{videoInfo.uploader || 'Creator'}
              </p>
              <div className="flex items-center gap-2 pt-1 font-mono text-[10px] text-slate-400">
                <span className="uppercase px-1.5 py-0.2 rounded bg-slate-950 border border-slate-800 text-slate-300">
                  {videoInfo.platform}
                </span>
                <span>{selectedQuality.toUpperCase()}</span>
              </div>
            </div>
          </div>

          {/* Segmented Progress Blocks when downloading */}
          {isDownloading && renderSegmentedProgress()}
        </div>
      )}

      {/* RECENT DOWNLOADS (LAST 3) WITH PLAY BUTTONS */}
      {recentDownloads && recentDownloads.length > 0 && (
        <div className="machine-surface rounded-xl p-3.5 space-y-2.5">
          <div className="flex items-center justify-between pb-1 border-b border-slate-800">
            <span className="font-mono text-xs font-bold text-slate-300 uppercase tracking-wider">
              RECENT DOWNLOADS // QUICK PLAY
            </span>
            <span className="font-mono text-[10px] text-slate-500">LAST 3</span>
          </div>

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
                    <div className="font-mono text-[10px] text-slate-400 flex items-center gap-2">
                      <span className="text-cyan-400 uppercase">{item.platform}</span>
                      <span>•</span>
                      <span>{item.quality.toUpperCase()}</span>
                    </div>
                  </div>
                </div>

                <button
                  onClick={() => handlePlayExisting(item.fileUri || item.url, item.title)}
                  className="machine-btn px-2.5 py-1.5 rounded-md font-mono text-[11px] text-cyan-300 flex items-center gap-1 shrink-0"
                >
                  <Play className="w-3 h-3 fill-current text-cyan-400" />
                  <span>PLAY</span>
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* BOTTOM STATUS STRIP: SERVER LED & NET MS */}
      <div className="machine-surface rounded-lg px-3 py-2 flex items-center justify-between text-xs font-mono">
        <div className="flex items-center gap-2">
          <span
            className={`w-2 h-2 rounded-full ${
              serverStatus === 'connected'
                ? serverLatency && serverLatency > 3000
                  ? 'led-amber'
                  : 'led-emerald'
                : serverStatus === 'checking'
                ? 'led-amber'
                : 'led-rose'
            }`}
          />
          <span className="text-slate-300">
            SERVER:{' '}
            {serverStatus === 'connected'
              ? serverLatency && serverLatency > 3000
                ? 'SLEEPING'
                : 'ONLINE'
              : serverStatus === 'checking'
              ? 'PINGING'
              : 'OFFLINE'}
          </span>
        </div>

        <div className="text-slate-400">
          NET:{' '}
          <span className="text-cyan-400 font-bold">
            {serverLatency !== null ? `${serverLatency}ms` : '--'}
          </span>
        </div>
      </div>
    </div>
  );
};
