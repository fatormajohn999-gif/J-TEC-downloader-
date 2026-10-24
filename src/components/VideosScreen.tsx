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
  Sparkles,
  CopyCheck,
  Edit3,
  Layers,
  Check,
  X,
  MoreVertical,
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
  const [activeMenuId, setActiveMenuId] = useState<string | null>(null);

  // Duplicates modal state
  const [duplicateModalOpen, setDuplicateModalOpen] = useState(false);
  const [duplicateGroups, setDuplicateGroups] = useState<{
    key: string;
    videos: VideoLibraryItem[];
    keepVideo: VideoLibraryItem;
    extraVideos: VideoLibraryItem[];
  }[]>([]);
  const [isDeletingDuplicates, setIsDeletingDuplicates] = useState(false);

  // Rename modal state
  const [renameModalVideo, setRenameModalVideo] = useState<VideoLibraryItem | null>(null);
  const [newTitle, setNewTitle] = useState('');
  const [newPlatform, setNewPlatform] = useState<string>('Other');
  const [isRenaming, setIsRenaming] = useState(false);

  // Close 3-dot menu when clicking outside
  useEffect(() => {
    const handleGlobalClick = () => setActiveMenuId(null);
    window.addEventListener('click', handleGlobalClick);
    return () => window.removeEventListener('click', handleGlobalClick);
  }, []);

  // Load videos from MediaStore
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

  const handleRequestPermission = async () => {
    try {
      await VideoDownloader.requestVideoPermission();
      setTimeout(loadLibrary, 500);
    } catch (e) {
      console.error(e);
    }
  };

  useEffect(() => {
    loadLibrary();
  }, [loadLibrary, onRefreshTrigger]);

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

  const handlePlay = async (video: VideoLibraryItem) => {
    try {
      const initialIndex = filteredVideos.findIndex((v) => v.uri === video.uri);
      const playlist = filteredVideos.map((v) => ({
        uri: v.uri,
        title: v.title,
        platform: v.platform,
      }));
      await VideoDownloader.playVideo({
        uri: video.uri,
        title: video.title,
        playlist,
        initialIndex: initialIndex >= 0 ? initialIndex : 0,
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

  const handleFindDuplicates = () => {
    const map = new Map<string, VideoLibraryItem[]>();
    for (const v of videos) {
      if (v.size > 0) {
        const durSec = Math.round((v.durationMs || 0) / 1000);
        const groupKey = `${v.size}_${durSec}`;
        const list = map.get(groupKey) || [];
        list.push(v);
        map.set(groupKey, list);
      }
    }

    const groups: {
      key: string;
      videos: VideoLibraryItem[];
      keepVideo: VideoLibraryItem;
      extraVideos: VideoLibraryItem[];
    }[] = [];

    for (const [key, list] of map.entries()) {
      if (list.length > 1) {
        const sorted = [...list].sort((a, b) => (a.dateAdded || 0) - (b.dateAdded || 0));
        groups.push({
          key,
          videos: sorted,
          keepVideo: sorted[0],
          extraVideos: sorted.slice(1),
        });
      }
    }

    setDuplicateGroups(groups);
    setDuplicateModalOpen(true);
  };

  const handleDeleteDuplicates = async () => {
    setIsDeletingDuplicates(true);
    try {
      const extraVideos = duplicateGroups.flatMap((g) => g.extraVideos);
      for (const v of extraVideos) {
        await VideoDownloader.deleteVideo({ uri: v.uri });
      }
      const removedUris = new Set(extraVideos.map((v) => v.uri));
      setVideos((prev) => prev.filter((v) => !removedUris.has(v.uri)));
      setDuplicateModalOpen(false);
      setDuplicateGroups([]);
    } catch (e) {
      alert('Error deleting duplicate copies: ' + e);
    } finally {
      setIsDeletingDuplicates(false);
    }
  };

  const handleOpenRename = (video: VideoLibraryItem) => {
    setRenameModalVideo(video);
    setNewTitle(video.title);
    setNewPlatform(video.platform || 'Other');
    setActiveMenuId(null);
  };

  const handleSaveRename = async () => {
    if (!renameModalVideo) return;
    const trimmedTitle = newTitle.trim();
    if (!trimmedTitle) return;

    setIsRenaming(true);
    try {
      const res = await VideoDownloader.renameVideo({
        uri: renameModalVideo.uri,
        newTitle: trimmedTitle,
        newPlatform: newPlatform || 'Other',
      });
      if (res && res.success) {
        setVideos((prev) =>
          prev.map((v) =>
            v.uri === renameModalVideo.uri
              ? { ...v, title: res.title || trimmedTitle, platform: (res.platform || newPlatform) as PlatformType }
              : v
          )
        );
      }
      setRenameModalVideo(null);
    } catch (e) {
      alert('Failed to rename video: ' + e);
    } finally {
      setIsRenaming(false);
    }
  };

  const filteredVideos = useMemo(() => {
    return videos.filter((v) => {
      const matchSearch =
        searchQuery.trim() === '' ||
        v.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (v.platform && v.platform.toLowerCase().includes(searchQuery.toLowerCase()));

      const matchPlatform =
        selectedPlatform === 'all' ||
        (v.platform && v.platform.toLowerCase() === selectedPlatform.toLowerCase()) ||
        (selectedPlatform === 'other' &&
          (!v.platform ||
            !['tiktok', 'youtube', 'instagram', 'facebook'].includes(v.platform.toLowerCase())));

      return matchSearch && matchPlatform;
    });
  }, [videos, searchQuery, selectedPlatform]);

  const totalSizeBytes = useMemo(() => {
    return videos.reduce((acc, v) => acc + (v.size || 0), 0);
  }, [videos]);

  const platforms = [
    { id: 'all', label: 'ALL' },
    { id: 'tiktok', label: 'TIKTOK' },
    { id: 'youtube', label: 'YOUTUBE' },
    { id: 'instagram', label: 'INSTAGRAM' },
    { id: 'facebook', label: 'FACEBOOK' },
    { id: 'other', label: 'OTHER' },
  ];

  const formatSize = (bytes: number) => {
    if (!bytes) return '0 B';
    const mb = bytes / (1024 * 1024);
    if (mb < 1) {
      return `${(bytes / 1024).toFixed(1)} KB`;
    }
    if (mb >= 1024) {
      return `${(mb / 1024).toFixed(2)} GB`;
    }
    return `${mb.toFixed(1)} MB`;
  };

  const formatDuration = (ms: number) => {
    if (!ms) return '0:00';
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

  // Colored Platform Chips as specified:
  // TikTok cyan/pink, YouTube red, Instagram purple/orange, Facebook blue, Other gray
  const getPlatformBadgeStyle = (platform: PlatformType | string) => {
    const p = (platform || 'other').toLowerCase();
    switch (p) {
      case 'tiktok':
        return 'bg-gradient-to-r from-cyan-950 to-pink-950 text-cyan-300 border-cyan-400/60 shadow-[0_0_8px_rgba(0,240,255,0.25)]';
      case 'youtube':
        return 'bg-red-950 text-red-400 border-red-600/70 shadow-[0_0_8px_rgba(239,68,68,0.25)]';
      case 'instagram':
        return 'bg-gradient-to-r from-purple-950 to-amber-950 text-purple-300 border-purple-500/60 shadow-[0_0_8px_rgba(168,85,247,0.25)]';
      case 'facebook':
        return 'bg-blue-950 text-blue-400 border-blue-500/70 shadow-[0_0_8px_rgba(59,130,246,0.25)]';
      default:
        return 'bg-slate-900 text-slate-400 border-slate-700';
    }
  };

  const totalExtraDuplicates = useMemo(() => {
    return duplicateGroups.reduce((acc, g) => acc + g.extraVideos.length, 0);
  }, [duplicateGroups]);

  return (
    <div className="space-y-4 pb-28">
      {/* Web Notice if in browser */}
      {!isNative && (
        <div className="machine-surface p-3 rounded-xl flex items-center gap-2.5 text-xs text-slate-300">
          <Sparkles className="w-4 h-4 text-cyan-400 shrink-0" />
          <span>Your video library is available in the Android app (synced directly with Movies/OmniDrop).</span>
        </div>
      )}

      {/* Permission Missing State */}
      {isNative && hasPermission === false && (
        <div className="machine-surface p-6 rounded-xl text-center space-y-4">
          <div className="w-14 h-14 mx-auto rounded-xl bg-amber-950/60 border border-amber-500/50 flex items-center justify-center text-amber-400">
            <ShieldAlert className="w-7 h-7" />
          </div>
          <div className="space-y-1">
            <h3 className="font-mono text-sm font-bold text-white uppercase">
              PERMISSION REQUIRED // READ_MEDIA_VIDEO
            </h3>
            <p className="text-xs text-slate-400 max-w-xs mx-auto font-sans">
              OmniDrop requires permission to query and play video files stored in Movies/OmniDrop.
            </p>
          </div>
          <button
            onClick={handleRequestPermission}
            className="machine-btn-primary w-full py-2.5 rounded-lg font-mono text-xs uppercase"
          >
            ALLOW ACCESS TO MY VIDEOS
          </button>
        </div>
      )}

      {/* Header Info & Stats in Machine Style */}
      <div className="flex items-center justify-between pt-1">
        <div>
          <h2 className="text-xs font-mono font-bold text-cyan-400 uppercase tracking-widest flex items-center gap-2">
            <span className="w-2 h-2 rounded-full led-cyan" />
            VIDEOS // LIBRARY
          </h2>
          <div className="flex items-center gap-2 mt-0.5 font-mono text-xs text-slate-400">
            <span>{videos.length} {videos.length === 1 ? 'CLIP' : 'CLIPS'}</span>
            <span>•</span>
            <span className="text-cyan-300 font-bold">{formatSize(totalSizeBytes)}</span>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {/* Find Duplicates Button */}
          <button
            onClick={handleFindDuplicates}
            className="machine-btn px-2.5 py-1.5 rounded-lg flex items-center gap-1.5 text-xs font-mono text-cyan-300"
            title="Find duplicate video files"
          >
            <CopyCheck className="w-3.5 h-3.5 text-cyan-400" />
            <span>FIND DUPLICATES</span>
          </button>

          <button
            onClick={loadLibrary}
            disabled={isLoading}
            className="machine-btn p-2 rounded-lg text-slate-400 hover:text-cyan-300 disabled:opacity-50"
            title="Refresh library"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin text-cyan-400' : ''}`} />
          </button>
        </div>
      </div>

      {/* Search Input in Machine Style */}
      <div className="relative">
        <Search className="w-4 h-4 text-slate-500 absolute left-3 top-1/2 -translate-y-1/2" />
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="SEARCH VIDEOS OR PLATFORM..."
          className="w-full pl-9 pr-8 py-2 rounded-lg bg-slate-950 border border-slate-800 focus:border-cyan-400 font-mono text-xs text-white placeholder-slate-600 outline-none uppercase"
        />
        {searchQuery && (
          <button
            onClick={() => setSearchQuery('')}
            className="absolute right-2.5 top-1/2 -translate-y-1/2 font-mono text-xs text-slate-500 hover:text-white"
          >
            ✕
          </button>
        )}
      </div>

      {/* Platform Filter Buttons */}
      <div className="flex items-center gap-1 overflow-x-auto pb-1 no-scrollbar text-xs">
        {platforms.map((p) => {
          const isActive = selectedPlatform === p.id;
          return (
            <button
              key={p.id}
              onClick={() => setSelectedPlatform(p.id)}
              className={`px-2.5 py-1 rounded-md font-mono text-[11px] whitespace-nowrap transition-all ${
                isActive
                  ? 'machine-btn-primary text-slate-950 shadow-[0_0_8px_rgba(0,240,255,0.4)]'
                  : 'machine-btn text-slate-400 hover:text-slate-200'
              }`}
            >
              {p.label}
            </button>
          );
        })}
      </div>

      {/* Loading Skeleton */}
      {isLoading && videos.length === 0 && (
        <div className="space-y-2.5">
          {[1, 2, 3].map((i) => (
            <div
              key={i}
              className="machine-surface p-3 rounded-xl animate-pulse flex gap-3 items-center"
            >
              <div className="w-20 h-14 rounded-lg bg-slate-950 border border-slate-800 shrink-0" />
              <div className="flex-1 space-y-2">
                <div className="h-3 bg-slate-800 rounded w-3/4" />
                <div className="h-2.5 bg-slate-800/60 rounded w-1/3" />
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Machine Empty State */}
      {!isLoading && filteredVideos.length === 0 && (
        <div className="machine-surface p-8 text-center rounded-xl space-y-3">
          <div className="w-14 h-14 mx-auto rounded-xl bg-slate-950 border border-cyan-500/30 flex items-center justify-center text-cyan-400 shadow-[0_0_15px_rgba(0,240,255,0.2)]">
            <Film className="w-7 h-7" />
          </div>
          <div className="space-y-1">
            <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
              {searchQuery || selectedPlatform !== 'all' ? 'NO MATCHING CLIPS' : 'LIBRARY VOID // NO RECORDED STREAMS'}
            </h3>
            <p className="text-xs text-slate-400 max-w-xs mx-auto font-sans leading-relaxed">
              {searchQuery || selectedPlatform !== 'all'
                ? 'Adjust your search string or platform filter.'
                : 'Videos downloaded with OmniDrop will automatically appear here and stay in Movies/OmniDrop.'}
            </p>
          </div>
        </div>
      )}

      {/* Video Cards List */}
      <div className="space-y-3">
        {filteredVideos.map((video) => (
          <div
            key={video.id || video.uri}
            className="machine-surface group relative p-3 rounded-xl flex flex-col gap-2.5 transition-all duration-150"
          >
            <div className="flex gap-3 items-center">
              {/* Thumbnail with Play Key */}
              <button
                onClick={() => handlePlay(video)}
                className="relative w-22 h-15 rounded-lg overflow-hidden bg-slate-950 shrink-0 border border-slate-800 group-hover:border-cyan-400/60 transition-colors text-left"
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

                {/* Dark Vignette & Play */}
                <div className="absolute inset-0 bg-black/40 flex items-center justify-center group-hover:bg-black/20 transition-all">
                  <div className="w-7 h-7 rounded-full bg-cyan-400 text-slate-950 flex items-center justify-center shadow-[0_0_8px_rgba(0,240,255,0.7)]">
                    <Play className="w-3.5 h-3.5 fill-slate-950 translate-x-0.5" />
                  </div>
                </div>

                {video.durationMs > 0 && (
                  <span className="absolute bottom-1 right-1 px-1 py-0.2 rounded bg-black/80 font-mono text-[9px] text-white">
                    {formatDuration(video.durationMs)}
                  </span>
                )}
              </button>

              {/* Title & Info */}
              <div className="flex-1 min-w-0 pr-1">
                <h4
                  onClick={() => handlePlay(video)}
                  className="font-mono text-xs font-bold text-white truncate cursor-pointer hover:text-cyan-300 transition"
                  title={video.title}
                >
                  {video.title}
                </h4>

                <div className="flex items-center gap-2 mt-1.5 flex-wrap">
                  <span
                    className={`uppercase px-1.5 py-0.2 rounded border text-[10px] font-mono tracking-wider font-bold ${getPlatformBadgeStyle(
                      video.platform
                    )}`}
                  >
                    {video.platform || 'Other'}
                  </span>

                  <span className="font-mono text-[11px] text-slate-400">
                    {formatSize(video.size)}
                  </span>

                  {video.dateAdded > 0 && (
                    <span className="flex items-center gap-1 font-mono text-[10px] text-slate-500">
                      <Clock className="w-3 h-3" />
                      {formatDate(video.dateAdded)}
                    </span>
                  )}
                </div>
              </div>
            </div>

            {/* Bottom Row: Play Button & 3-Dot Menu */}
            <div className="flex items-center justify-between pt-2 border-t border-slate-800/80">
              <button
                onClick={() => handlePlay(video)}
                className="machine-btn px-3 py-1 rounded-md text-xs font-mono font-bold text-cyan-300 flex items-center gap-1.5"
              >
                <Play className="w-3 h-3 fill-cyan-400" />
                <span>PLAY</span>
              </button>

              <div className="relative">
                {/* 3-Dot Trigger */}
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setActiveMenuId(activeMenuId === video.uri ? null : video.uri);
                  }}
                  className="machine-btn p-1.5 rounded-md text-slate-400 hover:text-cyan-300"
                  title="Card options"
                >
                  <MoreVertical className="w-3.5 h-3.5" />
                </button>

                {/* 3-Dot Dropdown Menu (Rename, Share, Delete) */}
                {activeMenuId === video.uri && (
                  <div
                    onClick={(e) => e.stopPropagation()}
                    className="absolute right-0 bottom-full mb-1 w-36 rounded-lg bg-slate-950 border border-cyan-500/40 shadow-2xl p-1 z-30 font-mono text-xs space-y-0.5 animate-fade-in"
                  >
                    <button
                      onClick={() => handleOpenRename(video)}
                      className="w-full px-2.5 py-1.5 rounded flex items-center gap-2 text-slate-300 hover:bg-slate-800 hover:text-cyan-300 text-left"
                    >
                      <Edit3 className="w-3.5 h-3.5 text-cyan-400" />
                      <span>Rename</span>
                    </button>
                    <button
                      onClick={() => {
                        setActiveMenuId(null);
                        handleShare(video);
                      }}
                      className="w-full px-2.5 py-1.5 rounded flex items-center gap-2 text-slate-300 hover:bg-slate-800 hover:text-cyan-300 text-left"
                    >
                      <Share2 className="w-3.5 h-3.5 text-cyan-400" />
                      <span>Share</span>
                    </button>
                    <button
                      onClick={() => {
                        setActiveMenuId(null);
                        setDeleteConfirmId(video.uri);
                      }}
                      className="w-full px-2.5 py-1.5 rounded flex items-center gap-2 text-rose-400 hover:bg-rose-950/50 text-left"
                    >
                      <Trash2 className="w-3.5 h-3.5 text-rose-400" />
                      <span>Delete</span>
                    </button>
                  </div>
                )}
              </div>
            </div>

            {/* In-place Delete Confirmation */}
            {deleteConfirmId === video.uri && (
              <div className="p-2 rounded-lg bg-rose-950/60 border border-rose-500/50 flex items-center justify-between font-mono text-xs animate-fade-in">
                <span className="text-rose-200">Confirm file deletion?</span>
                <div className="flex items-center gap-1.5">
                  <button
                    onClick={() => handleDelete(video)}
                    disabled={isDeleting}
                    className="px-2.5 py-1 rounded bg-rose-600 hover:bg-rose-500 text-white font-bold disabled:opacity-50"
                  >
                    DELETE
                  </button>
                  <button
                    onClick={() => setDeleteConfirmId(null)}
                    className="px-2 py-1 rounded bg-slate-800 text-slate-300"
                  >
                    CANCEL
                  </button>
                </div>
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Find Duplicates Modal */}
      {duplicateModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm">
          <div className="machine-surface w-full max-w-md max-h-[85vh] flex flex-col rounded-xl shadow-2xl">
            <div className="p-3.5 border-b border-cyan-500/20 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Layers className="w-4 h-4 text-cyan-400" />
                <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
                  DUPLICATE SCANNER // AUDIT
                </h3>
              </div>
              <button
                onClick={() => setDuplicateModalOpen(false)}
                className="text-slate-400 hover:text-white p-1"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-4 overflow-y-auto space-y-3.5 flex-1 font-mono text-xs">
              {duplicateGroups.length === 0 ? (
                <div className="text-center py-8 space-y-2">
                  <div className="w-12 h-12 mx-auto rounded-full bg-emerald-950 border border-emerald-500/50 flex items-center justify-center text-emerald-400">
                    <Check className="w-6 h-6" />
                  </div>
                  <h4 className="font-mono text-xs font-bold text-white uppercase">
                    NO DUPLICATES DETECTED
                  </h4>
                  <p className="text-slate-400 max-w-xs mx-auto font-sans">
                    All video files in your library have unique sizes and durations.
                  </p>
                </div>
              ) : (
                <>
                  <div className="p-3 rounded-lg bg-cyan-950/60 border border-cyan-500/40 text-cyan-300">
                    Found <strong>{duplicateGroups.length}</strong> duplicate sets ({totalExtraDuplicates} redundant copies).
                    Oldest original copies will be preserved.
                  </div>

                  <div className="space-y-3">
                    {duplicateGroups.map((group, idx) => (
                      <div
                        key={idx}
                        className="p-3 rounded-lg bg-slate-950 border border-slate-800 space-y-2"
                      >
                        <div className="flex justify-between text-slate-400 text-[11px]">
                          <span>Group #{idx + 1}</span>
                          <span>
                            {formatSize(group.keepVideo.size)} • {formatDuration(group.keepVideo.durationMs)}
                          </span>
                        </div>

                        <div className="p-2 rounded bg-emerald-950/30 border border-emerald-600/30 text-emerald-300 text-[11px] truncate">
                          [KEEP OLDEST] {group.keepVideo.title}
                        </div>

                        {group.extraVideos.map((extra, eIdx) => (
                          <div
                            key={eIdx}
                            className="p-2 rounded bg-rose-950/30 border border-rose-600/30 text-rose-300 text-[11px] truncate"
                          >
                            [PURGE EXTRA] {extra.title}
                          </div>
                        ))}
                      </div>
                    ))}
                  </div>
                </>
              )}
            </div>

            {duplicateGroups.length > 0 && (
              <div className="p-3.5 border-t border-slate-800 flex items-center justify-end gap-2">
                <button
                  onClick={() => setDuplicateModalOpen(false)}
                  className="machine-btn px-3 py-1.5 rounded-lg font-mono text-xs text-slate-400"
                >
                  CANCEL
                </button>
                <button
                  onClick={handleDeleteDuplicates}
                  disabled={isDeletingDuplicates}
                  className="machine-btn-danger px-4 py-1.5 rounded-lg font-mono text-xs font-bold uppercase"
                >
                  {isDeletingDuplicates ? 'PURGING...' : `PURGE ${totalExtraDuplicates} COPIES`}
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Rename Modal */}
      {renameModalVideo && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm">
          <div className="machine-surface w-full max-w-sm rounded-xl p-4 space-y-3 shadow-2xl">
            <div className="flex items-center justify-between pb-2 border-b border-cyan-500/20">
              <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
                RENAME VIDEO RECORD
              </h3>
              <button
                onClick={() => setRenameModalVideo(null)}
                className="text-slate-400 hover:text-white"
              >
                ✕
              </button>
            </div>

            <div className="space-y-2.5">
              <div>
                <label className="block text-[10px] font-mono text-slate-400 mb-1 uppercase">
                  VIDEO TITLE
                </label>
                <input
                  type="text"
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  className="w-full rounded-lg bg-slate-950 border border-slate-800 focus:border-cyan-400 px-3 py-2 font-mono text-xs text-white outline-none"
                />
              </div>

              <div>
                <label className="block text-[10px] font-mono text-slate-400 mb-1 uppercase">
                  PLATFORM CLASSIFICATION
                </label>
                <div className="grid grid-cols-3 gap-1">
                  {['TikTok', 'YouTube', 'Instagram', 'Facebook', 'Other'].map((p) => (
                    <button
                      key={p}
                      onClick={() => setNewPlatform(p)}
                      className={`py-1.5 rounded font-mono text-[11px] font-bold transition-all ${
                        newPlatform.toLowerCase() === p.toLowerCase()
                          ? 'machine-btn-primary text-slate-950'
                          : 'machine-btn text-slate-400'
                      }`}
                    >
                      {p}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <div className="pt-2 flex items-center justify-end gap-2">
              <button
                onClick={() => setRenameModalVideo(null)}
                className="machine-btn px-3 py-1.5 rounded-lg font-mono text-xs text-slate-400"
              >
                CANCEL
              </button>
              <button
                onClick={handleSaveRename}
                disabled={isRenaming}
                className="machine-btn-primary px-4 py-1.5 rounded-lg font-mono text-xs uppercase"
              >
                {isRenaming ? 'SAVING...' : 'SAVE CHANGES'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
