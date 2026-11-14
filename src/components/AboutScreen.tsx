import React, { useState, useCallback, useEffect } from 'react';
import {
  BookOpen,
  ShieldCheck,
  User,
  Info,
  ChevronRight,
  ArrowLeft,
  Sparkles,
  ExternalLink,
  Cpu,
  Mail,
  Smartphone,
  Globe,
  CheckCircle2,
  Terminal,
  Server,
  Layers,
  Music,
  Bot,
  Copy,
  Trash2,
  BarChart3,
  RotateCcw,
  TrendingUp,
  Calendar,
  HardDrive,
  Film,
  Camera,
  AlertTriangle,
  RefreshCw,
} from 'lucide-react';
import { VideoDownloader } from '../plugins/videoDownloader';
import { AppUpdateInfo, DownloadStatsData } from '../types';
import { EmptyState } from './EmptyState';
import { GITHUB_REPO } from '../services/updateService';

type SubScreen = 'whatsnew' | 'guide' | 'privacy' | 'developer' | 'info' | 'stats' | null;

interface AboutScreenProps {
  latestRelease?: AppUpdateInfo | null;
  hasUnreadWhatsNew?: boolean;
  onMarkWhatsNewSeen?: (tagName: string) => void;
  onRefreshRelease?: () => Promise<any>;
}

