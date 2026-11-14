import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
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
  Star,
  FolderPlus,
  Folder,
  Plus,
  CheckSquare,
  Square,
  Music,
  LayoutGrid,
  List,
} from 'lucide-react';
import { VideoLibraryItem, PlatformType, CollectionItem } from '../types';
import { VideoDownloader } from '../plugins/videoDownloader';
import { Capacitor } from '@capacitor/core';
import { EmptyState } from './EmptyState';

interface VideosScreenProps {
  onRefreshTrigger?: number;
}

const STORAGE_VIDEOS_VIEW_MODE = 'omnidrop_videos_view_mode';

export const VideosScreen: React.FC<VideosScreenProps> = ({ onRefreshTrigger }) => {
  const isNative = Capacitor.isNativePlatform();
  const [videos, setVideos] = useState<VideoLibraryItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [hasPermission, setHasPermission] = useState<boolean | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedPlatform, setSelectedPlatform] = useState<string>('all');
  const [viewMode, setViewMode] = useState<'list' | 'grid'>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_VIDEOS_VIEW_MODE);
      return saved === 'grid' ? 'grid' : 'list';
    } catch {
      return 'list';
    }
  });
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [activeMenuId, setActiveMenuId] = useState<string | null>(null);

  // Scroll-collapsing header + search state
  const [isHeaderCollapsed, setIsHeaderCollapsed] = useState(false);
  const lastScrollYRef = useRef<number>(0);

  // Horizontal swipe gesture state (List view)
  const [swipeUri, setSwipeUri] = useState<string | null>(null);
  const [swipeOffset, setSwipeOffset] = useState<number>(0);
  const swipeTouchRef = useRef<{
    uri: string;
    startX: number;
    startY: number;
    locked: 'none' | 'horizontal' | 'vertical';
  } | null>(null);
  const justSwipedRef = useRef<boolean>(false);

  // Favorites & Collections
  const [favorites, setFavorites] = useState<string[]>([]);
  const [collections, setCollections] = useState<CollectionItem[]>([]);
  const [collectionMenuId, setCollectionMenuId] = useState<string | null>(null);

  // Modals for collections
  const [createCollectionModalOpen, setCreateCollectionModalOpen] = useState(false);
  const [newCollectionName, setNewCollectionName] = useState('');
  const [renameCollectionTarget, setRenameCollectionTarget] = useState<CollectionItem | null>(null);
  const [addToCollectionModalOpen, setAddToCollectionModalOpen] = useState(false);
  const [targetVideosForCollection, setTargetVideosForCollection] = useState<VideoLibraryItem[]>([]);

  // Multi-select state
  const [selectedUris, setSelectedUris] = useState<Set<string>>(new Set());
  const [isMultiSelectMode, setIsMultiSelectMode] = useState(false);
  const pressTimerRef = useRef<NodeJS.Timeout | null>(null);

  const handleSetViewMode = (mode: 'list' | 'grid') => {
    setViewMode(mode);
    setSwipeUri(null);
    setSwipeOffset(0);
    try {
      localStorage.setItem(STORAGE_VIDEOS_VIEW_MODE, mode);
    } catch {}
  };

  useEffect(() => {
    const onScroll = () => {
      const y = window.scrollY || document.documentElement.scrollTop || 0;
      const dy = y - lastScrollYRef.current;
      lastScrollYRef.current = y;

      if (searchQuery.trim().length > 0 || isMultiSelectMode) {
        setIsHeaderCollapsed(false);
        return;
      }

      if (y < 32) {
        setIsHeaderCollapsed(false);
      } else if (dy > 8 && y > 56) {
        setIsHeaderCollapsed(true);
      } else if (dy < -8) {
        setIsHeaderCollapsed(false);
      }
    };

    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, [searchQuery, isMultiSelectMode]);

  const getVideoKey = useCallback((v: VideoLibraryItem) => {
    const name = v.fileName || v.title || 'video';
    return `${name}_${v.size || 0}`;
  }, []);

  const loadFavoritesAndCollections = useCallback(async () => {
    try {
      const res = await VideoDownloader.getFavoritesAndCollections();
      if (res) {
        if (Array.isArray(res.favorites)) setFavorites(res.favorites);
        if (Array.isArray(res.collections)) setCollections(res.collections);
      }
    } catch (e) {
      console.warn('Failed to load favorites and collections', e);
    }
  }, []);

  useEffect(() => {
    loadFavoritesAndCollections();
    let h1: any = null;
    let h2: any = null;
    (async () => {
      try {
        h1 = await VideoDownloader.addListener('onFavoritesChanged', () => {
          loadFavoritesAndCollections();
        });
        h2 = await VideoDownloader.addListener('onCollectionsChanged', () => {
          loadFavoritesAndCollections();
        });
      } catch {}
    })();
    return () => {
      if (h1 && h1.remove) h1.remove();
      if (h2 && h2.remove) h2.remove();
    };
  }, [loadFavoritesAndCollections]);

  // Audio extraction modal state
  const [audioExtractModal, setAudioExtractModal] = useState<{
    isOpen: boolean;
    video: VideoLibraryItem | null;
    isExtracting: boolean;
    progress: number;
    resultUri: string | null;
    resultFileName: string | null;
    error: string | null;
  }>({
    isOpen: false,
    video: null,
    isExtracting: false,
    progress: 0,
    resultUri: null,
    resultFileName: null,
    error: null,
  });

  useEffect(() => {
    let handle: any = null;
    (async () => {
      try {
        handle = await VideoDownloader.addListener('audioExtractProgress', (data: { percent: number; title: string }) => {
          setAudioExtractModal((prev) => (prev.isOpen ? { ...prev, progress: data.percent } : prev));
        });
      } catch {}
    })();
    return () => {
      if (handle && handle.remove) handle.remove();
    };
  }, []);

  const handleStartAudioExtraction = async (video: VideoLibraryItem) => {
    setActiveMenuId(null);
    setAudioExtractModal({
      isOpen: true,
      video,
      isExtracting: true,
      progress: 0,
      resultUri: null,
      resultFileName: null,
      error: null,
    });
    try {
      const res = await VideoDownloader.extractAudio({
        uri: video.uri,
        title: video.title,
      });
      if (res && res.success && res.uri) {
        setAudioExtractModal((prev) => ({
          ...prev,
          isExtracting: false,
          progress: 100,
          resultUri: res.uri || null,
          resultFileName: res.fileName || `${video.title}.m4a`,
        }));
      } else {
        setAudioExtractModal((prev) => ({
          ...prev,
          isExtracting: false,
          error: res?.error || 'Extraction failed: unsupported audio track',
        }));
      }
    } catch (err: any) {
      setAudioExtractModal((prev) => ({
        ...prev,
        isExtracting: false,
        error: err?.message || 'Unsupported audio codec or extraction error',
      }));
    }
  };

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

  // Dark Robotic Notice / Error Modal (no window.alert)
  const [roboticNotice, setRoboticNotice] = useState<{
    title: string;
    message: string;
    isError?: boolean;
  } | null>(null);

  // Styled permission hint toast/banner
  const [permissionNotice, setPermissionNotice] = useState<string | null>(null);

  // Purge result status
  const [purgeResultText, setPurgeResultText] = useState<string | null>(null);

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
      // Strictly deduplicate filteredVideos by URI so playlist matches visible filtered clips exactly
      const seen = new Set<string>();
      const dedupedPlaylist: VideoLibraryItem[] = [];
      for (const v of filteredVideos) {
        if (v.uri && !seen.has(v.uri)) {
          seen.add(v.uri);
          dedupedPlaylist.push(v);
        }
      }

      const initialIndex = dedupedPlaylist.findIndex((v) => v.uri === video.uri);
      const playlist = dedupedPlaylist.map((v) => ({
        uri: v.uri,
        title: v.title,
        platform: v.platform,
        fileName: v.fileName,
        size: v.size,
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
    setPermissionNotice('Android will ask your permission to delete 1 file');
    try {
      const res = await VideoDownloader.deleteVideo({ uri: video.uri });
      setPermissionNotice(null);
      if (res && res.cancelled) {
        setRoboticNotice({
          title: 'DELETION CANCELLED',
          message: 'Permission was not granted to delete this video file.',
          isError: true,
        });
      } else if (res && (res.deleted || (res.count && res.count > 0))) {
        setVideos((prev) => prev.filter((v) => v.uri !== video.uri));
        setDeleteConfirmId(null);
        await loadLibrary();
      } else if (res && res.failed && res.failed > 0) {
        setRoboticNotice({
          title: 'DELETE FAILED',
          message: 'Android restricted deletion of this media file.',
          isError: true,
        });
      } else {
        setVideos((prev) => prev.filter((v) => v.uri !== video.uri));
        setDeleteConfirmId(null);
        await loadLibrary();
      }
    } catch (e) {
      setPermissionNotice(null);
      setRoboticNotice({
        title: 'DELETE ERROR',
        message: 'Could not delete video: ' + (e instanceof Error ? e.message : String(e)),
        isError: true,
      });
    } finally {
      setIsDeleting(false);
      setPermissionNotice(null);
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
    setPurgeResultText(null);
    setDuplicateModalOpen(true);
  };

  const handleDeleteDuplicates = async () => {
    const extraVideos = duplicateGroups.flatMap((g) => g.extraVideos);
    const targetUris = extraVideos.map((v) => v.uri);
    if (targetUris.length === 0) return;

    setIsDeletingDuplicates(true);
    setPurgeResultText(null);
    setPermissionNotice(`Android will ask your permission to delete ${targetUris.length} files`);

    try {
      const res = await VideoDownloader.deleteVideo({ uris: targetUris });
      setPermissionNotice(null);
      const deletedCount =
        typeof res.deleted === 'number'
          ? res.deleted
          : (res.count ?? (res.deleted ? targetUris.length : 0));

      setPurgeResultText(`Deleted ${deletedCount} of ${targetUris.length}`);
      if (res && res.cancelled) {
        setRoboticNotice({
          title: 'PURGE CANCELLED',
          message: `Permission was cancelled. Deleted ${deletedCount} of ${targetUris.length} duplicate copies.`,
          isError: true,
        });
      }
      await loadLibrary();
    } catch (e) {
      setPermissionNotice(null);
      setPurgeResultText(`Deleted 0 of ${targetUris.length}`);
      setRoboticNotice({
        title: 'PURGE ERROR',
        message: 'Error deleting duplicate copies: ' + (e instanceof Error ? e.message : String(e)),
        isError: true,
      });
    } finally {
      setIsDeletingDuplicates(false);
      setPermissionNotice(null);
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
    setPermissionNotice('Android will ask your permission to rename this file');
    try {
      const res = await VideoDownloader.renameVideo({
        uri: renameModalVideo.uri,
        newTitle: trimmedTitle,
        newPlatform: newPlatform || 'Other',
      });
      setPermissionNotice(null);
      if (res && res.cancelled) {
        setRoboticNotice({
          title: 'RENAME CANCELLED',
          message: 'Permission was not granted to rename this video file.',
          isError: true,
        });
      } else if (res && res.success) {
        setVideos((prev) =>
          prev.map((v) =>
            v.uri === renameModalVideo.uri
              ? { ...v, title: res.title || trimmedTitle, platform: (res.platform || newPlatform) as PlatformType }
              : v
          )
        );
        await loadLibrary();
      }
      setRenameModalVideo(null);
    } catch (e) {
      setPermissionNotice(null);
      setRoboticNotice({
        title: 'RENAME ERROR',
        message: 'Failed to rename video: ' + (e instanceof Error ? e.message : String(e)),
        isError: true,
      });
    } finally {
      setIsRenaming(false);
      setPermissionNotice(null);
    }
  };

  const filteredVideos = useMemo(() => {
    return videos.filter((v) => {
      const matchSearch =
        searchQuery.trim() === '' ||
        v.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (v.platform && v.platform.toLowerCase().includes(searchQuery.toLowerCase()));

      if (!matchSearch) return false;

      if (selectedPlatform === 'all') return true;
      if (selectedPlatform === 'favorites') {
        return favorites.includes(getVideoKey(v));
      }
      if (selectedPlatform.startsWith('col_')) {
        const col = collections.find((c) => c.id === selectedPlatform);
        return col ? col.videoKeys.includes(getVideoKey(v)) : true;
      }
      const p = (v.platform || 'other').toLowerCase();
      if (selectedPlatform === 'other') {
        return !['tiktok', 'youtube', 'instagram', 'facebook'].includes(p);
      }
      return p === selectedPlatform.toLowerCase();
    });
  }, [videos, searchQuery, selectedPlatform, favorites, collections, getVideoKey]);

  // Favorites & Collections Actions
  const handleToggleFavorite = async (video: VideoLibraryItem, e?: React.MouseEvent) => {
    if (e) e.stopPropagation();
    const key = getVideoKey(video);
    try {
      const res = await VideoDownloader.toggleFavorite({ mediaKey: key });
      setFavorites((prev) =>
        res.isFavorite ? [...prev.filter((k) => k !== key), key] : prev.filter((k) => k !== key)
      );
    } catch (err) {
      console.warn('Failed to toggle favorite', err);
    }
  };

  const handleCreateCollection = async () => {
    const trimmed = newCollectionName.trim();
    if (!trimmed) return;
    try {
      const res = await VideoDownloader.createCollection({ name: trimmed });
      if (res && res.collection) {
        setCollections((prev) => [...prev, res.collection]);
        setSelectedPlatform(res.collection.id);
      }
    } catch (err) {
      console.warn('Failed to create collection', err);
    } finally {
      setNewCollectionName('');
      setCreateCollectionModalOpen(false);
    }
  };

  const handleSaveRenameCollection = async () => {
    if (!renameCollectionTarget) return;
    const trimmed = newCollectionName.trim();
    if (!trimmed) return;
    try {
      await VideoDownloader.renameCollection({ id: renameCollectionTarget.id, name: trimmed });
      setCollections((prev) =>
        prev.map((c) => (c.id === renameCollectionTarget.id ? { ...c, name: trimmed } : c))
      );
    } catch (err) {
      console.warn('Failed to rename collection', err);
    } finally {
      setRenameCollectionTarget(null);
      setNewCollectionName('');
    }
  };

  const handleDeleteCollection = async (id: string) => {
    try {
      await VideoDownloader.deleteCollection({ id });
      setCollections((prev) => prev.filter((c) => c.id !== id));
      if (selectedPlatform === id) setSelectedPlatform('all');
    } catch (err) {
      console.warn('Failed to delete collection', err);
    } finally {
      setCollectionMenuId(null);
    }
  };

  const handleAddVideosToCollection = async (collectionId: string) => {
    const keys = targetVideosForCollection.map(getVideoKey);
    if (keys.length === 0) return;
    try {
      await VideoDownloader.addToCollection({ collectionId, mediaKeys: keys });
      setCollections((prev) =>
        prev.map((c) => {
          if (c.id === collectionId) {
            const set = new Set([...c.videoKeys, ...keys]);
            return { ...c, videoKeys: Array.from(set) };
          }
          return c;
        })
      );
    } catch (err) {
      console.warn('Failed to add to collection', err);
    } finally {
      setAddToCollectionModalOpen(false);
      setTargetVideosForCollection([]);
      setSelectedUris(new Set());
      setIsMultiSelectMode(false);
    }
  };

  // Multi-select handlers
  const handleToggleSelectUri = (uri: string) => {
    setSelectedUris((prev) => {
      const next = new Set(prev);
      if (next.has(uri)) {
        next.delete(uri);
        if (next.size === 0) setIsMultiSelectMode(false);
      } else {
        next.add(uri);
        setIsMultiSelectMode(true);
      }
      return next;
    });
  };

  const handleCardPressStart = (uri: string) => {
    pressTimerRef.current = setTimeout(() => {
      handleToggleSelectUri(uri);
    }, 500);
  };

  const handleCardPressEnd = () => {
    if (pressTimerRef.current) {
      clearTimeout(pressTimerRef.current);
      pressTimerRef.current = null;
    }
  };

  const handleSwipeTouchStart = (uri: string, e: React.TouchEvent) => {
    handleCardPressStart(uri);
    if (isMultiSelectMode || viewMode !== 'list' || e.touches.length !== 1) return;
    const t = e.touches[0];
    swipeTouchRef.current = {
      uri,
      startX: t.clientX,
      startY: t.clientY,
      locked: 'none',
    };
  };

  const handleSwipeTouchMove = (uri: string, e: React.TouchEvent) => {
    const state = swipeTouchRef.current;
    if (!state || state.uri !== uri || e.touches.length !== 1) return;
    const t = e.touches[0];
    const dx = t.clientX - state.startX;
    const dy = t.clientY - state.startY;

    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
      handleCardPressEnd();
    }

    if (state.locked === 'vertical') return;

    if (state.locked === 'none') {
      if (Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy) * 1.2) {
        state.locked = 'horizontal';
        setSwipeUri(uri);
      } else if (Math.abs(dy) > 12) {
        state.locked = 'vertical';
        return;
      } else {
        return;
      }
    }

    if (state.locked === 'horizontal') {
      const clamped = Math.max(-110, Math.min(110, dx));
      setSwipeUri(uri);
      setSwipeOffset(clamped);
    }
  };

  const handleSwipeTouchEnd = (video: VideoLibraryItem) => {
    handleCardPressEnd();
    const state = swipeTouchRef.current;
    swipeTouchRef.current = null;
    if (!state || state.uri !== video.uri) {
      setSwipeUri(null);
      setSwipeOffset(0);
      return;
    }

    if (state.locked === 'horizontal') {
      justSwipedRef.current = true;
      setTimeout(() => {
        justSwipedRef.current = false;
      }, 260);

      if (swipeOffset >= 72) {
        handleToggleFavorite(video);
      } else if (swipeOffset <= -72) {
        setDeleteConfirmId(video.uri);
      }
    }

    setSwipeUri(null);
    setSwipeOffset(0);
  };

  const handleSelectAll = () => {
    const all = new Set(filteredVideos.map((v) => v.uri));
    setSelectedUris(all);
    setIsMultiSelectMode(true);
  };

  const handleClearSelection = () => {
    setSelectedUris(new Set());
    setIsMultiSelectMode(false);
  };

  const handleDeleteSelected = async () => {
    const urisToDelete = Array.from(selectedUris);
    if (urisToDelete.length === 0) return;
    setIsDeleting(true);
    setPermissionNotice(`Android will ask your permission to delete ${urisToDelete.length} files`);
    try {
      const res = await VideoDownloader.deleteVideo({ uris: urisToDelete });
      setPermissionNotice(null);
      if (res && res.cancelled) {
        setRoboticNotice({
          title: 'DELETE CANCELLED',
          message: 'Permission was cancelled by user.',
          isError: true,
        });
      }
      setSelectedUris(new Set());
      setIsMultiSelectMode(false);
      await loadLibrary();
    } catch (e: any) {
      setPermissionNotice(null);
      setRoboticNotice({
        title: 'DELETE ERROR',
        message: e?.message || 'Failed to delete selected videos',
        isError: true,
      });
    } finally {
      setIsDeleting(false);
      setPermissionNotice(null);
    }
  };

  const handleFavoriteSelected = async () => {
    const selectedVideos = videos.filter((v) => selectedUris.has(v.uri));
    for (const v of selectedVideos) {
      await handleToggleFavorite(v);
    }
    setSelectedUris(new Set());
    setIsMultiSelectMode(false);
  };

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

      {/* Sticky Scroll-Collapsing Header & Search Box */}
      <div className="sticky top-[57px] z-20 -mx-1 px-1 pt-0.5 pb-1.5 bg-[var(--od-bg-canvas)]/95 backdrop-blur-md space-y-2.5">
        <div
          className={`overflow-hidden transition-all duration-200 space-y-2.5 ${
            isHeaderCollapsed
              ? 'max-h-0 opacity-0 pointer-events-none'
              : 'max-h-48 opacity-100'
          }`}
        >
          {/* Header Info & Toolbar */}
          <div className="flex items-center justify-between pt-1 gap-2">
            <div className="min-w-0">
              <h2 className="text-xs font-mono font-bold text-accent uppercase tracking-widest flex items-center gap-2 whitespace-nowrap">
                <span className="w-2 h-2 rounded-full led-accent shrink-0" />
                VIDEOS // LIBRARY
              </h2>
              <div className="flex items-center gap-2 mt-0.5 font-mono text-xs text-slate-400 tabular-nums whitespace-nowrap">
                <span>{videos.length} {videos.length === 1 ? 'CLIP' : 'CLIPS'}</span>
                <span>•</span>
                <span className="text-accent-soft font-bold">{formatSize(totalSizeBytes)}</span>
              </div>
            </div>

            <div className="flex items-center gap-1.5 shrink-0">
              {/* Grid / List View Switcher */}
              <div className="flex items-center bg-slate-950 border border-slate-800 rounded-lg p-0.5">
                <button
                  onClick={() => handleSetViewMode('list')}
                  className={`p-1.5 rounded transition-all ${
                    viewMode === 'list'
                      ? 'bg-accent-soft text-accent border border-accent'
                      : 'text-slate-400 hover:text-slate-200'
                  }`}
                  title="List view"
                >
                  <List className="w-3.5 h-3.5" />
                </button>
                <button
                  onClick={() => handleSetViewMode('grid')}
                  className={`p-1.5 rounded transition-all ${
                    viewMode === 'grid'
                      ? 'bg-accent-soft text-accent border border-accent'
                      : 'text-slate-400 hover:text-slate-200'
                  }`}
                  title="Grid view"
                >
                  <LayoutGrid className="w-3.5 h-3.5" />
                </button>
              </div>

              <button
                onClick={() => {
                  if (isMultiSelectMode) {
                    handleClearSelection();
                  } else {
                    setIsMultiSelectMode(true);
                  }
                }}
                className={`machine-btn px-2 py-1.5 rounded-lg flex items-center gap-1 text-[11px] font-mono transition-all whitespace-nowrap ${
                  isMultiSelectMode ? 'bg-accent-soft border-accent-strong text-accent-soft' : 'text-slate-400'
                }`}
                title="Multi-select mode"
              >
                <CheckSquare className="w-3.5 h-3.5 text-accent" />
                <span>{isMultiSelectMode ? 'DONE' : 'SELECT'}</span>
              </button>

              <button
                onClick={handleFindDuplicates}
                className="machine-btn p-1.5 rounded-lg flex items-center justify-center text-accent-soft"
                title="Find duplicate video files"
              >
                <CopyCheck className="w-3.5 h-3.5 text-accent" />
              </button>

              <button
                onClick={loadLibrary}
                disabled={isLoading}
                className="machine-btn p-1.5 rounded-lg text-slate-400 hover:text-accent-soft disabled:opacity-50"
                title="Refresh library"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin text-accent' : ''}`} />
              </button>
            </div>
          </div>

          {/* Search Input */}
          <div className="relative">
            <Search className="w-4 h-4 text-slate-500 absolute left-3 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="SEARCH VIDEOS OR PLATFORM..."
              className="machine-input w-full pl-9 pr-8 py-2 rounded-lg font-mono text-xs text-white placeholder-slate-600 uppercase"
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
        </div>

        {/* Platform & Collections Filter Buttons */}
        <div className="flex items-center gap-1.5 overflow-x-auto pb-0.5 no-scrollbar text-xs">
          <button
            onClick={() => setSelectedPlatform('all')}
            className={`px-2.5 py-1 rounded-md font-mono text-[11px] whitespace-nowrap tabular-nums transition-all ${
              selectedPlatform === 'all'
                ? 'machine-btn-primary text-slate-950 glow-accent-sm'
                : 'machine-btn text-slate-400 hover:text-slate-200'
            }`}
          >
            ALL ({videos.length})
          </button>

          <button
            onClick={() => setSelectedPlatform('favorites')}
            className={`px-2.5 py-1 rounded-md font-mono text-[11px] whitespace-nowrap tabular-nums transition-all flex items-center gap-1.5 ${
              selectedPlatform === 'favorites'
                ? 'bg-amber-400 text-slate-950 font-bold shadow-[0_0_10px_rgba(245,158,11,0.5)] border border-amber-300'
                : 'machine-btn text-amber-400 hover:text-amber-300 border-amber-500/30'
            }`}
          >
            <Star className="w-3 h-3 fill-current" />
            <span>FAVORITES ({favorites.length})</span>
          </button>

        {/* Platforms */}
          {platforms
            .filter((p) => p.id !== 'all')
            .map((p) => {
              const isActive = selectedPlatform === p.id;
              return (
                <button
                  key={p.id}
                  onClick={() => setSelectedPlatform(p.id)}
                  className={`px-2.5 py-1 rounded-md font-mono text-[11px] whitespace-nowrap transition-all ${
                    isActive
                      ? 'machine-btn-primary text-slate-950 glow-accent-sm'
                      : 'machine-btn text-slate-400 hover:text-slate-200'
                  }`}
                >
                  {p.label}
                </button>
              );
            })}

          {/* User Collections */}
          {collections.map((c) => {
            const isActive = selectedPlatform === c.id;
            return (
              <div key={c.id} className="relative shrink-0 flex items-center">
                <button
                  onClick={() => setSelectedPlatform(c.id)}
                  className={`px-2.5 py-1 rounded-l-md font-mono text-[11px] whitespace-nowrap tabular-nums transition-all flex items-center gap-1.5 ${
                    isActive
                      ? 'bg-purple-500 text-slate-950 font-bold shadow-[0_0_8px_rgba(168,85,247,0.4)]'
                      : 'machine-btn text-purple-300 hover:text-purple-200 border-purple-500/30'
                  }`}
                >
                  <Folder className="w-3 h-3" />
                  <span>{c.name.toUpperCase()} ({c.videoKeys.length})</span>
                </button>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setCollectionMenuId(collectionMenuId === c.id ? null : c.id);
                  }}
                  className={`px-1.5 py-1 rounded-r-md border-l border-slate-800 font-mono text-[10px] ${
                    isActive ? 'bg-purple-600 text-slate-950' : 'machine-btn text-slate-400 hover:text-slate-200'
                  }`}
                  title="Collection options"
                >
                  •••
                </button>

                {collectionMenuId === c.id && (
                  <div
                    onClick={(e) => e.stopPropagation()}
                    className="absolute left-0 top-full mt-1 w-32 rounded-lg bg-slate-950 border border-purple-500/40 shadow-2xl p-1 z-30 font-mono text-xs space-y-0.5 animate-fade-in"
                  >
                    <button
                      onClick={() => {
                        setCollectionMenuId(null);
                        setRenameCollectionTarget(c);
                        setNewCollectionName(c.name);
                      }}
                      className="w-full px-2 py-1.5 rounded flex items-center gap-1.5 text-slate-300 hover:bg-slate-800 hover:text-accent-soft text-left text-[11px]"
                    >
                      <Edit3 className="w-3 h-3 text-accent" />
                      <span>Rename</span>
                    </button>
                    <button
                      onClick={() => handleDeleteCollection(c.id)}
                      className="w-full px-2 py-1.5 rounded flex items-center gap-1.5 text-red-400 hover:bg-slate-800 hover:text-red-300 text-left text-[11px]"
                    >
                      <Trash2 className="w-3 h-3 text-red-400" />
                      <span>Delete</span>
                    </button>
                  </div>
                )}
              </div>
            );
          })}

          <button
            onClick={() => {
              setNewCollectionName('');
              setCreateCollectionModalOpen(true);
            }}
            className="px-2.5 py-1 rounded-md font-mono text-[11px] whitespace-nowrap transition-all machine-btn text-accent hover:text-accent-soft border-accent flex items-center gap-1 shrink-0"
          >
            <FolderPlus className="w-3 h-3 text-accent" />
            <span>+ COLLECTION</span>
          </button>
        </div>
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
        <EmptyState
          icon={Film}
          title={
            searchQuery || selectedPlatform !== 'all'
              ? 'NO MATCHING CLIPS'
              : 'LIBRARY VOID // NO CLIPS'
          }
          message={
            searchQuery || selectedPlatform !== 'all'
              ? 'Adjust your search keyword or switch filter chips.'
              : 'Videos downloaded with OmniDrop will appear here and stay in Movies/OmniDrop.'
          }
        />
      )}

      {/* GRID VIEW */}
      {viewMode === 'grid' && filteredVideos.length > 0 && (
        <div className="grid grid-cols-2 gap-2.5">
          {filteredVideos.map((video, idx) => {
            const mediaKey = getVideoKey(video);
            const isFav = favorites.includes(mediaKey);
            const isSelected = selectedUris.has(video.uri);

            return (
              <div
                key={video.id || video.uri}
                onTouchStart={() => handleCardPressStart(video.uri)}
                onTouchEnd={handleCardPressEnd}
                onMouseDown={() => handleCardPressStart(video.uri)}
                onMouseUp={handleCardPressEnd}
                onClick={() => {
                  if (isMultiSelectMode) {
                    handleToggleSelectUri(video.uri);
                  } else {
                    handlePlay(video);
                  }
                }}
                style={{ '--stagger': Math.min(idx, 10) } as React.CSSProperties}
                className={`stagger-card machine-surface rounded-xl overflow-hidden flex flex-col cursor-pointer transition-all ${
                  isSelected ? 'border-accent-strong glow-accent-sm' : ''
                }`}
              >
                {/* Big 16:9 Thumbnail */}
                <div className="relative aspect-video bg-slate-950 border-b border-slate-800 overflow-hidden">
                  {video.thumbnail ? (
                    <img
                      src={video.thumbnail}
                      alt={video.title}
                      className="w-full h-full object-cover"
                      loading="lazy"
                    />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center text-slate-700">
                      <Film className="w-7 h-7" />
                    </div>
                  )}

                  <div className="absolute inset-0 bg-black/25 flex items-center justify-center">
                    <div className="w-8 h-8 rounded-full bg-accent text-slate-950 flex items-center justify-center glow-accent-sm">
                      <Play className="w-4 h-4 fill-slate-950 translate-x-0.5" />
                    </div>
                  </div>

                  {/* Favorite Star top-left */}
                  <button
                    onClick={(e) => handleToggleFavorite(video, e)}
                    className={`absolute top-1.5 left-1.5 p-1 rounded-md bg-black/70 border transition-colors ${
                      isFav ? 'border-amber-400/60 text-amber-400' : 'border-slate-700 text-slate-300'
                    }`}
                    title={isFav ? 'Remove from Favorites' : 'Add to Favorites'}
                  >
                    <Star className={`w-3 h-3 ${isFav ? 'fill-amber-400' : ''}`} />
                  </button>

                  {/* Multi-select checkbox top-right */}
                  {isMultiSelectMode && (
                    <div className="absolute top-1.5 right-1.5 bg-slate-950/90 rounded p-0.5">
                      {isSelected ? (
                        <CheckSquare className="w-4 h-4 text-accent" />
                      ) : (
                        <Square className="w-4 h-4 text-slate-400" />
                      )}
                    </div>
                  )}

                  {video.durationMs > 0 && (
                    <span className="absolute bottom-1 right-1 px-1.5 py-0.5 rounded bg-black/85 font-mono text-[9px] text-white tabular-nums">
                      {formatDuration(video.durationMs)}
                    </span>
                  )}
                </div>

                {/* Single-line title & compact meta */}
                <div className="p-2.5 space-y-1.5 flex-1 flex flex-col justify-between">
                  <h4 className="font-mono text-xs font-bold text-white truncate" title={video.title}>
                    {video.title}
                  </h4>

                  <div className="flex items-center justify-between gap-1">
                    <span
                      className={`uppercase px-1.5 py-0.2 rounded border text-[9px] font-mono font-bold truncate ${getPlatformBadgeStyle(
                        video.platform
                      )}`}
                    >
                      {video.platform || 'Other'}
                    </span>
                    <span className="font-mono text-[10px] text-slate-400 tabular-nums whitespace-nowrap shrink-0">
                      {formatSize(video.size)}
                    </span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* LIST VIEW WITH HORIZONTAL SWIPE (LEFT = DELETE CONFIRM, RIGHT = FAVORITE) */}
      {viewMode === 'list' && filteredVideos.length > 0 && (
        <div className="space-y-3">
          {filteredVideos.map((video, idx) => {
            const mediaKey = getVideoKey(video);
            const isFav = favorites.includes(mediaKey);
            const isSelected = selectedUris.has(video.uri);
            const isSwiping = swipeUri === video.uri;
            const currentOffset = isSwiping ? swipeOffset : 0;

            return (
              <div
                key={video.id || video.uri}
                style={{ '--stagger': Math.min(idx, 10) } as React.CSSProperties}
                className="stagger-card relative rounded-xl overflow-hidden"
              >
                {/* Swipe Action Backdrop */}
                {isSwiping && currentOffset !== 0 && (
                  <div
                    className={`absolute inset-0 rounded-xl flex items-center px-5 font-mono text-xs font-bold uppercase tracking-wider ${
                      currentOffset > 0
                        ? 'justify-start bg-amber-500/20 border border-amber-400/50 text-amber-300'
                        : 'justify-end bg-rose-500/20 border border-rose-400/50 text-rose-300'
                    }`}
                  >
                    {currentOffset > 0 ? (
                      <div className="flex items-center gap-2 whitespace-nowrap">
                        <Star className={`w-4 h-4 ${isFav ? '' : 'fill-amber-400'}`} />
                        <span>{isFav ? 'UNSTAR' : 'FAVORITE'}</span>
                      </div>
                    ) : (
                      <div className="flex items-center gap-2 whitespace-nowrap">
                        <span>DELETE</span>
                        <Trash2 className="w-4 h-4" />
                      </div>
                    )}
                  </div>
                )}

                {/* Draggable Card Foreground */}
                <div
                  onTouchStart={(e) => handleSwipeTouchStart(video.uri, e)}
                  onTouchMove={(e) => handleSwipeTouchMove(video.uri, e)}
                  onTouchEnd={() => handleSwipeTouchEnd(video)}
                  onTouchCancel={() => {
                    swipeTouchRef.current = null;
                    setSwipeUri(null);
                    setSwipeOffset(0);
                    handleCardPressEnd();
                  }}
                  onMouseDown={() => handleCardPressStart(video.uri)}
                  onMouseUp={handleCardPressEnd}
                  onClick={() => {
                    if (justSwipedRef.current) return;
                    if (isMultiSelectMode) {
                      handleToggleSelectUri(video.uri);
                    }
                  }}
                  style={{
                    transform: currentOffset !== 0 ? `translateX(${currentOffset}px)` : undefined,
                    transition: isSwiping ? 'none' : 'transform 200ms cubic-bezier(0.22, 1, 0.36, 1)',
                  }}
                  className={`machine-surface group relative p-3 rounded-xl flex flex-col gap-2.5 ${
                    isSelected ? 'border-accent-strong bg-accent-subtle glow-accent-sm' : ''
                  }`}
                >
                  <div className="flex gap-3 items-center">
                    {isMultiSelectMode && (
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          handleToggleSelectUri(video.uri);
                        }}
                        className="p-1 text-accent shrink-0"
                      >
                        {isSelected ? (
                          <CheckSquare className="w-5 h-5 text-accent" />
                        ) : (
                          <Square className="w-5 h-5 text-slate-600 hover:text-slate-400" />
                        )}
                      </button>
                    )}

                    <button
                      onClick={(e) => {
                        if (justSwipedRef.current) return;
                        if (isMultiSelectMode) {
                          e.stopPropagation();
                          handleToggleSelectUri(video.uri);
                        } else {
                          handlePlay(video);
                        }
                      }}
                      className="relative w-22 h-15 rounded-lg overflow-hidden bg-slate-950 shrink-0 border border-slate-800 group-hover:border-accent transition-colors text-left"
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

                      <div className="absolute inset-0 bg-black/40 flex items-center justify-center group-hover:bg-black/20 transition-all">
                        <div className="w-7 h-7 rounded-full bg-accent text-slate-950 flex items-center justify-center glow-accent-sm">
                          <Play className="w-3.5 h-3.5 fill-slate-950 translate-x-0.5" />
                        </div>
                      </div>

                      {video.durationMs > 0 && (
                        <span className="absolute bottom-1 right-1 px-1 py-0.2 rounded bg-black/80 font-mono text-[9px] text-white tabular-nums">
                          {formatDuration(video.durationMs)}
                        </span>
                      )}
                    </button>

                    <div className="flex-1 min-w-0 pr-1">
                      <h4
                        onClick={() => {
                          if (justSwipedRef.current) return;
                          if (isMultiSelectMode) {
                            handleToggleSelectUri(video.uri);
                          } else {
                            handlePlay(video);
                          }
                        }}
                        className="font-mono text-xs font-bold text-white truncate cursor-pointer hover:text-accent-soft transition"
                        title={video.title}
                      >
                        {video.title}
                      </h4>

                      <div className="flex items-center gap-2 mt-1.5 whitespace-nowrap">
                        <span
                          className={`uppercase px-1.5 py-0.2 rounded border text-[10px] font-mono tracking-wider font-bold ${getPlatformBadgeStyle(
                            video.platform
                          )}`}
                        >
                          {video.platform || 'Other'}
                        </span>

                        <span className="font-mono text-[11px] text-slate-400 tabular-nums">
                          {formatSize(video.size)}
                        </span>

                        {video.dateAdded > 0 && (
                          <span className="flex items-center gap-1 font-mono text-[10px] text-slate-500 tabular-nums">
                            <Clock className="w-3 h-3" />
                            {formatDate(video.dateAdded)}
                          </span>
                        )}
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center justify-between pt-2 border-t border-slate-800/80">
                    <div className="flex items-center gap-2">
                      <button
                        onClick={() => handlePlay(video)}
                        className="machine-btn px-3 py-1 rounded-md text-xs font-mono font-bold text-accent-soft flex items-center gap-1.5 whitespace-nowrap"
                      >
                        <Play className="w-3 h-3 fill-current text-accent" />
                        <span>PLAY</span>
                      </button>

                      <button
                        onClick={(e) => handleToggleFavorite(video, e)}
                        className={`machine-btn p-1.5 rounded-md transition-all ${
                          isFav
                            ? 'text-amber-400 border-amber-500/50 shadow-[0_0_8px_rgba(251,191,36,0.3)] bg-amber-950/20'
                            : 'text-slate-400 hover:text-amber-300'
                        }`}
                        title={isFav ? 'Remove from Favorites' : 'Add to Favorites'}
                      >
                        <Star className={`w-3.5 h-3.5 ${isFav ? 'fill-amber-400' : ''}`} />
                      </button>
                    </div>

                    <div className="relative">
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          setActiveMenuId(activeMenuId === video.uri ? null : video.uri);
                        }}
                        className="machine-btn p-1.5 rounded-md text-slate-400 hover:text-accent-soft"
                        title="Card options"
                      >
                        <MoreVertical className="w-3.5 h-3.5" />
                      </button>

                      {activeMenuId === video.uri && (
                        <div
                          onClick={(e) => e.stopPropagation()}
                          className="absolute right-0 bottom-full mb-1 w-44 rounded-lg bg-slate-950 border border-accent shadow-2xl p-1 z-30 font-mono text-xs space-y-0.5 animate-fade-in"
                        >
                          <button
                            onClick={() => handleOpenRename(video)}
                            className="w-full px-2.5 py-1.5 rounded flex items-center gap-2 text-slate-300 hover:bg-slate-800 hover:text-accent-soft text-left"
                          >
                            <Edit3 className="w-3.5 h-3.5 text-accent" />
                            <span>Rename</span>
                          </button>
                          <button
                            onClick={() => {
                              setActiveMenuId(null);
                              setTargetVideosForCollection([video]);
                              setAddToCollectionModalOpen(true);
                            }}
                            className="w-full px-2.5 py-1.5 rounded flex items-center gap-2 text-slate-300 hover:bg-slate-800 hover:text-accent-soft text-left"
                          >
                            <Folder className="w-3.5 h-3.5 text-purple-400" />
                            <span>Add to Collection</span>
                          </button>
                          <button
                            onClick={() => handleStartAudioExtraction(video)}
                            className="w-full px-2.5 py-1.5 rounded flex items-center gap-2 text-slate-300 hover:bg-slate-800 hover:text-accent-soft text-left"
                          >
                            <Music className="w-3.5 h-3.5 text-accent" />
                            <span>Extract audio</span>
                          </button>
                          <button
                            onClick={() => {
                              setActiveMenuId(null);
                              handleShare(video);
                            }}
                            className="w-full px-2.5 py-1.5 rounded flex items-center gap-2 text-slate-300 hover:bg-slate-800 hover:text-accent-soft text-left"
                          >
                            <Share2 className="w-3.5 h-3.5 text-accent" />
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

                  {deleteConfirmId === video.uri && (
                    <div className="p-2 rounded-lg bg-rose-950/60 border border-rose-500/50 flex items-center justify-between font-mono text-xs animate-fade-in">
                      <span className="text-rose-200 truncate pr-2">Confirm file deletion?</span>
                      <div className="flex items-center gap-1.5 shrink-0">
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
              </div>
            );
          })}
        </div>
      )}

      {/* Find Duplicates Modal */}
      {duplicateModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm">
          <div className="machine-surface w-full max-w-md max-h-[85vh] flex flex-col rounded-xl shadow-2xl">
            <div className="p-3.5 border-b border-accent flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Layers className="w-4 h-4 text-accent" />
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
                <EmptyState
                  compact
                  tone="emerald"
                  icon={Check}
                  title="NO DUPLICATES DETECTED"
                  message="All video files in your library have unique sizes and durations."
                />
              ) : (
                <>
                  <div className="p-3 rounded-lg bg-accent-soft border border-accent text-accent-soft">
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
                  className="machine-btn-danger px-4 py-1.5 rounded-lg font-mono text-xs font-bold uppercase transition-all"
                >
                  {isDeletingDuplicates
                    ? 'PURGING...'
                    : purgeResultText
                    ? purgeResultText
                    : `PURGE ${totalExtraDuplicates} COPIES`}
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
            <div className="flex items-center justify-between pb-2 border-b border-accent">
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
                  className="machine-input w-full rounded-lg px-3 py-2 font-mono text-xs text-white outline-none"
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

      {/* Floating Multi-Select Action Bar */}
      {(isMultiSelectMode || selectedUris.size > 0) && (
        <div className="fixed bottom-20 left-4 right-4 z-40 p-2.5 rounded-xl bg-slate-950/95 border border-accent-strong glow-accent flex items-center justify-between gap-2 font-mono text-xs animate-in slide-in-from-bottom duration-200">
          <div className="flex items-center gap-2 pl-1">
            <span className="w-2 h-2 rounded-full led-accent animate-pulse" />
            <span className="font-bold text-white uppercase tracking-wider tabular-nums whitespace-nowrap">
              {selectedUris.size} SELECTED
            </span>
          </div>

          <div className="flex items-center gap-1.5 flex-wrap">
            <button
              onClick={handleSelectAll}
              className="machine-btn px-2 py-1 rounded text-[11px] text-slate-300 hover:text-white"
            >
              ALL
            </button>
            <button
              onClick={handleFavoriteSelected}
              className="machine-btn px-2 py-1 rounded text-[11px] text-amber-400 hover:text-amber-300 flex items-center gap-1"
              title="Toggle Favorite"
            >
              <Star className="w-3 h-3 fill-amber-400" />
              <span>STAR</span>
            </button>
            <button
              onClick={() => {
                const targetVids = videos.filter((v) => selectedUris.has(v.uri));
                setTargetVideosForCollection(targetVids);
                setAddToCollectionModalOpen(true);
              }}
              className="machine-btn px-2 py-1 rounded text-[11px] text-purple-400 hover:text-purple-300 flex items-center gap-1"
              title="Add to Collection"
            >
              <Folder className="w-3 h-3 text-purple-400" />
              <span>COLLECT</span>
            </button>
            <button
              onClick={handleDeleteSelected}
              className="machine-btn-danger px-2 py-1 rounded text-[11px] font-bold text-rose-300 hover:text-white flex items-center gap-1"
              title="Delete Selected"
            >
              <Trash2 className="w-3 h-3" />
              <span>DEL</span>
            </button>
            <button
              onClick={handleClearSelection}
              className="machine-btn px-2 py-1 rounded text-[11px] text-slate-400 hover:text-white"
              title="Cancel Selection"
            >
              <X className="w-3 h-3" />
            </button>
          </div>
        </div>
      )}

      {/* Create Collection Modal */}
      {createCollectionModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm">
          <div className="machine-surface w-full max-w-sm rounded-xl p-4 space-y-3.5 shadow-2xl border border-accent">
            <div className="flex items-center justify-between pb-2 border-b border-accent">
              <div className="flex items-center gap-2">
                <FolderPlus className="w-4 h-4 text-accent" />
                <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
                  NEW COLLECTION
                </h3>
              </div>
              <button
                onClick={() => setCreateCollectionModalOpen(false)}
                className="text-slate-400 hover:text-white"
              >
                ✕
              </button>
            </div>

            <div className="space-y-2">
              <label className="block text-[10px] font-mono text-slate-400 uppercase">
                COLLECTION NAME
              </label>
              <input
                type="text"
                autoFocus
                placeholder="e.g. Anime edits, Car clips, Dance"
                value={newCollectionName}
                onChange={(e) => setNewCollectionName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleCreateCollection();
                }}
                className="machine-input w-full rounded-lg px-3 py-2 font-mono text-xs text-white outline-none"
              />
            </div>

            <div className="pt-2 flex items-center justify-end gap-2">
              <button
                onClick={() => setCreateCollectionModalOpen(false)}
                className="machine-btn px-3 py-1.5 rounded-lg font-mono text-xs text-slate-400"
              >
                CANCEL
              </button>
              <button
                onClick={handleCreateCollection}
                disabled={!newCollectionName.trim()}
                className="machine-btn-primary px-4 py-1.5 rounded-lg font-mono text-xs font-bold uppercase disabled:opacity-50"
              >
                CREATE
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Rename Collection Modal */}
      {renameCollectionTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm">
          <div className="machine-surface w-full max-w-sm rounded-xl p-4 space-y-3.5 shadow-2xl border border-purple-500/40">
            <div className="flex items-center justify-between pb-2 border-b border-purple-500/20">
              <div className="flex items-center gap-2">
                <Edit3 className="w-4 h-4 text-purple-400" />
                <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
                  RENAME COLLECTION
                </h3>
              </div>
              <button
                onClick={() => setRenameCollectionTarget(null)}
                className="text-slate-400 hover:text-white"
              >
                ✕
              </button>
            </div>

            <div className="space-y-2">
              <label className="block text-[10px] font-mono text-slate-400 uppercase">
                NEW NAME
              </label>
              <input
                type="text"
                autoFocus
                value={newCollectionName}
                onChange={(e) => setNewCollectionName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleSaveRenameCollection();
                }}
                className="w-full rounded-lg bg-slate-950 border border-slate-800 focus:border-purple-400 px-3 py-2 font-mono text-xs text-white outline-none"
              />
            </div>

            <div className="pt-2 flex items-center justify-end gap-2">
              <button
                onClick={() => setRenameCollectionTarget(null)}
                className="machine-btn px-3 py-1.5 rounded-lg font-mono text-xs text-slate-400"
              >
                CANCEL
              </button>
              <button
                onClick={handleSaveRenameCollection}
                disabled={!newCollectionName.trim()}
                className="machine-btn-primary px-4 py-1.5 rounded-lg font-mono text-xs font-bold uppercase disabled:opacity-50"
              >
                SAVE
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Add To Collection Modal */}
      {addToCollectionModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm">
          <div className="machine-surface w-full max-w-sm max-h-[80vh] flex flex-col rounded-xl p-4 space-y-3 shadow-2xl border border-purple-500/40">
            <div className="flex items-center justify-between pb-2 border-b border-purple-500/20">
              <div className="flex items-center gap-2">
                <Folder className="w-4 h-4 text-purple-400" />
                <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
                  ADD TO COLLECTION
                </h3>
              </div>
              <button
                onClick={() => {
                  setAddToCollectionModalOpen(false);
                  setTargetVideosForCollection([]);
                }}
                className="text-slate-400 hover:text-white"
              >
                ✕
              </button>
            </div>

            <p className="font-mono text-[11px] text-slate-400">
              Select collection for {targetVideosForCollection.length} clip{targetVideosForCollection.length > 1 ? 's' : ''}:
            </p>

            <div className="space-y-1.5 overflow-y-auto max-h-56 pr-1">
              {collections.length === 0 ? (
                <EmptyState
                  compact
                  icon={FolderPlus}
                  title="NO COLLECTIONS YET"
                  message="Create your first collection below to organize videos."
                />
              ) : (
                collections.map((col) => {
                  const targetKeys = targetVideosForCollection.map(getVideoKey);
                  const allAlreadyIn = targetKeys.every((k) => col.videoKeys.includes(k));

                  return (
                    <button
                      key={col.id}
                      onClick={() => handleAddVideosToCollection(col.id)}
                      className="w-full p-2.5 rounded-lg bg-slate-950 border border-slate-800 hover:border-purple-500/60 flex items-center justify-between transition-colors text-left"
                    >
                      <div className="flex items-center gap-2 font-mono text-xs text-slate-200">
                        <Folder className="w-3.5 h-3.5 text-purple-400 shrink-0" />
                        <span className="font-bold truncate">{col.name}</span>
                      </div>
                      <span className="font-mono text-[10px] text-slate-400 shrink-0">
                        {allAlreadyIn ? 'ALREADY ADDED' : `${col.videoKeys.length} items`}
                      </span>
                    </button>
                  );
                })
              )}
            </div>

            <div className="pt-2 border-t border-slate-800 flex items-center justify-between gap-2">
              <button
                onClick={() => {
                  setAddToCollectionModalOpen(false);
                  setNewCollectionName('');
                  setCreateCollectionModalOpen(true);
                }}
                className="machine-btn px-2.5 py-1.5 rounded-lg font-mono text-xs text-accent hover:text-accent-soft flex items-center gap-1"
              >
                <Plus className="w-3 h-3" />
                <span>NEW</span>
              </button>

              <button
                onClick={() => {
                  setAddToCollectionModalOpen(false);
                  setTargetVideosForCollection([]);
                }}
                className="machine-btn px-3 py-1.5 rounded-lg font-mono text-xs text-slate-400"
              >
                CANCEL
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Audio Extraction Modal with Progress Bar and Open/Share */}
      {audioExtractModal.isOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/85 backdrop-blur-sm animate-in fade-in duration-200">
          <div className="machine-surface w-full max-w-sm rounded-xl p-5 space-y-4 shadow-2xl border border-accent">
            <div className="flex items-center justify-between pb-2 border-b border-accent">
              <div className="flex items-center gap-2">
                <Music className="w-4 h-4 text-accent" />
                <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
                  EXTRACT AUDIO // .M4A
                </h3>
              </div>
              {!audioExtractModal.isExtracting && (
                <button
                  onClick={() => setAudioExtractModal((prev) => ({ ...prev, isOpen: false }))}
                  className="text-slate-400 hover:text-white"
                >
                  <X className="w-4 h-4" />
                </button>
              )}
            </div>

            <div className="space-y-1">
              <p className="font-mono text-xs text-white truncate font-bold" title={audioExtractModal.video?.title}>
                {audioExtractModal.video?.title}
              </p>
              <p className="font-mono text-[10px] text-slate-400">
                Direct demux to MPEG-4 Audio (.m4a) without lossy re-encoding
              </p>
            </div>

            {/* Progress Bar Container */}
            <div className="space-y-2">
              <div className="h-2 w-full bg-slate-950 rounded-full overflow-hidden border border-slate-800">
                <div
                  style={{ width: `${audioExtractModal.progress}%` }}
                  className={`h-full transition-all duration-150 ${
                    audioExtractModal.error
                      ? 'bg-rose-500'
                      : audioExtractModal.progress === 100
                      ? 'bg-emerald-400 shadow-[0_0_8px_rgba(16,185,129,0.7)]'
                      : 'bg-accent glow-accent-sm'
                  }`}
                />
              </div>

              <div className="flex justify-between font-mono text-[10px]">
                <span className={audioExtractModal.error ? 'text-rose-400' : audioExtractModal.progress === 100 ? 'text-emerald-400' : 'text-accent-soft'}>
                  {audioExtractModal.error
                    ? 'Extraction failed'
                    : audioExtractModal.progress === 100
                    ? 'Saved to Music/OmniDrop'
                    : `Demuxing audio track (${audioExtractModal.progress}%)...`}
                </span>
                <span className="text-slate-500 font-bold tabular-nums">{audioExtractModal.progress}%</span>
              </div>
            </div>

            {/* Error Message if any */}
            {audioExtractModal.error && (
              <div className="p-3 rounded-lg bg-rose-950/60 border border-rose-500/40 font-mono text-[11px] text-rose-300 leading-relaxed">
                {audioExtractModal.error}
              </div>
            )}

            {/* Success details if finished */}
            {!audioExtractModal.isExtracting && audioExtractModal.resultUri && !audioExtractModal.error && (
              <div className="p-3 rounded-lg bg-emerald-950/40 border border-emerald-500/30 space-y-1 font-mono text-xs">
                <div className="text-emerald-300 font-bold flex items-center gap-1.5">
                  <Check className="w-3.5 h-3.5 text-emerald-400" />
                  <span>Audio Saved Successfully</span>
                </div>
                <div className="text-slate-400 text-[11px] truncate">
                  {audioExtractModal.resultFileName}
                </div>
              </div>
            )}

            {/* Action buttons */}
            <div className="pt-2 flex items-center justify-end gap-2 font-mono text-xs">
              {!audioExtractModal.isExtracting && audioExtractModal.resultUri && !audioExtractModal.error ? (
                <>
                  <button
                    onClick={() => {
                      if (audioExtractModal.resultUri) {
                        VideoDownloader.openAudio({ uri: audioExtractModal.resultUri });
                      }
                    }}
                    className="machine-btn-primary px-3.5 py-1.5 rounded-lg flex items-center gap-1.5 text-slate-950 font-bold uppercase"
                  >
                    <span>OPEN AUDIO</span>
                  </button>
                  <button
                    onClick={() => {
                      if (audioExtractModal.resultUri) {
                        VideoDownloader.shareAudio({
                          uri: audioExtractModal.resultUri,
                          title: audioExtractModal.resultFileName || undefined,
                        });
                      }
                    }}
                    className="machine-btn px-3.5 py-1.5 rounded-lg text-accent-soft hover:text-white flex items-center gap-1.5"
                  >
                    <Share2 className="w-3.5 h-3.5" />
                    <span>SHARE</span>
                  </button>
                  <button
                    onClick={() => setAudioExtractModal((prev) => ({ ...prev, isOpen: false }))}
                    className="machine-btn px-3 py-1.5 rounded-lg text-slate-400"
                  >
                    CLOSE
                  </button>
                </>
              ) : !audioExtractModal.isExtracting ? (
                <button
                  onClick={() => setAudioExtractModal((prev) => ({ ...prev, isOpen: false }))}
                  className="machine-btn px-4 py-1.5 rounded-lg text-slate-300"
                >
                  DISMISS
                </button>
              ) : null}
            </div>
          </div>
        </div>
      )}

      {/* Styled Dark Robotic Modal for System Messages & Errors (never window.alert) */}
      {roboticNotice && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center p-4 bg-black/85 backdrop-blur-sm animate-in fade-in duration-200">
          <div className="machine-surface w-full max-w-sm rounded-xl p-5 space-y-4 shadow-2xl border border-accent">
            <div className="flex items-center gap-2.5">
              <span
                className={`inline-block w-2.5 h-2.5 rounded-full ${
                  roboticNotice.isError
                    ? 'led-red'
                    : 'led-accent'
                }`}
              />
              <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider flex-1">
                {roboticNotice.title}
              </h3>
            </div>
            <p className="font-mono text-xs text-slate-300 leading-relaxed bg-slate-950/60 p-3 rounded-lg border border-slate-800">
              {roboticNotice.message}
            </p>
            <div className="flex justify-end pt-1">
              <button
                onClick={() => setRoboticNotice(null)}
                className="machine-btn-primary px-4 py-1.5 rounded-lg font-mono text-xs font-bold uppercase"
              >
                ACKNOWLEDGE
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Styled Permission Notice Banner */}
      {permissionNotice && (
        <div className="fixed top-4 left-1/2 -translate-x-1/2 z-[70] px-4 py-2.5 rounded-xl bg-slate-950/95 border border-accent-strong glow-accent flex items-center gap-2.5 animate-in slide-in-from-top duration-200">
          <span className="w-2 h-2 rounded-full led-accent animate-ping" />
          <span className="font-mono text-xs text-accent-soft font-bold uppercase tracking-wider whitespace-nowrap">
            {permissionNotice}
          </span>
        </div>
      )}
    </div>
  );
};
