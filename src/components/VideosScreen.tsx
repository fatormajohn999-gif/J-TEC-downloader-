import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Play,
  Share2,
  Trash2,
  Search,
  Film,
  HardDrive,
  RefreshCw,
  Clock,
  ShieldAlert,
  FolderOpen,
  Sparkles,
} from 'lucide-react';
import { VideoLibraryItem, PlatformType } from '../types';
import { VideoDownloader } from '../plugins/videoDownloader';
import { Capacitor } from '@capacitor/core';

interface VideosScreenProps {
  onRefreshTrigger?: number;
}

export const VideosScreen: React.FC<VideosScreenProps> = ({ onRefreshTrigger }) => {
  const isNative = Capacitor.isNativePlatform();
  const [videos, setVideos] = useState<VideoLibraryItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [hasPermission, setHasPermission] = useState<boolean | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedPlatform, setSelectedPlatform] = useState<string>('all');
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  // Check permission and load videos from MediaStore
  const loadLibrary = useCallback(async () => {
    setIsLoading(true);
    try {
      if (isNative) {
        const perm = await VideoDownloader.hasVideoPermission();
        setHasPermission(perm.granted);
        if (!perm.granted) {
          setIsLoading(false);
          return;
        }
      } else {
        setHasPermission(true);
      }

      const res = await VideoDownloader.getVideoLibrary();
      if (res && Array.isArray(res.videos)) {
        setVideos(res.videos);
      }
    } catch (e) {
      console.warn('Failed to load video library', e);
    } finally {
      setIsLoading(false);
    }
  }, [isNative]);

  // Request permission
  const handleRequestPermission = async () => {
    try {
      await VideoDownloader.requestVideoPermission();
      // Re-check after prompt
      setTimeout(() => {
        loadLibrary();
      }, 500);
    } catch (e) {
      console.error(e);
    }
  };

  // Initial load
  useEffect(() => {
    loadLibrary();
  }, [loadLibrary, onRefreshTrigger]);

  // Listen to foreground/visibility events
  useEffect(() => {
    const handleFocus = () => loadLibrary();
    const handleVisibility = () => {
      if (document.visibilityState === 'visible') loadLibrary();
    };
    window.addEventListener('focus', handleFocus);
    document.addEventListener('visibilitychange', handleVisibility);
    return () => {
      window.removeEventListener('focus', handleFocus);
      document.removeEventListener('visibilitychange', handleVisibility);
    };
  }, [loadLibrary]);

  // Listen to nativeDownloadFinished
  useEffect(() => {
    let handle: any = null;
    (async () => {
      try {
        handle = await VideoDownloader.addListener('nativeDownloadFinished', () => {
          loadLibrary();
        });
      } catch {}
    })();
    return () => {
      if (handle && handle.remove) handle.remove();
    };
  }, [loadLibrary]);

  // Actions
  const handlePlay = async (video: VideoLibraryItem) => {
    try {
      await VideoDownloader.playVideo({
        uri: video.uri,
        title: video.title,
      });
    } catch (e) {
      console.error('Play error', e);
    }
  };

  const handleShare = async (video: VideoLibraryItem) => {
    try {
      await VideoDownloader.shareVideo({
        uri: video.uri,
        title: video.title,
      });
    } catch (e) {
      console.error('Share error', e);
    }
  };

  const handleDelete = async (video: VideoLibraryItem) => {
    setIsDeleting(true);
    try {
      await VideoDownloader.deleteVideo({ uri: video.uri });
      setVideos((prev) => prev.filter((v) => v.uri !== video.uri));
      setDeleteConfirmId(null);
    } catch (e) {
      alert('Could not delete video: ' + e);
    } finally {
      setIsDeleting(false);
    }
  };

  // Filtering
  const platforms = [
    { id: 'all', label: 'All' },
    { id: 'tiktok', label: 'TikTok' },
    { id: 'youtube', label: 'YouTube' },
    { id: 'instagram', label: 'Instagram' },
    { id: 'facebook', label: 'Facebook' },
  ];

  const filteredVideos = useMemo(() => {
    return videos.filter((v) => {
      const matchSearch =
        searchQuery.trim() === '' ||
        v.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
        v.platform.toLowerCase().includes(searchQuery.toLowerCase());
      const matchPlatform =
        selectedPlatform === 'all' || v.platform.toLowerCase() === selectedPlatform.toLowerCase();
      return matchSearch && matchPlatform;
    });
  }, [videos, searchQuery, selectedPlatform]);

  // Stats
  const totalSizeBytes = useMemo(() => {
    return videos.reduce((acc, v) => acc + (v.size || 0), 0);
  }, [videos]);

  const formatSize = (bytes: number) => {
    if (!bytes || bytes <= 0) return '0 MB';
    if (bytes >= 1024 * 1024 * 1024) {
      return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
    }
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  };

  const formatDuration = (ms: number) => {
    if (!ms || ms <= 0) return '--:--';
    const totalSecs = Math.floor(ms / 1000);
    const mins = Math.floor(totalSecs / 60);
    const secs = totalSecs % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  const formatDate = (timestamp: number) => {
    if (!timestamp) return '';
    const d = new Date(timestamp);
    return d.toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
    });
  };

  const getPlatformBadgeStyle = (platform: PlatformType | string) => {
    const p = platform.toLowerCase();
    switch (p) {
      case 'tiktok':
        return 'bg-pink-950/60 text-pink-300 border-pink-800/60';
      case 'youtube':
        return 'bg-red-950/60 text-red-300 border-red-800/60';
      case 'instagram':
        return 'bg-purple-950/60 text-purple-300 border-purple-800/60';
      case 'facebook':
        return 'bg-blue-950/60 text-blue-300 border-blue-800/60';
      default:
        return 'bg-slate-800 text-slate-300 border-slate-700';
    }
  };

  return (
    <div className="space-y-4 pb-28">
      {/* Web Fallback Notice */}
      {!isNative && (
        <div className="p-3.5 rounded-2xl bg-indigo-950/40 border border-indigo-800/60 flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-indigo-900/60 border border-indigo-700/60 flex items-center justify-center shrink-0 text-indigo-300">
            <Sparkles className="w-5 h-5" />
          </div>
          <div className="flex-1 min-w-0">
            <h3 className="text-xs font-bold text-white">Browser Preview Mode</h3>
            <p className="text-[11px] text-indigo-300/80">
              Your video library is available in the Android app (synced directly with Movies/OmniDrop).
            </p>
          </div>
        </div>
      )}

      {/* Permission Missing State */}
      {isNative && hasPermission === false && (
        <div className="p-6 rounded-2xl bg-slate-900/90 border border-amber-900/50 text-center space-y-4 shadow-xl">
          <div className="w-14 h-14 mx-auto rounded-2xl bg-amber-950/60 border border-amber-700/60 flex items-center justify-center text-amber-400">
            <ShieldAlert className="w-7 h-7" />
          </div>
          <div className="space-y-1">
            <h3 className="text-sm font-bold text-white">Video Permission Required</h3>
            <p className="text-xs text-slate-400 max-w-xs mx-auto">
              OmniDrop needs permission to access and play videos saved in your phone&apos;s Movies/OmniDrop folder.
            </p>
          </div>
          <button
            onClick={handleRequestPermission}
            className="w-full py-2.5 px-4 rounded-xl bg-cyan-600 hover:bg-cyan-500 active:scale-95 font-semibold text-xs text-slate-950 transition shadow-[0_0_15px_rgba(0,240,255,0.4)]"
          >
            Allow Access to My Videos
          </button>
        </div>
      )}

      {/* Header Info & Stats */}
      <div className="flex items-center justify-between pt-1">
        <div>
          <h2 className="text-base font-bold text-white flex items-center gap-2 tracking-wide font-mono">
            <Film className="w-4 h-4 text-cyan-400" />
            VIDEOS // LIBRARY
          </h2>
          <p className="text-xs text-slate-400 flex items-center gap-2 mt-0.5">
            <span>{videos.length} {videos.length === 1 ? 'video' : 'videos'}</span>
            <span className="w-1 h-1 rounded-full bg-slate-700" />
            <span className="flex items-center gap-1 font-mono text-[11px] text-cyan-400">
              <HardDrive className="w-3 h-3" />
              {formatSize(totalSizeBytes)}
            </span>
          </p>
        </div>

        <button
          onClick={loadLibrary}
          disabled={isLoading}
          className="p-2 rounded-xl bg-slate-900 border border-slate-800 text-slate-400 hover:text-cyan-400 transition active:scale-95 disabled:opacity-50"
          title="Refresh library"
        >
          <RefreshCw className={`w-4 h-4 ${isLoading ? 'animate-spin text-cyan-400' : ''}`} />
        </button>
      </div>

      {/* Search Input */}
      <div className="relative">
        <Search className="w-4 h-4 text-slate-500 absolute left-3.5 top-1/2 -translate-y-1/2" />
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="Search videos by title or platform..."
          className="w-full pl-10 pr-4 py-2 text-xs rounded-xl bg-slate-900 border border-slate-800 text-white placeholder-slate-500 focus:outline-none focus:border-cyan-500/80 focus:ring-1 focus:ring-cyan-500/50 transition font-sans"
        />
        {searchQuery && (
          <button
            onClick={() => setSearchQuery('')}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-slate-500 hover:text-slate-300"
          >
            ✕
          </button>
        )}
      </div>

      {/* Platform Filter Chips */}
      <div className="flex items-center gap-1.5 overflow-x-auto pb-1 no-scrollbar text-xs">
        {platforms.map((p) => {
          const isActive = selectedPlatform === p.id;
          return (
            <button
              key={p.id}
              onClick={() => setSelectedPlatform(p.id)}
              className={`px-3 py-1.5 rounded-lg border font-mono text-[11px] transition-all whitespace-nowrap active:scale-95 ${
                isActive
                  ? 'bg-cyan-950/80 text-cyan-300 border-cyan-500/70 shadow-[0_0_10px_rgba(0,240,255,0.2)] font-semibold'
                  : 'bg-slate-900/90 text-slate-400 border-slate-800 hover:text-slate-200 hover:border-slate-700'
              }`}
            >
              {p.label}
            </button>
          );
        })}
      </div>

      {/* Loading Skeleton */}
      {isLoading && videos.length === 0 && (
        <div className="space-y-3">
          {[1, 2, 3].map((i) => (
            <div
              key={i}
              className="p-3 rounded-2xl bg-slate-900/50 border border-slate-800 animate-pulse flex gap-3 items-center"
            >
              <div className="w-24 h-16 rounded-xl bg-slate-800/80 shrink-0" />
              <div className="flex-1 space-y-2">
                <div className="h-3.5 bg-slate-800 rounded w-3/4" />
                <div className="h-3 bg-slate-800/60 rounded w-1/2" />
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Empty State */}
      {!isLoading && filteredVideos.length === 0 && (
        <div className="p-8 text-center rounded-2xl bg-slate-900/60 border border-slate-800/80 space-y-3">
          <div className="w-14 h-14 mx-auto rounded-2xl bg-slate-800/80 border border-slate-700/60 flex items-center justify-center text-slate-500">
            <Film className="w-7 h-7 text-cyan-500/60" />
          </div>
          <div className="space-y-1">
            <h3 className="text-sm font-semibold text-slate-200">
              {searchQuery || selectedPlatform !== 'all' ? 'No matching videos' : 'No videos found in library'}
            </h3>
            <p className="text-xs text-slate-400 max-w-xs mx-auto">
              {searchQuery || selectedPlatform !== 'all'
                ? 'Try adjusting your search or platform filter.'
                : 'Videos downloaded with OmniDrop will automatically appear here and in your phone gallery (Movies/OmniDrop).'}
            </p>
          </div>
        </div>
      )}

      {/* Video Cards List */}
      <div className="space-y-3">
        {filteredVideos.map((video) => (
          <div
            key={video.id || video.uri}
            className="group relative p-3 rounded-2xl bg-slate-900/90 border border-slate-800 hover:border-cyan-500/40 transition-all duration-200 flex flex-col gap-2.5 shadow-lg"
          >
            <div className="flex gap-3 items-center">
              {/* Thumbnail with Play Overlay */}
              <button
                onClick={() => handlePlay(video)}
                className="relative w-24 h-16 rounded-xl overflow-hidden bg-slate-950 shrink-0 border border-slate-800 group-hover:border-cyan-500/50 transition-colors text-left"
              >
                {video.thumbnail ? (
                  <img
                    src={video.thumbnail}
                    alt={video.title}
                    className="w-full h-full object-cover"
                    loading="lazy"
                  />
                ) : (
                  <div className="w-full h-full flex items-center justify-center text-slate-700">
                    <Film className="w-6 h-6" />
                  </div>
                )}

                {/* Dark Vignette & Play Icon */}
                <div className="absolute inset-0 bg-black/40 flex items-center justify-center group-hover:bg-black/20 transition-all">
                  <div className="w-8 h-8 rounded-full bg-cyan-500/90 text-slate-950 flex items-center justify-center shadow-lg transition-transform group-hover:scale-110">
                    <Play className="w-4 h-4 fill-slate-950 translate-x-0.5" />
                  </div>
                </div>

                {/* Duration Overlay */}
                {video.durationMs > 0 && (
                  <span className="absolute bottom-1 right-1 px-1.5 py-0.5 rounded bg-black/80 font-mono text-[9px] text-white tracking-wider">
                    {formatDuration(video.durationMs)}
                  </span>
                )}
              </button>

              {/* Title & Info */}
              <div className="flex-1 min-w-0 pr-1">
                <h4
                  onClick={() => handlePlay(video)}
                  className="text-xs font-semibold text-white truncate cursor-pointer hover:text-cyan-300 transition"
                  title={video.title}
                >
                  {video.title}
                </h4>

                <div className="flex items-center gap-2 mt-1.5 flex-wrap">
                  <span
                    className={`capitalize px-2 py-0.5 rounded border text-[10px] font-mono tracking-wide ${getPlatformBadgeStyle(
                      video.platform
                    )}`}
                  >
                    {video.platform}
                  </span>

                  <span className="font-mono text-[11px] text-slate-400">
                    {formatSize(video.size)}
                  </span>

                  {video.dateAdded > 0 && (
                    <span className="flex items-center gap-1 text-[11px] text-slate-500">
                      <Clock className="w-3 h-3" />
                      {formatDate(video.dateAdded)}
                    </span>
                  )}
                </div>
              </div>
            </div>

            {/* Bottom Actions Row */}
            <div className="flex items-center justify-between pt-2 border-t border-slate-800/80">
              <button
                onClick={() => handlePlay(video)}
                className="flex items-center gap-1.5 text-xs font-mono font-semibold text-cyan-400 hover:text-cyan-300 px-3 py-1 rounded-lg bg-cyan-950/40 border border-cyan-800/40 transition active:scale-95"
              >
                <Play className="w-3.5 h-3.5 fill-cyan-400" />
                PLAY
              </button>

              <div className="flex items-center gap-1.5">
                <button
                  onClick={() => handleShare(video)}
                  className="flex items-center gap-1 text-xs text-slate-400 hover:text-white px-2.5 py-1 rounded-lg bg-slate-800 hover:bg-slate-700 transition active:scale-95"
                  title="Share video"
                >
                  <Share2 className="w-3.5 h-3.5" />
                  <span className="text-[11px]">Share</span>
                </button>

                {deleteConfirmId === video.uri ? (
                  <div className="flex items-center gap-1">
                    <button
                      onClick={() => handleDelete(video)}
                      disabled={isDeleting}
                      className="px-2.5 py-1 rounded-lg bg-rose-600 hover:bg-rose-500 text-white text-[11px] font-bold transition active:scale-95 disabled:opacity-50"
                    >
                      Confirm
                    </button>
                    <button
                      onClick={() => setDeleteConfirmId(null)}
                      className="px-2 py-1 rounded-lg bg-slate-800 text-slate-300 text-[11px] transition"
                    >
                      Cancel
                    </button>
                  </div>
                ) : (
                  <button
                    onClick={() => setDeleteConfirmId(video.uri)}
                    className="p-1.5 rounded-lg text-slate-500 hover:text-rose-400 hover:bg-rose-950/30 transition active:scale-95"
                    title="Delete video"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