export const AboutScreen: React.FC<AboutScreenProps> = ({
  latestRelease = null,
  hasUnreadWhatsNew = false,
  onMarkWhatsNewSeen,
  onRefreshRelease,
}) => {
  const [activeSubScreen, setActiveSubScreen] = useState<SubScreen>(null);
  const [isRefreshingRelease, setIsRefreshingRelease] = useState(false);
  const [crashLog, setCrashLog] = useState<string>('');
  const [isCopied, setIsCopied] = useState(false);
  const [networkLog, setNetworkLog] = useState<string>('');
  const [isNetworkCopied, setIsNetworkCopied] = useState(false);
  const [appVersion, setAppVersion] = useState<{ versionName: string; versionCode: number }>({
    versionName: '1.0.0',
    versionCode: 1,
  });
  const [stats, setStats] = useState<DownloadStatsData | null>(null);
  const [isLoadingStats, setIsLoadingStats] = useState(false);
  const [resetConfirmOpen, setResetConfirmOpen] = useState(false);
  const [isResetting, setIsResetting] = useState(false);

  useEffect(() => {
    VideoDownloader.getAppVersion()
      .then((ver) => {
        if (ver && ver.versionCode) {
          setAppVersion(ver);
        }
      })
      .catch(() => {});
  }, []);

  const loadStats = useCallback(async () => {
    setIsLoadingStats(true);
    try {
      const res = await VideoDownloader.getDownloadStats();
      if (res && res.totalDownloads > 0) {
        setStats(res);
      } else {
        // Fallback baseline from existing media libraries
        const [vRes, pRes] = await Promise.all([
          VideoDownloader.getVideoLibrary().catch(() => ({ videos: [] })),
          VideoDownloader.getPhotoLibrary().catch(() => ({ photos: [] })),
        ]);
        const videos = vRes?.videos || [];
        const photos = pRes?.photos || [];
        const total = videos.length + photos.length;
        const totalBytes =
          videos.reduce((acc: number, v: any) => acc + (v.size || 0), 0) +
          photos.reduce((acc: number, p: any) => acc + (p.size || 0), 0);

        const split: Record<string, number> = {};
        for (const v of videos) {
          const p = v.platform || 'Other';
          split[p] = (split[p] || 0) + 1;
        }
        for (const p of photos) {
          const plat = p.platform || 'TikTok';
          split[plat] = (split[plat] || 0) + 1;
        }

        const historyMap = new Map<string, { count: number; bytes: number }>();
        const today = new Date();
        for (let i = 13; i >= 0; i--) {
          const d = new Date(today);
          d.setDate(today.getDate() - i);
          const ds = d.toISOString().split('T')[0];
          historyMap.set(ds, { count: 0, bytes: 0 });
        }

        for (const v of videos) {
          if (v.dateAdded) {
            const ds = new Date(v.dateAdded).toISOString().split('T')[0];
            if (historyMap.has(ds)) {
              const cur = historyMap.get(ds)!;
              cur.count += 1;
              cur.bytes += v.size || 0;
            }
          }
        }
        for (const p of photos) {
          if (p.dateAdded) {
            const ds = new Date(p.dateAdded).toISOString().split('T')[0];
            if (historyMap.has(ds)) {
              const cur = historyMap.get(ds)!;
              cur.count += 1;
              cur.bytes += p.size || 0;
            }
          }
        }

        const dailyStats: Array<{ date: string; downloads: number; bytes: number }> = [];
        let busiestDayObj = { date: '', count: 0 };
        for (const [date, val] of historyMap.entries()) {
          dailyStats.push({ date, downloads: val.count, bytes: val.bytes });
          if (val.count > busiestDayObj.count) {
            busiestDayObj = { date, count: val.count };
          }
        }

        const busiestDayStr = busiestDayObj.date
          ? `${busiestDayObj.date} (${busiestDayObj.count} dls)`
          : 'None';

        setStats({
          totalDownloads: total,
          totalBytes,
          videoCount: videos.length,
          photoCount: photos.length,
          failedCount: 0,
          platformCounts: split,
          dailyStats,
          busiestDay: busiestDayStr,
          averageBytes: total > 0 ? Math.round(totalBytes / total) : 0,
          successRate: total > 0 ? 100 : 0,
        });
      }
    } catch (e) {
      console.warn('Failed to load stats', e);
    } finally {
      setIsLoadingStats(false);
    }
  }, []);

  useEffect(() => {
    if (activeSubScreen === 'info') {
      VideoDownloader.getLastCrash()
        .then((res) => {
          setCrashLog(res?.crash || '');
        })
        .catch(() => setCrashLog(''));
      VideoDownloader.getNetworkLog()
        .then((res) => {
          setNetworkLog(res?.log || '');
        })
        .catch(() => setNetworkLog(''));
    } else if (activeSubScreen === 'stats') {
      loadStats();
    } else if (activeSubScreen === 'whatsnew' && latestRelease?.tagName && onMarkWhatsNewSeen) {
      onMarkWhatsNewSeen(latestRelease.tagName);
    }
  }, [activeSubScreen, loadStats, latestRelease?.tagName, onMarkWhatsNewSeen]);

  const handleResetStats = async () => {
    setIsResetting(true);
    try {
      await VideoDownloader.resetDownloadStats();
      setStats({
        totalDownloads: 0,
        totalBytes: 0,
        videoCount: 0,
        photoCount: 0,
        failedCount: 0,
        platformCounts: {},
        dailyStats: [],
        busiestDay: 'None',
        averageBytes: 0,
        successRate: 0,
      });
      setResetConfirmOpen(false);
    } catch (err) {
      console.warn('Failed to reset stats', err);
    } finally {
      setIsResetting(false);
    }
  };

  const formatStatsBytes = (bytes: number) => {
    if (!bytes || bytes === 0) return '0 B';
    const mb = bytes / (1024 * 1024);
    if (mb < 1) return `${(bytes / 1024).toFixed(1)} KB`;
    if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`;
    return `${mb.toFixed(1)} MB`;
  };

  const handleCopyCrash = async () => {
    if (!crashLog) return;
    try {
      await navigator.clipboard.writeText(crashLog);
      setIsCopied(true);
      setTimeout(() => setIsCopied(false), 2000);
    } catch {}
  };

  const handleClearCrash = async () => {
    try {
      await VideoDownloader.clearLastCrash();
      setCrashLog('');
    } catch {}
  };

  const handleCopyNetworkLog = async () => {
    if (!networkLog) return;
    try {
      await navigator.clipboard.writeText(networkLog);
      setIsNetworkCopied(true);
      setTimeout(() => setIsNetworkCopied(false), 2000);
    } catch {}
  };

  const handleClearNetworkLog = async () => {
    try {
      await VideoDownloader.clearNetworkLog();
      setNetworkLog('');
    } catch {}
  };

  const menuItems = [
    {
      id: 'whatsnew' as SubScreen,
      code: 'NEWS // 01',
      title: "What's New",
      subtitle: latestRelease?.tagName
        ? `Release notes & changelog for ${latestRelease.tagName}`
        : 'Latest release notes and GitHub changelog',
      icon: Sparkles,
      color: 'text-accent',
      badge: latestRelease?.tagName || 'CHANGELOG',
      unread: hasUnreadWhatsNew,
    },
    {
      id: 'guide' as SubScreen,
      code: 'DOC // 02',
      title: 'User Guide',
      subtitle: 'Complete manual for downloads, gestures, edge tab & troubleshooting',
      icon: BookOpen,
      color: 'text-accent',
      badge: 'GUIDE',
    },
    {
      id: 'privacy' as SubScreen,
      code: 'SEC // 03',
      title: 'Privacy Policy',
      subtitle: 'Zero ads, zero telemetry, local storage only & permissions audit',
      icon: ShieldCheck,
      color: 'text-emerald-400',
      badge: 'VERIFIED',
    },
    {
      id: 'developer' as SubScreen,
      code: 'DEV // 04',
      title: 'About the Developer',
      subtitle: 'JTech • Independent creator based in Sierra Leone',
      icon: User,
      color: 'text-amber-400',
      badge: 'CREATOR',
    },
    {
      id: 'info' as SubScreen,
      code: 'SYS // 05',
      title: 'App Specifications',
      subtitle: `v1.0.${appVersion.versionCode} (Build ${appVersion.versionCode}) • Hybrid runtime & open-source stack`,
      icon: Info,
      color: 'text-purple-400',
      badge: `v1.0.${appVersion.versionCode}`,
    },
    {
      id: 'stats' as SubScreen,
      code: 'METRICS // 06',
      title: 'Download Statistics',
      subtitle: 'Volume, bandwidth saved, platform breakdown, 14-day history & efficiency',
      icon: BarChart3,
      color: 'text-accent',
      badge: 'STATS',
    },
  ];

  // SUB-SCREEN 0: WHAT'S NEW (LATEST GITHUB RELEASE NOTES)
  if (activeSubScreen === 'whatsnew') {
    const publishedDate = latestRelease?.publishedAt
      ? new Date(latestRelease.publishedAt).toLocaleDateString(undefined, {
          year: 'numeric',
          month: 'short',
          day: 'numeric',
        })
      : '';

    const handleFetchLatest = async () => {
      if (!onRefreshRelease) return;
      setIsRefreshingRelease(true);
      try {
        const res = await onRefreshRelease();
        if (res?.updateInfo?.tagName && onMarkWhatsNewSeen) {
          onMarkWhatsNewSeen(res.updateInfo.tagName);
        }
      } finally {
        setIsRefreshingRelease(false);
      }
    };

    return (
      <div className="space-y-4 pb-28 animate-fade-in">
        <div className="flex items-center gap-3 pb-2 border-b border-accent">
          <button
            onClick={() => setActiveSubScreen(null)}
            className="machine-btn px-3 py-1.5 rounded-lg flex items-center gap-1.5 text-xs font-mono text-accent-soft whitespace-nowrap"
          >
            <ArrowLeft className="w-3.5 h-3.5" />
            <span>BACK // MENU</span>
          </button>
          <div className="text-right flex-1 min-w-0">
            <span className="font-mono text-[11px] text-accent font-bold uppercase tracking-widest truncate">
              NEWS // WHAT&apos;S NEW
            </span>
          </div>
        </div>

        {!latestRelease ? (
          <div className="space-y-3">
            <EmptyState
              icon={Sparkles}
              title="NO RELEASE NOTES LOADED"
              message="Fetch the latest OmniDrop release notes directly from GitHub."
            />
            {onRefreshRelease && (
              <button
                onClick={handleFetchLatest}
                disabled={isRefreshingRelease}
                className="machine-btn-primary w-full py-2.5 rounded-lg font-mono text-xs uppercase flex items-center justify-center gap-2"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${isRefreshingRelease ? 'animate-spin' : ''}`} />
                <span>{isRefreshingRelease ? 'CHECKING GITHUB...' : 'FETCH RELEASE NOTES'}</span>
              </button>
            )}
          </div>
        ) : (
          <div className="space-y-3">
            <div className="machine-surface rounded-xl p-4 space-y-3">
              <div className="flex items-center justify-between gap-2 pb-2.5 border-b border-slate-800">
                <div className="flex items-center gap-2 min-w-0">
                  <span className="w-2.5 h-2.5 rounded-full led-accent shrink-0" />
                  <div className="min-w-0">
                    <h3 className="font-mono text-sm font-bold text-white uppercase tracking-wide truncate">
                      RELEASE {latestRelease.tagName || 'LATEST'}
                    </h3>
                    {publishedDate && (
                      <p className="font-mono text-[10px] text-slate-400 tabular-nums whitespace-nowrap">
                        PUBLISHED: {publishedDate.toUpperCase()}
                      </p>
                    )}
                  </div>
                </div>

                {onRefreshRelease && (
                  <button
                    onClick={handleFetchLatest}
                    disabled={isRefreshingRelease}
                    className="machine-btn px-2.5 py-1.5 rounded-lg font-mono text-[11px] text-accent-soft flex items-center gap-1.5 shrink-0 whitespace-nowrap"
                  >
                    <RefreshCw className={`w-3 h-3 ${isRefreshingRelease ? 'animate-spin' : ''}`} />
                    <span>REFRESH</span>
                  </button>
                )}
              </div>

              <div className="p-3 rounded-lg bg-slate-950 border border-slate-800 font-mono text-xs text-slate-200 whitespace-pre-wrap leading-relaxed select-text max-h-[55vh] overflow-y-auto">
                {latestRelease.releaseNotes || 'No release notes provided for this build.'}
              </div>

              <div className="flex items-center justify-between gap-2 pt-1 text-[11px] font-mono">
                <span className="text-slate-400 truncate">
                  Installed: <strong className="text-white tabular-nums">v1.0.{appVersion.versionCode}</strong>
                </span>
                <a
                  href={`https://github.com/${GITHUB_REPO}/releases/latest`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="machine-btn px-2.5 py-1 rounded-md text-accent-soft flex items-center gap-1.5 shrink-0 whitespace-nowrap"
                >
                  <ExternalLink className="w-3 h-3 text-accent" />
                  <span>GITHUB</span>
                </a>
              </div>
            </div>
          </div>
        )}
      </div>
    );
  }

  // SUB-SCREEN 1: USER GUIDE
  if (activeSubScreen === 'guide') {
    return (
      <div className="space-y-4 pb-28 animate-fade-in">
        {/* Sub-screen header */}
        <div className="flex items-center gap-3 pb-2 border-b border-accent">
          <button
            onClick={() => setActiveSubScreen(null)}
            className="machine-btn px-3 py-1.5 rounded-lg flex items-center gap-1.5 text-xs font-mono text-accent-soft whitespace-nowrap"
          >
            <ArrowLeft className="w-3.5 h-3.5" />
            <span>BACK // MENU</span>
          </button>
          <div className="text-right flex-1">
            <span className="font-mono text-[11px] text-accent font-bold uppercase tracking-widest">
              DOC // USER GUIDE
            </span>
          </div>
        </div>

        {/* Guide Sections */}
        <div className="space-y-3">
          {/* Step 1 */}
          <div className="machine-surface rounded-xl p-3.5 space-y-2">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-accent-soft border border-accent text-accent-soft font-bold">
                STEP 01
              </span>
              <h3 className="text-xs font-bold text-white uppercase tracking-wider font-mono">
                Getting Started & Backend Setup
              </h3>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              Open the <strong>Settings</strong> tab and enter your personal Render backend service URL (e.g.{' '}
              <code className="text-accent-soft bg-slate-950 px-1 py-0.5 rounded font-mono">
                https://your-service.onrender.com
              </code>
              ). Tap <strong>Test Server Connection</strong> to verify connectivity before starting downloads.
            </p>
          </div>

          {/* Step 2 */}
          <div className="machine-surface rounded-xl p-3.5 space-y-2">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-accent-soft border border-accent text-accent-soft font-bold">
                STEP 02
              </span>
              <h3 className="text-xs font-bold text-white uppercase tracking-wider font-mono">
                Download by Pasting a Link
              </h3>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              Copy any public video link from TikTok, YouTube, Instagram, or Facebook. On the Download tab, tap the mechanical{' '}
              <strong className="text-accent-soft">PASTE</strong> key. OmniDrop cleans tracking parameters automatically, fetches metadata, and displays your video preview.
            </p>
          </div>

          {/* Step 3 */}
          <div className="machine-surface rounded-xl p-3.5 space-y-2">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-accent-soft border border-accent text-accent-soft font-bold">
                STEP 03
              </span>
              <h3 className="text-xs font-bold text-white uppercase tracking-wider font-mono">
                Download with the System Share Button
              </h3>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              Inside TikTok, YouTube, Instagram, or Facebook, tap the native <strong>Share</strong> button on any clip and choose <strong>OmniDrop</strong> from the app sheet. OmniDrop will immediately parse the URL and prepare the download.
            </p>
          </div>

          {/* Step 4 */}
          <div className="machine-surface rounded-xl p-3.5 space-y-2">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-accent-soft border border-accent text-accent-soft font-bold">
                STEP 04
              </span>
              <h3 className="text-xs font-bold text-white uppercase tracking-wider font-mono">
                Using the Floating Edge Tab
              </h3>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              Enable the <strong>Floating Tab</strong> in Settings and grant <em>"Display over other apps"</em>. A subtle vertical tab docks to your screen edge while you use other apps. Pull it inward to reveal a quick-paste HUD that downloads videos in the background without leaving your feed.
            </p>
          </div>

          {/* Step 5 */}
          <div className="machine-surface rounded-xl p-3.5 space-y-2">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-accent-soft border border-accent text-accent-soft font-bold">
                STEP 05
              </span>
              <h3 className="text-xs font-bold text-white uppercase tracking-wider font-mono">
                Quality Options
              </h3>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              Choose from four ingestion modes:
            </p>
            <ul className="text-xs text-slate-400 space-y-1 font-mono pl-3 list-disc">
              <li><strong className="text-white">BEST:</strong> Highest available stream up to 4K / 60fps.</li>
              <li><strong className="text-white">1080P:</strong> Crisp High Definition.</li>
              <li><strong className="text-white">720P:</strong> Balanced quality and fast download size.</li>
              <li><strong className="text-white">MP3:</strong> Audio-only extract saved to <code className="text-accent">Music/OmniDrop</code>.</li>
            </ul>
          </div>

          {/* Step 6 */}
          <div className="machine-surface rounded-xl p-3.5 space-y-2">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-accent-soft border border-accent text-accent-soft font-bold">
                STEP 06
              </span>
              <h3 className="text-xs font-bold text-white uppercase tracking-wider font-mono">
                Videos Library & Native Player
              </h3>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              Downloaded videos appear in the <strong>Videos</strong> tab and in your phone gallery (<code className="text-accent-soft">Movies/OmniDrop</code>). Tap any card to launch the hardware-accelerated player:
            </p>
            <ul className="text-xs text-slate-400 space-y-1 font-mono pl-3 list-disc">
              <li><strong className="text-white">Double-tap left/right:</strong> Skip backward or forward 10 seconds.</li>
              <li><strong className="text-white">Single tap:</strong> Toggle playback HUD and system bars.</li>
              <li><strong className="text-white">Speed key:</strong> Cycle 0.5x, 1.0x, 1.5x, 2.0x playback rates.</li>
              <li><strong className="text-white">Orientation:</strong> Auto-rotates or locks fullscreen seamlessly.</li>
            </ul>
          </div>

          {/* Step 7 */}
          <div className="machine-surface rounded-xl p-3.5 space-y-2">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-amber-950 border border-amber-500/40 text-amber-300 font-bold">
                TIPS
              </span>
              <h3 className="text-xs font-bold text-white uppercase tracking-wider font-mono">
                Tips & Troubleshooting
              </h3>
            </div>
            <ul className="text-xs text-slate-300 space-y-2 pl-3 list-disc leading-relaxed">
              <li>
                <strong>Free Server Sleep:</strong> Free tier backends on Render go to sleep after ~15 minutes of idle time. The first download after inactivity may take 30–60 seconds while the server spins up. The app indicates this with the <span className="font-mono text-amber-400 text-[11px] font-bold">SLEEPING</span> badge.
              </li>
              <li>
                <strong>Platform Anti-Scraping:</strong> Occasionally YouTube or Instagram will rotate session requirements or temporarily challenge IP blocks. If a link fails, wait a few minutes or retry with another link.
              </li>
            </ul>
          </div>
        </div>
      </div>
    );
  }

  // SUB-SCREEN 2: PRIVACY POLICY
  if (activeSubScreen === 'privacy') {
    return (
      <div className="space-y-4 pb-28 animate-fade-in">
        <div className="flex items-center gap-3 pb-2 border-b border-accent">
          <button
            onClick={() => setActiveSubScreen(null)}
            className="machine-btn px-3 py-1.5 rounded-lg flex items-center gap-1.5 text-xs font-mono text-accent-soft whitespace-nowrap"
          >
            <ArrowLeft className="w-3.5 h-3.5" />
            <span>BACK // MENU</span>
          </button>
          <div className="text-right flex-1">
            <span className="font-mono text-[11px] text-emerald-400 font-bold uppercase tracking-widest">
              SEC // PRIVACY POLICY
            </span>
          </div>
        </div>

        <div className="machine-surface rounded-xl p-4 space-y-4 text-xs text-slate-300 leading-relaxed">
          <div className="flex items-center justify-between pb-2 border-b border-slate-800">
            <span className="font-mono text-[11px] text-slate-400">PROTOCOL: STRICT LOCAL INTEGRITY</span>
            <span className="font-mono text-[10px] px-2 py-0.5 rounded bg-emerald-950 border border-emerald-500/40 text-emerald-300">
              UPDATED: OCT 23, 2026
            </span>
          </div>

          <div>
            <h4 className="font-mono font-bold text-white uppercase tracking-wide mb-1 text-[13px]">
              1. Zero Data Collection & Zero Advertising
            </h4>
            <p>
              OmniDrop operates under a zero-telemetry architecture. There are no user accounts, no login requirements, no tracking pixels, no analytics SDKs, and absolutely no commercial advertising networks embedded in this software.
            </p>
          </div>

          <div>
            <h4 className="font-mono font-bold text-white uppercase tracking-wide mb-1 text-[13px]">
              2. How Links Are Handled
            </h4>
            <p>
              When you paste or share a link to OmniDrop, that URL is transmitted directly to your configured backend server (hosted on Render) solely for the purpose of extracting the video stream. The server may temporarily log the host domain and response status for operational diagnostics. No personal identifiers or IP records are stored or sold.
            </p>
          </div>

          <div>
            <h4 className="font-mono font-bold text-white uppercase tracking-wide mb-1 text-[13px]">
              3. Local Device Storage Only
            </h4>
            <p>
              All downloaded files are written directly to your phone's standard gallery folders: <code className="text-accent-soft">Movies/OmniDrop</code> for video content and <code className="text-accent-soft">Music/OmniDrop</code> for audio extracts. Your files remain on your hardware and are never uploaded or mirrored to any external cloud service.
            </p>
          </div>

          <div>
            <h4 className="font-mono font-bold text-white uppercase tracking-wide mb-1 text-[13px]">
              4. Clipboard Access
            </h4>
            <p>
              OmniDrop accesses the system clipboard only when you explicitly tap the mechanical <strong>PASTE</strong> button or activate the floating edge drawer. No continuous background clipboard harvesting is performed.
            </p>
          </div>

          <div>
            <h4 className="font-mono font-bold text-white uppercase tracking-wide mb-2 text-[13px]">
              5. Android Permissions Audit
            </h4>
            <div className="space-y-2 font-mono text-[11px]">
              <div className="p-2.5 rounded-lg bg-slate-950/80 border border-slate-800">
                <div className="text-accent-soft font-bold">android.permission.INTERNET</div>
                <div className="text-slate-400 mt-0.5">Required to communicate with your Render backend server to download video and audio data.</div>
              </div>
              <div className="p-2.5 rounded-lg bg-slate-950/80 border border-slate-800">
                <div className="text-accent-soft font-bold">SYSTEM_ALERT_WINDOW (Display over other apps)</div>
                <div className="text-slate-400 mt-0.5">Used strictly to render the floating edge drawer for quick 1-tap background downloading while browsing social apps.</div>
              </div>
              <div className="p-2.5 rounded-lg bg-slate-950/80 border border-slate-800">
                <div className="text-accent-soft font-bold">POST_NOTIFICATIONS</div>
                <div className="text-slate-400 mt-0.5">Employed to deliver download progress percentages and finished notification alerts.</div>
              </div>
              <div className="p-2.5 rounded-lg bg-slate-950/80 border border-slate-800">
                <div className="text-accent-soft font-bold">READ_MEDIA_VIDEO / READ_EXTERNAL_STORAGE</div>
                <div className="text-slate-400 mt-0.5">Required to query <code className="text-white">Movies/OmniDrop</code> so the in-app library can rebuild itself and play videos offline.</div>
              </div>
            </div>
          </div>

          <div className="pt-2 border-t border-slate-800 flex items-center justify-between text-slate-400">
            <span>Direct Inquiries:</span>
            <a
              href="mailto:fatormajohn64@gmail.com"
              className="font-mono text-accent-soft hover:underline flex items-center gap-1"
            >
              <Mail className="w-3 h-3" />
              fatormajohn64@gmail.com
            </a>
          </div>
        </div>
      </div>
    );
  }

  // SUB-SCREEN 3: ABOUT THE DEVELOPER
  if (activeSubScreen === 'developer') {
    return (
      <div className="space-y-4 pb-28 animate-fade-in">
        <div className="flex items-center gap-3 pb-2 border-b border-accent">
          <button
            onClick={() => setActiveSubScreen(null)}
            className="machine-btn px-3 py-1.5 rounded-lg flex items-center gap-1.5 text-xs font-mono text-accent-soft whitespace-nowrap"
          >
            <ArrowLeft className="w-3.5 h-3.5" />
            <span>BACK // MENU</span>
          </button>
          <div className="text-right flex-1">
            <span className="font-mono text-[11px] text-amber-400 font-bold uppercase tracking-widest">
              DEV // JTECH
            </span>
          </div>
        </div>

        {/* Creator Identity Hero */}
        <div className="machine-surface rounded-xl p-5 text-center space-y-4">
          <div className="relative inline-block mx-auto">
            <div className="w-20 h-20 rounded-2xl bg-slate-950 border-2 border-accent-strong glow-accent p-0.5 flex items-center justify-center">
              <div className="w-full h-full rounded-[14px] bg-slate-950 flex items-center justify-center">
                <span className="font-mono font-black text-2xl tracking-wider text-accent-soft">
                  JT
                </span>
              </div>
            </div>
            <div className="absolute -bottom-1 -right-1 w-5 h-5 rounded-full bg-emerald-500 border-2 border-slate-950 led-emerald" />
          </div>

          <div>
            <h3 className="font-mono font-bold text-base text-white tracking-wide">
              JTech
            </h3>
            <p className="font-mono text-xs text-accent mt-0.5">
              Independent Software Engineer • Sierra Leone 🇸🇱
            </p>
          </div>

          <div className="p-3.5 rounded-xl bg-slate-950/70 border border-slate-800 text-xs text-slate-300 leading-relaxed font-sans text-left">
            "JTech is an independent software creator based in Sierra Leone, building fast, practical tools for everyday mobile life entirely from a smartphone."
          </div>

          <div className="flex items-center justify-center gap-2 text-xs font-mono text-slate-400">
            <Terminal className="w-3.5 h-3.5 text-accent" />
            <span>Mobile-First Engineering • Zero Compromise</span>
          </div>
        </div>

        {/* Other Projects Showcase */}
        <div className="space-y-2">
          <div className="font-mono text-[11px] text-slate-400 uppercase tracking-widest px-1">
            PROJECTS BY JTECH // PORTFOLIO
          </div>

          <div className="machine-surface rounded-xl p-3.5 flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-pink-950/60 border border-pink-500/40 flex items-center justify-center text-pink-400 shrink-0">
              <Music className="w-5 h-5" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <h4 className="font-mono font-bold text-xs text-white">JTEC MUSIC</h4>
                <span className="text-[9px] font-mono px-1.5 py-0.2 rounded bg-pink-950 text-pink-300 border border-pink-500/30">
                  AUDIO ENGINE
                </span>
              </div>
              <p className="text-[11px] text-slate-400 mt-0.5">
                Ultra-lightweight offline music player engineered for seamless audio playback and hardware decoding.
              </p>
            </div>
          </div>

          <div className="machine-surface rounded-xl p-3.5 flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-purple-950/60 border border-purple-500/40 flex items-center justify-center text-purple-400 shrink-0">
              <Bot className="w-5 h-5" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <h4 className="font-mono font-bold text-xs text-white">Jexa AI</h4>
                <span className="text-[9px] font-mono px-1.5 py-0.2 rounded bg-purple-950 text-purple-300 border border-purple-500/30">
                  INTELLIGENCE
                </span>
              </div>
              <p className="text-[11px] text-slate-400 mt-0.5">
                Next-generation conversational assistant and automation utility for streamlined workflow execution.
              </p>
            </div>
          </div>
        </div>

        {/* Contact Tile */}
        <div className="machine-surface rounded-xl p-4 flex items-center justify-between">
          <div>
            <div className="font-mono text-xs text-white font-bold">CONTACT DEVELOPER</div>
            <div className="text-[11px] text-slate-400">Collaboration, bug reports & feedback</div>
          </div>
          <a
            href="mailto:fatormajohn64@gmail.com"
            className="machine-btn px-3 py-1.5 rounded-lg font-mono text-xs text-accent-soft flex items-center gap-1.5 whitespace-nowrap"
          >
            <Mail className="w-3.5 h-3.5 text-accent" />
            <span>EMAIL</span>
          </a>
        </div>
      </div>
    );
  }

  // SUB-SCREEN 4: APP SPECIFICATIONS & INFO
  if (activeSubScreen === 'info') {
    return (
      <div className="space-y-4 pb-28 animate-fade-in">
        <div className="flex items-center gap-3 pb-2 border-b border-accent">
          <button
            onClick={() => setActiveSubScreen(null)}
            className="machine-btn px-3 py-1.5 rounded-lg flex items-center gap-1.5 text-xs font-mono text-accent-soft whitespace-nowrap"
          >
            <ArrowLeft className="w-3.5 h-3.5" />
            <span>BACK // MENU</span>
          </button>
          <div className="text-right flex-1">
            <span className="font-mono text-[11px] text-purple-400 font-bold uppercase tracking-widest">
              SYS // APP INFO
            </span>
          </div>
        </div>

        <div className="machine-surface rounded-xl p-4 space-y-3.5">
          <div className="flex items-center justify-between pb-3 border-b border-slate-800">
            <div>
              <div className="font-mono font-bold text-white text-sm">OmniDrop Personal Downloader</div>
              <div className="font-mono text-[11px] text-accent tabular-nums">PRODUCTION BUILD {appVersion.versionCode}</div>
            </div>
            <span className="px-2.5 py-1 rounded-lg bg-accent-soft border border-accent text-accent-soft font-mono text-xs font-bold tabular-nums whitespace-nowrap">
              v1.0.{appVersion.versionCode}
            </span>
          </div>

          <div className="space-y-2 text-xs">
            <div className="flex justify-between py-1 border-b border-slate-800/60 font-mono">
              <span className="text-slate-400">Architecture</span>
              <span className="text-slate-200">Capacitor 6 Hybrid + Native Kotlin</span>
            </div>
            <div className="flex justify-between py-1 border-b border-slate-800/60 font-mono">
              <span className="text-slate-400">Native Plugins</span>
              <span className="text-slate-200">VideoDownloaderPlugin.kt</span>
            </div>
            <div className="flex justify-between py-1 border-b border-slate-800/60 font-mono">
              <span className="text-slate-400">System Drawer</span>
              <span className="text-slate-200">FloatingBubbleService.kt</span>
            </div>
            <div className="flex justify-between py-1 border-b border-slate-800/60 font-mono">
              <span className="text-slate-400">Player Engine</span>
              <span className="text-slate-200">VideoPlayerActivity.kt</span>
            </div>
            <div className="flex justify-between py-1 border-b border-slate-800/60 font-mono">
              <span className="text-slate-400">Backend Core</span>
              <span className="text-slate-200">FastAPI + yt-dlp</span>
            </div>
            <div className="flex justify-between py-1 border-b border-slate-800/60 font-mono">
              <span className="text-slate-400">Frontend Stack</span>
              <span className="text-slate-200">React 18 + Vite + Tailwind CSS</span>
            </div>
            <div className="flex justify-between py-1 border-b border-slate-800/60 font-mono">
              <span className="text-slate-400">Studio Tooling</span>
              <span className="text-slate-200">Google AI Studio</span>
            </div>
            <div className="flex justify-between py-1 font-mono">
              <span className="text-slate-400">License</span>
              <span className="text-emerald-400 font-bold">MIT / Personal Use</span>
            </div>
          </div>
        </div>

        {/* Diagnostics Section */}
        <div className="machine-surface rounded-xl p-4 space-y-3">
          <div className="flex items-center justify-between pb-2 border-b border-slate-800">
            <div className="flex items-center gap-2">
              <span className={`w-2 h-2 rounded-full ${crashLog ? 'led-red' : 'led-emerald'}`} />
              <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
                DIAGNOSTICS // CRASH LOG
              </h3>
            </div>
            {crashLog && (
              <div className="flex items-center gap-2">
                <button
                  onClick={handleCopyCrash}
                  className="px-2 py-1 rounded bg-slate-900 border border-slate-700 hover:border-accent-strong font-mono text-[10px] text-accent-soft flex items-center gap-1 active:scale-95"
                  title="Copy crash details"
                >
                  <Copy className="w-3 h-3" />
                  <span>{isCopied ? 'COPIED' : 'COPY'}</span>
                </button>
                <button
                  onClick={handleClearCrash}
                  className="px-2 py-1 rounded bg-red-950/60 border border-red-500/50 hover:border-red-400 font-mono text-[10px] text-red-300 flex items-center gap-1 active:scale-95"
                  title="Clear crash log"
                >
                  <Trash2 className="w-3 h-3" />
                  <span>CLEAR</span>
                </button>
              </div>
            )}
          </div>

          {crashLog ? (
            <div className="p-3 rounded-lg bg-black/90 border border-red-500/40 font-mono text-[11px] text-red-300 max-h-48 overflow-y-auto whitespace-pre-wrap select-text leading-relaxed">
              {crashLog}
            </div>
          ) : (
            <div className="py-2.5 text-center font-mono text-xs text-slate-400 flex items-center justify-center gap-2">
              <div className="w-2 h-2 rounded-full led-emerald" />
              <span>No crashes recorded</span>
            </div>
          )}
        </div>

        {/* Network Log Section */}
        <div className="machine-surface rounded-xl p-4 space-y-3">
          <div className="flex items-center justify-between pb-2 border-b border-slate-800">
            <div className="flex items-center gap-2">
              <span className={`w-2 h-2 rounded-full ${networkLog ? 'led-cyan' : 'led-emerald'}`} />
              <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
                DIAGNOSTICS // NETWORK LOG
              </h3>
            </div>
            {networkLog && (
              <div className="flex items-center gap-2">
                <button
                  onClick={handleCopyNetworkLog}
                  className="px-2 py-1 rounded bg-slate-900 border border-slate-700 hover:border-accent-strong font-mono text-[10px] text-accent-soft flex items-center gap-1 active:scale-95"
                  title="Copy network log"
                >
                  <Copy className="w-3 h-3" />
                  <span>{isNetworkCopied ? 'COPIED' : 'COPY'}</span>
                </button>
                <button
                  onClick={handleClearNetworkLog}
                  className="px-2 py-1 rounded bg-red-950/60 border border-red-500/50 hover:border-red-400 font-mono text-[10px] text-red-300 flex items-center gap-1 active:scale-95"
                  title="Clear network log"
                >
                  <Trash2 className="w-3 h-3" />
                  <span>CLEAR</span>
                </button>
              </div>
            )}
          </div>

          {networkLog ? (
            <div className="p-3 rounded-lg bg-black/90 border border-slate-800 font-mono text-[10.5px] text-slate-300 max-h-56 overflow-y-auto whitespace-pre-wrap select-text leading-relaxed">
              {networkLog}
            </div>
          ) : (
            <div className="py-2.5 text-center font-mono text-xs text-slate-400 flex items-center justify-center gap-2">
              <div className="w-2 h-2 rounded-full led-emerald" />
              <span>No network events recorded</span>
            </div>
          )}
        </div>

        <div className="machine-surface rounded-xl p-3.5 flex items-center justify-between text-xs font-mono">
          <div className="flex items-center gap-2">
            <div className="w-2.5 h-2.5 rounded-full led-emerald" />
            <span className="text-slate-300">CORE STATUS: SYSTEM OPERATIONAL</span>
          </div>
          <span className="text-accent whitespace-nowrap">100% ONLINE</span>
        </div>
      </div>
    );
  }

  // SUB-SCREEN 5: DOWNLOAD STATISTICS
  if (activeSubScreen === 'stats') {
    const totalPlatformCount = stats
      ? Object.values(stats.platformCounts || {}).reduce((a: number, b: number) => a + b, 0)
      : 0;

    const maxDailyDownloads = stats
      ? Math.max(...(stats.dailyStats || []).map((d) => d.downloads), 1)
      : 1;

    const platformColors: Record<string, { bg: string; text: string; glow: string }> = {
      TikTok: { bg: 'bg-cyan-400', text: 'text-cyan-400', glow: 'shadow-[0_0_8px_rgba(0,240,255,0.6)]' },
      YouTube: { bg: 'bg-rose-500', text: 'text-rose-400', glow: 'shadow-[0_0_8px_rgba(244,63,94,0.6)]' },
      Instagram: { bg: 'bg-fuchsia-500', text: 'text-fuchsia-400', glow: 'shadow-[0_0_8px_rgba(217,70,239,0.6)]' },
      Facebook: { bg: 'bg-blue-500', text: 'text-blue-400', glow: 'shadow-[0_0_8px_rgba(59,130,246,0.6)]' },
      Other: { bg: 'bg-slate-400', text: 'text-slate-400', glow: 'shadow-[0_0_8px_rgba(148,163,184,0.4)]' },
    };

    return (
      <div className="space-y-4 pb-28 animate-fade-in font-mono text-xs">
        {/* Sub-screen header */}
        <div className="flex items-center gap-3 pb-2 border-b border-accent">
          <button
            onClick={() => setActiveSubScreen(null)}
            className="machine-btn px-3 py-1.5 rounded-lg flex items-center gap-1.5 text-xs font-mono text-accent-soft whitespace-nowrap"
          >
            <ArrowLeft className="w-3.5 h-3.5" />
            <span>BACK // MENU</span>
          </button>
          <div className="text-right flex-1">
            <span className="font-mono text-[11px] text-accent font-bold uppercase tracking-widest">
              METRICS // DOWNLOAD STATS
            </span>
          </div>
        </div>

        {isLoadingStats && !stats ? (
          <div className="machine-surface p-8 text-center rounded-xl space-y-3">
            <div className="w-8 h-8 mx-auto rounded-full border-2 border-accent border-t-transparent animate-spin" />
            <p className="text-slate-400 font-mono text-xs">COMPUTING NATIVE TELEMETRY...</p>
          </div>
        ) : !stats || stats.totalDownloads === 0 ? (
          <EmptyState
            icon={BarChart3}
            title="NO STATS RECORDED YET"
            message="Completed downloads will automatically record volume, platform split, and daily activity."
          />
        ) : (
          <>
            {/* Primary KPI Grid (4 Cards) */}
            <div className="grid grid-cols-2 gap-2.5">
              {/* Total Downloads */}
              <div className="machine-surface p-3 rounded-xl space-y-1">
                <div className="flex items-center justify-between text-slate-400 text-[10px] uppercase">
                  <span>TOTAL DOWNLOADS</span>
                  <HardDrive className="w-3.5 h-3.5 text-accent" />
                </div>
                <div className="text-xl font-bold text-white tracking-wider tabular-nums">
                  {stats.totalDownloads}
                </div>
                <div className="text-[10px] text-slate-500">Finished media jobs</div>
              </div>

              {/* Total Bandwidth Saved */}
              <div className="machine-surface p-3 rounded-xl space-y-1">
                <div className="flex items-center justify-between text-slate-400 text-[10px] uppercase">
                  <span>DATA VOLUME</span>
                  <TrendingUp className="w-3.5 h-3.5 text-emerald-400" />
                </div>
                <div className="text-xl font-bold text-accent-soft tracking-wider tabular-nums">
                  {formatStatsBytes(stats.totalBytes)}
                </div>
                <div className="text-[10px] text-slate-500">Saved to storage</div>
              </div>

              {/* Videos vs Photos */}
              <div className="machine-surface p-3 rounded-xl space-y-1">
                <div className="flex items-center justify-between text-slate-400 text-[10px] uppercase">
                  <span>MEDIA SPLIT</span>
                  <div className="flex items-center gap-1">
                    <Film className="w-3 h-3 text-accent" />
                    <Camera className="w-3 h-3 text-fuchsia-400" />
                  </div>
                </div>
                <div className="text-base font-bold text-white tracking-wider flex items-center gap-2 tabular-nums">
                  <span>{stats.videoCount} <span className="text-[10px] text-slate-400 font-normal">VID</span></span>
                  <span className="text-slate-600">/</span>
                  <span>{stats.photoCount} <span className="text-[10px] text-slate-400 font-normal">PIC</span></span>
                </div>
                <div className="text-[10px] text-slate-500">Clips vs photo sets</div>
              </div>

              {/* Success Rate */}
              <div className="machine-surface p-3 rounded-xl space-y-1">
                <div className="flex items-center justify-between text-slate-400 text-[10px] uppercase">
                  <span>SUCCESS EFFICIENCY</span>
                  <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
                </div>
                <div className="text-xl font-bold text-emerald-400 tracking-wider">
                  {stats.successRate.toFixed(1)}%
                </div>
                <div className="text-[10px] text-slate-500">
                  {stats.failedCount > 0 ? `${stats.failedCount} recorded failures` : 'Flawless execution'}
                </div>
              </div>
            </div>

            {/* Secondary Insights (Average Size & Busiest Day) */}
            <div className="grid grid-cols-2 gap-2.5">
              <div className="machine-surface p-3 rounded-xl space-y-1">
                <div className="text-slate-400 text-[10px] uppercase flex items-center gap-1.5">
                  <BarChart3 className="w-3 h-3 text-purple-400" />
                  <span>AVG FILE SIZE</span>
                </div>
                <div className="text-sm font-bold text-white">
                  {formatStatsBytes(stats.averageBytes)}
                </div>
              </div>

              <div className="machine-surface p-3 rounded-xl space-y-1">
                <div className="text-slate-400 text-[10px] uppercase flex items-center gap-1.5">
                  <Calendar className="w-3 h-3 text-amber-400" />
                  <span>BUSIEST RECORD</span>
                </div>
                <div className="text-sm font-bold text-white truncate" title={stats.busiestDay}>
                  {stats.busiestDay || 'N/A'}
                </div>
              </div>
            </div>

            {/* Platform Distribution: Glowing Segmented Bar */}
            <div className="machine-surface rounded-xl p-4 space-y-3">
              <div className="flex items-center justify-between pb-1 border-b border-slate-800">
                <div className="flex items-center gap-2">
                  <span className="w-2 h-2 rounded-full led-cyan" />
                  <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
                    PLATFORM DISTRIBUTION
                  </h3>
                </div>
                <span className="text-[10px] text-slate-400">
                  {totalPlatformCount} total tracks
                </span>
              </div>

              {/* Glowing Segmented Bar */}
              <div className="h-3 w-full bg-slate-950 rounded-full overflow-hidden flex border border-slate-800 p-0.5">
                {totalPlatformCount === 0 ? (
                  <div className="w-full h-full bg-slate-800 rounded-full" />
                ) : (
                  Object.entries(stats.platformCounts || {}).map(([plat, count]) => {
                    const numCount = Number(count) || 0;
                    if (numCount <= 0) return null;
                    const pct = (numCount / totalPlatformCount) * 100;
                    const style = platformColors[plat] || platformColors.Other;
                    return (
                      <div
                        key={plat}
                        style={{ width: `${pct}%` }}
                        className={`h-full ${style.bg} ${style.glow} first:rounded-l-full last:rounded-r-full transition-all`}
                        title={`${plat}: ${numCount} (${pct.toFixed(1)}%)`}
                      />
                    );
                  })
                )}
              </div>

              {/* Platform Legend Grid */}
              <div className="grid grid-cols-2 gap-2 pt-1">
                {['TikTok', 'YouTube', 'Instagram', 'Facebook', 'Other'].map((plat) => {
                  const count = Number(stats.platformCounts?.[plat]) || 0;
                  const pct = totalPlatformCount > 0 ? (count / totalPlatformCount) * 100 : 0;
                  const style = platformColors[plat] || platformColors.Other;
                  return (
                    <div
                      key={plat}
                      className="p-2 rounded-lg bg-slate-950/80 border border-slate-800/80 flex items-center justify-between"
                    >
                      <div className="flex items-center gap-1.5 min-w-0">
                        <span className={`w-2 h-2 rounded-full ${style.bg} shrink-0`} />
                        <span className="font-bold text-slate-200 truncate">{plat}</span>
                      </div>
                      <div className="text-right shrink-0">
                        <span className={`font-bold ${style.text}`}>{count}</span>
                        <span className="text-[9px] text-slate-500 ml-1">({pct.toFixed(0)}%)</span>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* 14-Day Activity Bar Chart */}
            <div className="machine-surface rounded-xl p-4 space-y-3">
              <div className="flex items-center justify-between pb-1 border-b border-slate-800">
                <div className="flex items-center gap-2">
                  <BarChart3 className="w-3.5 h-3.5 text-accent" />
                  <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
                    14-DAY ACTIVITY TIMELINE
                  </h3>
                </div>
                <span className="text-[10px] text-slate-400 tabular-nums">Peak: {maxDailyDownloads} items</span>
              </div>

              <div className="pt-2">
                <div className="h-28 flex items-end justify-between gap-1 border-b border-slate-800/80 pb-1">
                  {stats.dailyStats.map((d, idx) => {
                    const heightPct = maxDailyDownloads > 0 ? (d.downloads / maxDailyDownloads) * 100 : 0;
                    const isPeak = d.downloads === maxDailyDownloads && d.downloads > 0;
                    return (
                      <div
                        key={idx}
                        className="flex-1 flex flex-col items-center h-full justify-end group relative"
                      >
                        {/* Tooltip on tap/hover */}
                        <div className="opacity-0 group-hover:opacity-100 pointer-events-none absolute bottom-full mb-1 z-30 px-1.5 py-0.5 rounded bg-black border border-accent text-[9px] text-accent-soft whitespace-nowrap transition-opacity tabular-nums">
                          {d.date.slice(5)}: {d.downloads} ({formatStatsBytes(d.bytes)})
                        </div>

                        {/* Bar */}
                        <div
                          style={{ height: `${Math.max(heightPct, d.downloads > 0 ? 8 : 2)}%` }}
                          className={`w-full rounded-t transition-all ${
                            d.downloads === 0
                              ? 'bg-slate-800/40'
                              : isPeak
                              ? 'bg-accent glow-accent-sm'
                              : 'bg-accent opacity-75 group-hover:opacity-100'
                          }`}
                        />
                      </div>
                    );
                  })}
                </div>

                {/* Day Labels */}
                <div className="flex justify-between pt-1 text-[8px] text-slate-500 font-mono">
                  <span>{stats.dailyStats[0]?.date.slice(5) || '14d ago'}</span>
                  <span>7d ago</span>
                  <span>Today</span>
                </div>
              </div>
            </div>

            {/* Reset Stats Key */}
            <div className="pt-2 flex justify-end">
              <button
                onClick={() => setResetConfirmOpen(true)}
                className="machine-btn px-3 py-1.5 rounded-lg font-mono text-xs text-rose-400 hover:text-rose-300 border-rose-500/30 flex items-center gap-1.5 transition-colors"
              >
                <RotateCcw className="w-3.5 h-3.5 text-rose-400" />
                <span>RESET STATS</span>
              </button>
            </div>
          </>
        )}

        {/* Reset Confirmation Modal */}
        {resetConfirmOpen && (
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/85 backdrop-blur-sm animate-in fade-in duration-200">
            <div className="machine-surface w-full max-w-sm rounded-xl p-5 space-y-4 shadow-2xl border border-rose-500/40">
              <div className="flex items-center gap-2">
                <AlertTriangle className="w-4 h-4 text-rose-400" />
                <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
                  CONFIRM STATS PURGE
                </h3>
              </div>
              <p className="font-mono text-xs text-slate-300 leading-relaxed bg-slate-950/60 p-3 rounded-lg border border-slate-800">
                Are you sure you want to purge all download statistics? This will reset total bandwidth saved, platform breakdown counters, and 14-day history to zero. Your video and photo files will NOT be deleted.
              </p>
              <div className="flex justify-end gap-2 pt-1 font-mono text-xs">
                <button
                  onClick={() => setResetConfirmOpen(false)}
                  className="machine-btn px-3 py-1.5 rounded-lg text-slate-400"
                >
                  CANCEL
                </button>
                <button
                  onClick={handleResetStats}
                  disabled={isResetting}
                  className="machine-btn-danger px-4 py-1.5 rounded-lg font-bold text-white uppercase"
                >
                  {isResetting ? 'PURGING...' : 'PURGE STATS'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    );
  }

  // MAIN ABOUT MENU
  return (
    <div className="space-y-4 pb-28">
      {/* Monospace Section Header */}
      <div className="flex items-center justify-between pt-1 gap-2">
        <div className="min-w-0">
          <h2 className="text-xs font-mono font-bold text-accent uppercase tracking-widest flex items-center gap-2 whitespace-nowrap">
            <span className="w-2 h-2 rounded-full led-accent shrink-0" />
            ABOUT // SYSTEM INTEL
          </h2>
          <p className="text-xs text-slate-400 mt-0.5 truncate">Release notes, documentation & diagnostics</p>
        </div>
        <div className="font-mono text-[10px] px-2 py-0.5 rounded bg-accent-soft border border-accent text-accent-soft font-bold tabular-nums whitespace-nowrap shrink-0">
          BUILD v1.0.{appVersion.versionCode}
        </div>
      </div>

      {/* Machine Tiles */}
      <div className="grid grid-cols-1 gap-3">
        {menuItems.map((item, idx) => {
          const Icon = item.icon;
          const isUnread = Boolean((item as any).unread);
          return (
            <button
              key={item.id}
              onClick={() => setActiveSubScreen(item.id)}
              style={{ '--stagger': idx } as React.CSSProperties}
              className="stagger-card machine-btn w-full p-4 rounded-xl text-left flex items-center justify-between group transition-all"
            >
              <div className="flex items-start gap-3.5 min-w-0">
                <div className="relative w-10 h-10 rounded-lg bg-slate-950 border border-accent flex items-center justify-center shrink-0 shadow-inner group-hover:border-accent-strong transition">
                  <Icon className={`w-5 h-5 ${item.color}`} />
                  {isUnread && (
                    <span className="absolute -top-1 -right-1 w-2.5 h-2.5 rounded-full led-accent animate-pulse" />
                  )}
                </div>
                <div className="min-w-0">
                  <div className="flex items-center gap-2 whitespace-nowrap">
                    <span className="font-mono text-[10px] text-accent font-semibold tracking-wider">
                      {item.code}
                    </span>
                    <span className="text-[10px] font-mono px-1.5 py-0.2 rounded bg-slate-950 border border-slate-800 text-slate-300 tabular-nums">
                      {item.badge}
                    </span>
                    {isUnread && (
                      <span className="text-[9px] font-mono px-1.5 py-0.2 rounded bg-accent text-slate-950 font-bold glow-accent-sm">
                        NEW
                      </span>
                    )}
                  </div>
                  <h3 className="font-mono font-bold text-sm text-white mt-0.5 tracking-wide group-hover:text-accent-soft transition truncate">
                    {item.title}
                  </h3>
                  <p className="text-xs text-slate-400 mt-0.5 leading-snug line-clamp-1">
                    {item.subtitle}
                  </p>
                </div>
              </div>

              <div className="pl-2 shrink-0">
                <div className="w-7 h-7 rounded-lg bg-slate-950 border border-slate-800 flex items-center justify-center text-slate-400 group-hover:text-accent group-hover:border-accent transition">
                  <ChevronRight className="w-4 h-4" />
                </div>
              </div>
            </button>
          );
        })}
      </div>

      {/* Hardware Badge Bottom */}
      <div className="machine-surface rounded-xl p-3 flex items-center justify-between text-xs font-mono text-slate-400 stagger-card" style={{ '--stagger': 6 } as React.CSSProperties}>
        <div className="flex items-center gap-2 whitespace-nowrap">
          <Terminal className="w-4 h-4 text-accent" />
          <span>OMNIDROP // HARDWARE HYBRID</span>
        </div>
        <span className="text-slate-500 whitespace-nowrap">2026 EDITION</span>
      </div>
    </div>
  );
};
