import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Image as ImageIcon,
  Share2,
  Trash2,
  Search,
  RefreshCw,
  ShieldAlert,
  Sparkles,
  Check,
  X,
  Layers,
  Grid,
  ChevronLeft,
  CheckSquare,
  Square,
} from 'lucide-react';
import { PhotoLibraryItem, PlatformType } from '../types';
import { VideoDownloader } from '../plugins/videoDownloader';
import { Capacitor } from '@capacitor/core';

interface PhotosScreenProps {
  onRefreshTrigger?: number;
}

export const PhotosScreen: React.FC<PhotosScreenProps> = ({ onRefreshTrigger }) => {
  const isNative = Capacitor.isNativePlatform();
  const [photos, setPhotos] = useState<PhotoLibraryItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [hasPermission, setHasPermission] = useState<boolean | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewMode, setViewMode] = useState<'byPost' | 'all'>('byPost');
  const [activePostTitle, setActivePostTitle] = useState<string | null>(null);

  // Selection state
  const [isSelectMode, setIsSelectMode] = useState(false);
  const [selectedUris, setSelectedUris] = useState<Set<string>>(new Set());
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);

  // Load photos from MediaStore
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

      const res = await VideoDownloader.getPhotoLibrary();
      if (res && Array.isArray(res.photos)) {
        setPhotos(res.photos);
      }
    } catch (e) {
      console.warn('Failed to load photo library', e);
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

  // Auto-refresh when foregrounded
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

  // Auto-refresh when a native download finishes
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

  // Format helpers
  const formatSize = (bytes: number) => {
    if (!bytes) return '0 MB';
    const mb = bytes / (1024 * 1024);
    if (mb >= 1000) {
      return `${(mb / 1024).toFixed(2)} GB`;
    }
    return `${mb.toFixed(1)} MB`;
  };

  const formatDate = (timestamp: number) => {
    if (!timestamp) return '';
    const d = new Date(timestamp);
    return d.toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
    });
  };

  const getPlatformBadgeStyle = (platform: PlatformType | string | undefined) => {
    const p = (platform || 'other').toLowerCase();
    switch (p) {
      case 'tiktok':
        return 'bg-gradient-to-r from-cyan-950 to-pink-950 text-cyan-300 border-cyan-400/60 shadow-[0_0_8px_rgba(0,240,255,0.25)]';
      case 'instagram':
        return 'bg-gradient-to-r from-purple-950 to-amber-950 text-purple-300 border-purple-500/60 shadow-[0_0_8px_rgba(168,85,247,0.25)]';
      case 'youtube':
        return 'bg-red-950 text-red-400 border-red-600/70 shadow-[0_0_8px_rgba(239,68,68,0.25)]';
      case 'facebook':
        return 'bg-blue-950 text-blue-400 border-blue-500/70 shadow-[0_0_8px_rgba(59,130,246,0.25)]';
      default:
        return 'bg-slate-900 text-slate-400 border-slate-700';
    }
  };

  // Filter photos by search query
  const filteredPhotos = useMemo(() => {
    if (!searchQuery.trim()) return photos;
    const q = searchQuery.toLowerCase().trim();
    return photos.filter(
      (p) =>
        (p.postTitle && p.postTitle.toLowerCase().includes(q)) ||
        (p.fileName && p.fileName.toLowerCase().includes(q)) ||
        (p.platform && p.platform.toLowerCase().includes(q))
    );
  }, [photos, searchQuery]);

  // Group photos by post
  const posts = useMemo(() => {
    const map = new Map<string, PhotoLibraryItem[]>();
    for (const p of filteredPhotos) {
      const key = p.postTitle || p.fileName || 'Untitled Post';
      const list = map.get(key) || [];
      list.push(p);
      map.set(key, list);
    }

    const result: {
      postTitle: string;
      coverPhoto: PhotoLibraryItem;
      photos: PhotoLibraryItem[];
      totalSize: number;
      dateAdded: number;
      platform?: string;
    }[] = [];

    for (const [title, list] of map.entries()) {
      const sorted = [...list].sort((a, b) => (b.dateAdded || 0) - (a.dateAdded || 0));
      const totalSize = sorted.reduce((acc, curr) => acc + (curr.size || 0), 0);
      result.push({
        postTitle: title,
        coverPhoto: sorted[0],
        photos: sorted,
        totalSize,
        dateAdded: sorted[0]?.dateAdded || 0,
        platform: sorted[0]?.platform || 'tiktok',
      });
    }

    return result.sort((a, b) => b.dateAdded - a.dateAdded);
  }, [filteredPhotos]);

  const totalSizeBytes = useMemo(() => {
    return filteredPhotos.reduce((acc, p) => acc + (p.size || 0), 0);
  }, [filteredPhotos]);

  // Active post photos when drilling down
  const currentPost = useMemo(() => {
    if (!activePostTitle) return null;
    return posts.find((p) => p.postTitle === activePostTitle) || null;
  }, [posts, activePostTitle]);

  // Selection handlers
  const toggleSelectPhoto = (uri: string) => {
    setSelectedUris((prev) => {
      const next = new Set(prev);
      if (next.has(uri)) {
        next.delete(uri);
      } else {
        next.add(uri);
      }
      return next;
    });
  };

  const handleSelectAll = () => {
    const targetPhotos = currentPost ? currentPost.photos : filteredPhotos;
    if (selectedUris.size === targetPhotos.length) {
      setSelectedUris(new Set());
    } else {
      setSelectedUris(new Set(targetPhotos.map((p) => p.uri)));
    }
  };

  const handlePhotoClick = (photo: PhotoLibraryItem, index: number, sourceList: PhotoLibraryItem[]) => {
    if (isSelectMode) {
      toggleSelectPhoto(photo.uri);
      return;
    }

    // Open full-screen photo viewer
    const uris = sourceList.map((p) => p.uri);
    VideoDownloader.openPhotoViewer({
      uris,
      index,
      postTitle: photo.postTitle || 'OmniDrop Photos',
    }).catch((err) => {
      console.warn('Failed to open photo viewer', err);
    });
  };

  const handlePhotoLongPress = (photo: PhotoLibraryItem) => {
    if (!isSelectMode) {
      setIsSelectMode(true);
      setSelectedUris(new Set([photo.uri]));
    }
  };

  const handleDeleteSelected = async () => {
    if (selectedUris.size === 0) return;
    setIsDeleting(true);
    try {
      const urisArray = Array.from(selectedUris);
      await VideoDownloader.deletePhotos({ uris: urisArray });
      setPhotos((prev) => prev.filter((p) => !selectedUris.has(p.uri)));
      setSelectedUris(new Set());
      setIsSelectMode(false);
      setDeleteConfirmOpen(false);
    } catch (e) {
      alert('Error deleting photos: ' + e);
    } finally {
      setIsDeleting(false);
    }
  };

  const handleShareSelected = async () => {
    if (selectedUris.size === 0) return;
    const urisArray = Array.from(selectedUris);
    if (urisArray.length === 1) {
      await VideoDownloader.shareVideo({
        uri: urisArray[0],
        title: 'OmniDrop Photo',
      });
    } else {
      // Open viewer on first image or prompt
      alert(`Sharing ${urisArray.length} photos. Select an image in the viewer to share.`);
    }
  };

  return (
    <div className="space-y-4 pb-28">
      {/* Web Notice if in browser */}
      {!isNative && (
        <div className="machine-surface p-3 rounded-xl flex items-center gap-2.5 text-xs text-slate-300">
          <Sparkles className="w-4 h-4 text-cyan-400 shrink-0" />
          <span>Your photo library is available in the Android app (synced directly with Pictures/OmniDrop).</span>
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
              PERMISSION REQUIRED // MEDIA STORAGE
            </h3>
            <p className="text-xs text-slate-400 max-w-xs mx-auto font-sans">
              OmniDrop requires permission to view and manage downloaded photos stored in Pictures/OmniDrop.
            </p>
          </div>
          <button
            onClick={handleRequestPermission}
            className="machine-btn-primary w-full py-2.5 rounded-lg font-mono text-xs uppercase"
          >
            ALLOW ACCESS TO MY MEDIA
          </button>
        </div>
      )}

      {/* Active Selection Action Bar */}
      {isSelectMode ? (
        <div className="machine-surface p-3 rounded-xl flex items-center justify-between border-cyan-500/50 shadow-[0_0_15px_rgba(0,240,255,0.15)] animate-in fade-in duration-150">
          <div className="flex items-center gap-2">
            <button
              onClick={() => {
                setIsSelectMode(false);
                setSelectedUris(new Set());
              }}
              className="p-1 rounded text-slate-400 hover:text-white"
            >
              <X className="w-4 h-4" />
            </button>
            <span className="font-mono text-xs font-bold text-cyan-300">
              [{selectedUris.size}] SELECTED
            </span>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={handleSelectAll}
              className="px-2.5 py-1 rounded bg-slate-900 border border-slate-700 hover:border-cyan-400 font-mono text-[11px] text-slate-300"
            >
              {selectedUris.size === (currentPost ? currentPost.photos.length : filteredPhotos.length)
                ? 'DESELECT'
                : 'SELECT ALL'}
            </button>

            <button
              onClick={handleShareSelected}
              disabled={selectedUris.size === 0}
              className="p-1.5 rounded bg-slate-900 border border-slate-700 hover:border-cyan-400 text-cyan-300 disabled:opacity-40"
              title="Share selected"
            >
              <Share2 className="w-3.5 h-3.5" />
            </button>

            <button
              onClick={() => setDeleteConfirmOpen(true)}
              disabled={selectedUris.size === 0}
              className="p-1.5 rounded bg-red-950/60 border border-red-500/50 hover:border-red-400 text-red-400 disabled:opacity-40"
              title="Delete selected"
            >
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      ) : (
        /* Standard Header Info & Stats */
        <div className="flex items-center justify-between pt-1">
          <div>
            <h2 className="text-xs font-mono font-bold text-cyan-400 uppercase tracking-widest flex items-center gap-2">
              <span className="w-2 h-2 rounded-full led-cyan" />
              PHOTOS // LIBRARY
            </h2>
            <div className="flex items-center gap-2 mt-0.5 font-mono text-xs text-slate-400">
              <span>
                {filteredPhotos.length} {filteredPhotos.length === 1 ? 'PHOTO' : 'PHOTOS'}
              </span>
              <span>•</span>
              <span className="text-cyan-300 font-bold">{formatSize(totalSizeBytes)}</span>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {/* View Mode Switcher */}
            <div className="flex items-center bg-slate-950 border border-slate-800 rounded-lg p-0.5 font-mono text-[11px]">
              <button
                onClick={() => {
                  setViewMode('byPost');
                  setActivePostTitle(null);
                }}
                className={`px-2 py-1 rounded transition-all flex items-center gap-1 ${
                  viewMode === 'byPost'
                    ? 'bg-cyan-500/20 text-cyan-300 font-bold border border-cyan-500/40'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                <Layers className="w-3 h-3" />
                <span>POSTS</span>
              </button>
              <button
                onClick={() => {
                  setViewMode('all');
                  setActivePostTitle(null);
                }}
                className={`px-2 py-1 rounded transition-all flex items-center gap-1 ${
                  viewMode === 'all'
                    ? 'bg-cyan-500/20 text-cyan-300 font-bold border border-cyan-500/40'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                <Grid className="w-3 h-3" />
                <span>ALL</span>
              </button>
            </div>

            <button
              onClick={loadLibrary}
              disabled={isLoading}
              className="machine-btn p-2 rounded-lg text-slate-400 hover:text-cyan-300 disabled:opacity-50"
              title="Refresh photo library"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin text-cyan-400' : ''}`} />
            </button>
          </div>
        </div>
      )}

      {/* Search Input */}
      <div className="relative">
        <Search className="w-4 h-4 text-slate-500 absolute left-3 top-1/2 -translate-y-1/2" />
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="SEARCH PHOTOS OR POSTS..."
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

      {/* Drill-down View: Photos of a specific post */}
      {viewMode === 'byPost' && currentPost ? (
        <div className="space-y-3">
          <div className="flex items-center justify-between bg-slate-950/80 p-2.5 rounded-lg border border-slate-800">
            <button
              onClick={() => {
                setActivePostTitle(null);
                setIsSelectMode(false);
                setSelectedUris(new Set());
              }}
              className="flex items-center gap-1 text-cyan-400 hover:text-cyan-300 font-mono text-xs"
            >
              <ChevronLeft className="w-4 h-4" />
              <span>‹ BACK TO POSTS</span>
            </button>
            <div className="flex items-center gap-2">
              <span
                className={`text-[9px] font-mono uppercase px-1.5 py-0.5 rounded border ${getPlatformBadgeStyle(
                  currentPost.platform
                )}`}
              >
                {currentPost.platform || 'TIKTOK'}
              </span>
              <span className="font-mono text-xs text-slate-400">
                {currentPost.photos.length} PHOTOS
              </span>
            </div>
          </div>

          <div className="px-1">
            <h3 className="font-bold text-sm text-white line-clamp-1">{currentPost.postTitle}</h3>
            <p className="text-[11px] font-mono text-slate-400 mt-0.5">
              {formatDate(currentPost.dateAdded)} • {formatSize(currentPost.totalSize)}
            </p>
          </div>

          {/* 3-Column Grid for post pictures */}
          <div className="grid grid-cols-3 gap-2">
            {currentPost.photos.map((photo, idx) => {
              const isSelected = selectedUris.has(photo.uri);
              return (
                <div
                  key={photo.id || photo.uri}
                  onClick={() => handlePhotoClick(photo, idx, currentPost.photos)}
                  onContextMenu={(e) => {
                    e.preventDefault();
                    handlePhotoLongPress(photo);
                  }}
                  className={`group relative aspect-square rounded-lg overflow-hidden bg-slate-950 border cursor-pointer transition-all active:scale-95 ${
                    isSelected
                      ? 'border-cyan-400 shadow-[0_0_10px_rgba(0,240,255,0.4)]'
                      : 'border-slate-800 hover:border-cyan-500/50'
                  }`}
                >
                  <img
                    src={photo.thumbnail || photo.uri}
                    alt={photo.fileName}
                    className="w-full h-full object-cover select-none"
                    loading="lazy"
                  />
                  {isSelectMode && (
                    <div className="absolute top-1.5 right-1.5 w-5 h-5 rounded flex items-center justify-center bg-slate-950/80 border border-slate-600">
                      {isSelected ? (
                        <div className="w-4 h-4 rounded bg-cyan-400 flex items-center justify-center text-slate-950">
                          <Check className="w-3 h-3 stroke-[3]" />
                        </div>
                      ) : null}
                    </div>
                  )}
                  <div className="absolute bottom-1 right-1 px-1 py-0.2 rounded bg-slate-950/70 text-[9px] font-mono text-slate-300">
                    #{idx + 1}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      ) : viewMode === 'byPost' ? (
        /* "By Post" List Cards */
        <div className="space-y-3">
          {posts.length === 0 ? (
            <div className="machine-surface p-8 rounded-xl text-center space-y-3">
              <div className="w-12 h-12 mx-auto rounded-xl bg-slate-900 border border-slate-800 flex items-center justify-center text-slate-600">
                <ImageIcon className="w-6 h-6" />
              </div>
              <div className="space-y-1">
                <h4 className="font-mono text-xs font-bold text-slate-300 uppercase">NO PHOTO POSTS FOUND</h4>
                <p className="text-xs text-slate-500 max-w-xs mx-auto">
                  Paste a TikTok photo post link on the Download tab to save image slideshows.
                </p>
              </div>
            </div>
          ) : (
            posts.map((post) => (
              <div
                key={post.postTitle}
                onClick={() => setActivePostTitle(post.postTitle)}
                className="machine-card p-3 rounded-xl border border-slate-800 hover:border-cyan-500/40 cursor-pointer transition-all active:scale-[0.99] flex gap-3 items-center group"
              >
                {/* Cover Thumbnail */}
                <div className="relative w-20 h-20 rounded-lg overflow-hidden bg-slate-950 shrink-0 border border-slate-800 group-hover:border-cyan-500/50">
                  <img
                    src={post.coverPhoto.thumbnail || post.coverPhoto.uri}
                    alt={post.postTitle}
                    className="w-full h-full object-cover"
                    loading="lazy"
                  />
                  <span className="absolute bottom-1 right-1 px-1.5 py-0.5 rounded bg-slate-950/80 font-mono text-[9px] font-bold text-cyan-300 border border-cyan-500/30">
                    {post.photos.length}P
                  </span>
                </div>

                {/* Post Metadata */}
                <div className="flex-1 min-w-0 space-y-1">
                  <div className="flex items-center gap-2">
                    <span
                      className={`text-[9px] font-mono uppercase px-1.5 py-0.5 rounded border ${getPlatformBadgeStyle(
                        post.platform
                      )}`}
                    >
                      {post.platform || 'TIKTOK'}
                    </span>
                    <span className="text-[10px] font-mono text-slate-500">{formatDate(post.dateAdded)}</span>
                  </div>

                  <h3 className="font-bold text-xs text-white line-clamp-2 group-hover:text-cyan-300 transition-colors">
                    {post.postTitle}
                  </h3>

                  <div className="flex items-center gap-2 font-mono text-[10px] text-slate-400">
                    <span className="text-cyan-400">{post.photos.length} PHOTOS</span>
                    <span>•</span>
                    <span>{formatSize(post.totalSize)}</span>
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      ) : (
        /* "All Photos" 3-Column Square Grid */
        <div>
          {filteredPhotos.length === 0 ? (
            <div className="machine-surface p-8 rounded-xl text-center space-y-3">
              <div className="w-12 h-12 mx-auto rounded-xl bg-slate-900 border border-slate-800 flex items-center justify-center text-slate-600">
                <ImageIcon className="w-6 h-6" />
              </div>
              <div className="space-y-1">
                <h4 className="font-mono text-xs font-bold text-slate-300 uppercase">NO PHOTOS IN STORAGE</h4>
                <p className="text-xs text-slate-500 max-w-xs mx-auto">
                  Photos downloaded with OmniDrop will automatically appear here.
                </p>
              </div>
            </div>
          ) : (
            <div className="grid grid-cols-3 gap-2">
              {filteredPhotos.map((photo, idx) => {
                const isSelected = selectedUris.has(photo.uri);
                return (
                  <div
                    key={photo.id || photo.uri}
                    onClick={() => handlePhotoClick(photo, idx, filteredPhotos)}
                    onContextMenu={(e) => {
                      e.preventDefault();
                      handlePhotoLongPress(photo);
                    }}
                    className={`group relative aspect-square rounded-lg overflow-hidden bg-slate-950 border cursor-pointer transition-all active:scale-95 ${
                      isSelected
                        ? 'border-cyan-400 shadow-[0_0_10px_rgba(0,240,255,0.4)]'
                        : 'border-slate-800 hover:border-cyan-500/50'
                    }`}
                  >
                    <img
                      src={photo.thumbnail || photo.uri}
                      alt={photo.fileName}
                      className="w-full h-full object-cover select-none"
                      loading="lazy"
                    />
                    {isSelectMode && (
                      <div className="absolute top-1.5 right-1.5 w-5 h-5 rounded flex items-center justify-center bg-slate-950/80 border border-slate-600">
                        {isSelected ? (
                          <div className="w-4 h-4 rounded bg-cyan-400 flex items-center justify-center text-slate-950">
                            <Check className="w-3 h-3 stroke-[3]" />
                          </div>
                        ) : null}
                      </div>
                    )}
                    <div className="absolute bottom-1 right-1 px-1 py-0.2 rounded bg-slate-950/70 text-[9px] font-mono text-slate-300">
                      {formatSize(photo.size)}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* Delete Confirmation Modal */}
      {deleteConfirmOpen && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="machine-surface max-w-sm w-full p-5 rounded-xl border border-red-500/50 space-y-4 animate-in fade-in zoom-in-95 duration-150">
            <div className="flex items-center gap-2 text-red-400 font-mono text-xs font-bold uppercase">
              <Trash2 className="w-4 h-4" />
              <span>DELETE CONFIRMATION // MEDIA PURGE</span>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed font-sans">
              Are you sure you want to delete {selectedUris.size}{' '}
              {selectedUris.size === 1 ? 'photo' : 'photos'} from OmniDrop and your device gallery?
            </p>
            <div className="flex items-center justify-end gap-2 pt-2">
              <button
                onClick={() => setDeleteConfirmOpen(false)}
                disabled={isDeleting}
                className="px-3 py-1.5 rounded-lg border border-slate-700 font-mono text-xs text-slate-300 hover:bg-slate-900"
              >
                CANCEL
              </button>
              <button
                onClick={handleDeleteSelected}
                disabled={isDeleting}
                className="px-4 py-1.5 rounded-lg bg-red-950/80 border border-red-500 text-red-300 hover:bg-red-900 font-mono text-xs font-bold uppercase transition-all shadow-[0_0_10px_rgba(239,68,68,0.3)] disabled:opacity-50"
              >
                {isDeleting ? 'DELETING...' : 'DELETE'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
