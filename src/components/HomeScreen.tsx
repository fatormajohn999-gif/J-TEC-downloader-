import React, { useState } from 'react';
import {
  Clipboard,
  Download,
  Loader2,
  AlertCircle,
  Sparkles,
  CheckCircle2,
  Trash2,
  ExternalLink,
  ShieldCheck,
  Video,
  Music,
  Share2,
} from 'lucide-react';
import { Capacitor } from '@capacitor/core';
import { VideoInfo, AppSettings, PlatformType } from '../types';
import { isSupportedUrl, fetchVideoInfo, downloadVideoFile, wakeUpServerUntilReady } from '../services/api';
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
}

export const HomeScreen: React.FC<HomeScreenProps> = ({
  settings,
  onDownloadCompleted,
  pendingUrl,
  onClearPendingUrl,
  onWakeServerStart,
  onWakeServerEnd,
  onOpenSettings,
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

  // Sync if pendingUrl was passed from Share Target or Clipboard detector
  React.useEffect(() => {
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
        setInputUrl(text.trim());
        if (isSupportedUrl(text.trim())) {
          inspectUrl(text.trim());
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
  };

  const inspectUrl = async (targetUrl?: string) => {
    const url = (targetUrl || inputUrl).trim();
    if (!url) {
      setErrorMessage('Please paste or type a video link.');
      return;
    }

    if (!isSupportedUrl(url)) {
      setErrorMessage('Unsupported link. Please paste a URL from TikTok, YouTube, Instagram, or Facebook.');
      return;
    }

    if (isBackendMissingOnNative) {
      setErrorMessage('Please set your Render Backend URL in Settings instead of calling /api.');
      return;
    }

    setErrorMessage(null);
    setSuccessMessage(null);
    setIsLoadingInfo(true);

    try {
      const info = await fetchVideoInfo(url, settings);
      setVideoInfo(info);
      // Auto-select quality if available
      if (settings.defaultQuality && info.qualities.includes(settings.defaultQuality)) {
        setSelectedQuality(settings.defaultQuality);
      } else if (info.qualities.includes('1080p')) {
        setSelectedQuality('1080p');
      } else if (info.qualities.includes('720p')) {
        setSelectedQuality('720p');
      } else {
        setSelectedQuality(info.qualities[0] || 'best');
      }
    } catch (err: any) {
      const msg = err.message || 'Could not inspect video';
      // If server took too long, attempt automatic wake-up
      if (msg.includes('waking up') || msg.includes('timeout') || msg.includes('Failed to fetch')) {
        onWakeServerStart();
        const wokeUp = await wakeUpServerUntilReady(settings);
        onWakeServerEnd();
        if (wokeUp) {
          // Retry
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

  const handleStartDownload = async () => {
    if (!videoInfo) return;

    setIsDownloading(true);
    setDownloadProgress(5);
    setDownloadStatusText('Connecting to downloader...');
    setErrorMessage(null);
    setSuccessMessage(null);

    try {
      const fileName = await downloadVideoFile(
        videoInfo.url,
        selectedQuality,
        videoInfo.title,
        settings,
        (pct, text) => {
          setDownloadProgress(pct);
          setDownloadStatusText(text);
        }
      );

      // Trigger native notification if available
      try {
        await VideoDownloader.showDownloadNotification({
          title: 'Video Downloaded',
          message: `${videoInfo.title.substring(0, 40)} saved!`,
        });
      } catch {
        // Native notification optional
      }

      // Record in history
      onDownloadCompleted({
        url: videoInfo.url,
        title: videoInfo.title,
        thumbnail: videoInfo.thumbnail,
        uploader: videoInfo.uploader,
        duration: videoInfo.duration,
        quality: selectedQuality,
        platform: videoInfo.platform,
        fileName,
      });

      setSuccessMessage(`Saved to device: ${fileName}`);
    } catch (err: any) {
      setErrorMessage(err.message || 'Download failed. Please try again.');
    } finally {
      setIsDownloading(false);
    }
  };

  return (
    <div className="space-y-5 pb-24">
      {/* Hero Badge */}
      <div className="text-center pt-2 pb-1">
        <div className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-slate-900 border border-slate-800 text-xs text-slate-300 shadow-inner">
          <Sparkles className="w-3.5 h-3.5 text-indigo-400" />
          <span>Full Quality • No Watermark • Direct Save</span>
        </div>
      </div>

      {/* Native Render URL Required Notice */}
      {isBackendMissingOnNative && (
        <div className="p-4 rounded-2xl bg-amber-950/70 border border-amber-600/60 shadow-xl text-amber-200 space-y-2.5 animate-fade-in">
          <div className="flex items-center gap-2">
            <AlertCircle className="w-4 h-4 text-amber-400 shrink-0" />
            <h4 className="text-xs font-bold text-white tracking-wide">
              Render Backend URL Required on Mobile
            </h4>
          </div>
          <p className="text-xs text-amber-200/90 leading-relaxed">
            Running on native device. Please set your Render Backend URL in <strong>Settings</strong> instead of calling local <code>/api</code>.
          </p>
          {onOpenSettings && (
            <button
              onClick={onOpenSettings}
              className="py-1.5 px-3.5 rounded-xl bg-amber-500 hover:bg-amber-400 text-slate-950 font-bold text-xs transition active:scale-95"
            >
              Open Settings
            </button>
          )}
        </div>
      )}

      {/* Main Input Box Card */}
      <div className="rounded-2xl bg-gradient-to-b from-slate-900 to-slate-900/80 border border-slate-800 p-4 shadow-xl">
        <label className="block text-xs font-medium text-slate-400 mb-2">
          Paste Social Video Link
        </label>
        <div className="relative">
          <textarea
            rows={2}
            value={inputUrl}
            onChange={(e) => setInputUrl(e.target.value)}
            placeholder="https://www.tiktok.com/@... or youtube.com/watch?v=..."
            className="w-full rounded-xl bg-slate-950 border border-slate-800 p-3 pr-20 text-xs font-mono text-slate-200 placeholder:text-slate-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 resize-none transition"
          />

          <div className="absolute right-2 top-2 flex flex-col gap-1.5">
            {inputUrl ? (
              <button
                onClick={handleClear}
                className="p-1.5 rounded-lg bg-slate-800 text-slate-400 hover:text-white transition"
                title="Clear input"
              >
                <Trash2 className="w-3.5 h-3.5" />
              </button>
            ) : (
              <button
                onClick={handlePaste}
                className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-indigo-600/30 text-indigo-300 border border-indigo-500/40 hover:bg-indigo-600/50 text-[11px] font-medium transition active:scale-95"
              >
                <Clipboard className="w-3 h-3" />
                Paste
              </button>
            )}
          </div>
        </div>

        {/* Action Button */}
        <div className="mt-3 flex gap-2">
          <button
            onClick={() => inspectUrl()}
            disabled={isLoadingInfo || !inputUrl.trim()}
            className="flex-1 py-3 px-4 rounded-xl bg-gradient-to-r from-indigo-600 via-purple-600 to-pink-600 hover:from-indigo-500 hover:to-pink-500 disabled:opacity-50 text-white font-medium text-xs flex items-center justify-center gap-2 shadow-lg shadow-indigo-600/25 transition active:scale-98"
          >
            {isLoadingInfo ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                <span>Inspecting Video...</span>
              </>
            ) : (
              <>
                <Download className="w-4 h-4" />
                <span>Fetch Video & Qualities</span>
              </>
            )}
          </button>
        </div>

        {/* Supported Platforms Pills */}
        <div className="mt-4 pt-3 border-t border-slate-800/80 flex items-center justify-between text-[11px] text-slate-400">
          <span className="text-slate-400">Supported:</span>
          <div className="flex items-center gap-2 font-medium">
            <span className="px-2 py-0.5 rounded-md bg-slate-800/90 text-slate-300">TikTok</span>
            <span className="px-2 py-0.5 rounded-md bg-slate-800/90 text-slate-300">YouTube</span>
            <span className="px-2 py-0.5 rounded-md bg-slate-800/90 text-slate-300">Instagram</span>
            <span className="px-2 py-0.5 rounded-md bg-slate-800/90 text-slate-300">Facebook</span>
          </div>
        </div>
      </div>

      {/* Error Message Toast */}
      {errorMessage && (
        <div className="p-3.5 rounded-xl bg-rose-950/70 border border-rose-800/80 flex items-start gap-2.5 text-xs text-rose-200 animate-shake">
          <AlertCircle className="w-4 h-4 text-rose-400 shrink-0 mt-0.5" />
          <div className="flex-1">
            <p className="font-medium">{errorMessage}</p>
          </div>
        </div>
      )}

      {/* Success Message Toast */}
      {successMessage && (
        <div className="p-3.5 rounded-xl bg-emerald-950/70 border border-emerald-800/80 flex items-center gap-2.5 text-xs text-emerald-200">
          <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
          <p className="font-medium flex-1">{successMessage}</p>
        </div>
      )}

      {/* Video Preview Card */}
      {videoInfo && (
        <div className="rounded-2xl bg-slate-900 border border-slate-800 p-4 shadow-2xl space-y-4 animate-fade-in">
          {/* Thumbnail & Badges */}
          <div className="relative aspect-video rounded-xl overflow-hidden bg-slate-950 border border-slate-800 group">
            {videoInfo.thumbnail ? (
              <img
                src={videoInfo.thumbnail}
                alt={videoInfo.title}
                className="w-full h-full object-cover"
                loading="lazy"
              />
            ) : (
              <div className="w-full h-full flex items-center justify-center text-slate-600">
                <Video className="w-12 h-12" />
              </div>
            )}

            {/* Duration pill */}
            {videoInfo.duration > 0 && (
              <div className="absolute bottom-2 right-2 px-2 py-0.5 rounded-md bg-black/80 backdrop-blur-md text-[11px] font-mono text-white">
                {formatDuration(videoInfo.duration)}
              </div>
            )}

            {/* Platform & Watermark Badge */}
            <div className="absolute top-2 left-2 flex items-center gap-1.5">
              <span className="px-2 py-0.5 rounded-md bg-indigo-600/90 text-white font-semibold text-[10px] uppercase tracking-wide backdrop-blur-md">
                {videoInfo.platform}
              </span>
              {videoInfo.platform === 'tiktok' && (
                <span className="px-2 py-0.5 rounded-md bg-emerald-600/90 text-white font-medium text-[10px] flex items-center gap-1 backdrop-blur-md">
                  <ShieldCheck className="w-3 h-3" />
                  No Watermark
                </span>
              )}
            </div>
          </div>

          {/* Details */}
          <div>
            <h3 className="font-semibold text-sm text-white line-clamp-2 leading-snug">
              {videoInfo.title}
            </h3>
            <p className="text-xs text-slate-400 mt-1 flex items-center gap-1.5">
              <span>By {videoInfo.uploader}</span>
            </p>
          </div>

          {/* Quality Picker */}
          <div>
            <label className="block text-xs font-medium text-slate-400 mb-2">
              Select Output Quality:
            </label>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
              {videoInfo.qualities.map((q) => {
                const isAudio = q === 'audio';
                const isSelected = selectedQuality === q;
                return (
                  <button
                    key={q}
                    type="button"
                    onClick={() => setSelectedQuality(q)}
                    className={`py-2 px-3 rounded-xl border text-xs font-medium flex items-center justify-center gap-1.5 transition active:scale-95 ${
                      isSelected
                        ? 'bg-indigo-600 border-indigo-500 text-white shadow-md shadow-indigo-600/30'
                        : 'bg-slate-950 border-slate-800 text-slate-300 hover:border-slate-700'
                    }`}
                  >
                    {isAudio ? <Music className="w-3.5 h-3.5" /> : <Video className="w-3.5 h-3.5" />}
                    <span>{isAudio ? 'MP3 Audio' : q.toUpperCase()}</span>
                  </button>
                );
              })}
            </div>
          </div>

          {/* Download Progress Bar */}
          {isDownloading && (
            <div className="space-y-1.5 p-3 rounded-xl bg-slate-950 border border-slate-800">
              <div className="flex items-center justify-between text-xs">
                <span className="text-slate-300 font-medium flex items-center gap-1.5">
                  <Loader2 className="w-3.5 h-3.5 animate-spin text-indigo-400" />
                  {downloadStatusText}
                </span>
                <span className="font-mono text-indigo-400 font-semibold">{downloadProgress}%</span>
              </div>
              <div className="w-full h-2 rounded-full bg-slate-800 overflow-hidden">
                <div
                  className="h-full bg-gradient-to-r from-indigo-500 via-purple-500 to-pink-500 transition-all duration-300"
                  style={{ width: `${downloadProgress}%` }}
                />
              </div>
            </div>
          )}

          {/* Download Button */}
          <button
            onClick={handleStartDownload}
            disabled={isDownloading}
            className="w-full py-3.5 px-4 rounded-xl bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-500 disabled:opacity-50 text-white font-semibold text-xs flex items-center justify-center gap-2 shadow-lg shadow-emerald-600/25 transition active:scale-98"
          >
            {isDownloading ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                <span>Downloading Media...</span>
              </>
            ) : (
              <>
                <Download className="w-4 h-4" />
                <span>Save Video to Phone</span>
              </>
            )}
          </button>
        </div>
      )}

      {/* Feature Highlights Grid */}
      <div className="grid grid-cols-2 gap-3 pt-2">
        <div className="p-3.5 rounded-xl bg-slate-900/60 border border-slate-800/80 space-y-1">
          <div className="text-indigo-400 mb-1">
            <ShieldCheck className="w-5 h-5" />
          </div>
          <h4 className="text-xs font-semibold text-white">Clean Watermark Removal</h4>
          <p className="text-[11px] text-slate-400 leading-tight">
            Original high-bitrate video streams extracted directly without visual logos.
          </p>
        </div>

        <div className="p-3.5 rounded-xl bg-slate-900/60 border border-slate-800/80 space-y-1">
          <div className="text-purple-400 mb-1">
            <Share2 className="w-5 h-5" />
          </div>
          <h4 className="text-xs font-semibold text-white">Direct Android Share</h4>
          <p className="text-[11px] text-slate-400 leading-tight">
            Tap &ldquo;Share&rdquo; in TikTok, YouTube or IG to auto-launch OmniDrop.
          </p>
        </div>
      </div>
    </div>
  );
};
