import React, { useState } from 'react';
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
} from 'lucide-react';
import { VideoDownloader } from '../plugins/videoDownloader';

type SubScreen = 'guide' | 'privacy' | 'developer' | 'info' | null;

export const AboutScreen: React.FC = () => {
  const [activeSubScreen, setActiveSubScreen] = useState<SubScreen>(null);
  const [crashLog, setCrashLog] = useState<string>('');
  const [isCopied, setIsCopied] = useState(false);

  React.useEffect(() => {
    if (activeSubScreen === 'info') {
      VideoDownloader.getLastCrash()
        .then((res) => {
          setCrashLog(res?.crash || '');
        })
        .catch(() => setCrashLog(''));
    }
  }, [activeSubScreen]);

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

  const menuItems = [
    {
      id: 'guide' as SubScreen,
      code: 'DOC // 01',
      title: 'User Guide',
      subtitle: 'Complete manual for downloads, gestures, edge tab & troubleshooting',
      icon: BookOpen,
      color: 'text-cyan-400',
      badge: 'GUIDE',
    },
    {
      id: 'privacy' as SubScreen,
      code: 'SEC // 02',
      title: 'Privacy Policy',
      subtitle: 'Zero ads, zero telemetry, local storage only & permissions audit',
      icon: ShieldCheck,
      color: 'text-emerald-400',
      badge: 'VERIFIED',
    },
    {
      id: 'developer' as SubScreen,
      code: 'DEV // 03',
      title: 'About the Developer',
      subtitle: 'JTech • Independent creator based in Sierra Leone',
      icon: User,
      color: 'text-amber-400',
      badge: 'CREATOR',
    },
    {
      id: 'info' as SubScreen,
      code: 'SYS // 04',
      title: 'App Specifications',
      subtitle: 'v2.4.0 (Build 240) • Hybrid runtime & open-source stack',
      icon: Info,
      color: 'text-purple-400',
      badge: 'v2.4.0',
    },
  ];

  // SUB-SCREEN 1: USER GUIDE
  if (activeSubScreen === 'guide') {
    return (
      <div className="space-y-4 pb-28 animate-fade-in">
        {/* Sub-screen header */}
        <div className="flex items-center gap-3 pb-2 border-b border-cyan-500/20">
          <button
            onClick={() => setActiveSubScreen(null)}
            className="machine-btn px-3 py-1.5 rounded-lg flex items-center gap-1.5 text-xs font-mono text-cyan-300"
          >
            <ArrowLeft className="w-3.5 h-3.5" />
            <span>BACK // MENU</span>
          </button>
          <div className="text-right flex-1">
            <span className="font-mono text-[11px] text-cyan-400 font-bold uppercase tracking-widest">
              DOC // USER GUIDE
            </span>
          </div>
        </div>

        {/* Guide Sections */}
        <div className="space-y-3">
          {/* Step 1 */}
          <div className="machine-surface rounded-xl p-3.5 space-y-2">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-cyan-950 border border-cyan-500/40 text-cyan-300 font-bold">
                STEP 01
              </span>
              <h3 className="text-xs font-bold text-white uppercase tracking-wider font-mono">
                Getting Started & Backend Setup
              </h3>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              Open the <strong>Settings</strong> tab and enter your personal Render backend service URL (e.g.{' '}
              <code className="text-cyan-300 bg-slate-950 px-1 py-0.5 rounded font-mono">
                https://your-service.onrender.com
              </code>
              ). Tap <strong>Test Server Connection</strong> to verify connectivity before starting downloads.
            </p>
          </div>

          {/* Step 2 */}
          <div className="machine-surface rounded-xl p-3.5 space-y-2">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-cyan-950 border border-cyan-500/40 text-cyan-300 font-bold">
                STEP 02
              </span>
              <h3 className="text-xs font-bold text-white uppercase tracking-wider font-mono">
                Download by Pasting a Link
              </h3>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              Copy any public video link from TikTok, YouTube, Instagram, or Facebook. On the Download tab, tap the mechanical{' '}
              <strong className="text-cyan-300">PASTE</strong> key. OmniDrop cleans tracking parameters automatically, fetches metadata, and displays your video preview.
            </p>
          </div>

          {/* Step 3 */}
          <div className="machine-surface rounded-xl p-3.5 space-y-2">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-cyan-950 border border-cyan-500/40 text-cyan-300 font-bold">
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
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-cyan-950 border border-cyan-500/40 text-cyan-300 font-bold">
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
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-cyan-950 border border-cyan-500/40 text-cyan-300 font-bold">
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
              <li><strong className="text-white">MP3:</strong> Audio-only extract saved to <code className="text-cyan-400">Music/OmniDrop</code>.</li>
            </ul>
          </div>

          {/* Step 6 */}
          <div className="machine-surface rounded-xl p-3.5 space-y-2">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs px-2 py-0.5 rounded bg-cyan-950 border border-cyan-500/40 text-cyan-300 font-bold">
                STEP 06
              </span>
              <h3 className="text-xs font-bold text-white uppercase tracking-wider font-mono">
                Videos Library & Native Player
              </h3>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              Downloaded videos appear in the <strong>Videos</strong> tab and in your phone gallery (<code className="text-cyan-300">Movies/OmniDrop</code>). Tap any card to launch the hardware-accelerated player:
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
        <div className="flex items-center gap-3 pb-2 border-b border-cyan-500/20">
          <button
            onClick={() => setActiveSubScreen(null)}
            className="machine-btn px-3 py-1.5 rounded-lg flex items-center gap-1.5 text-xs font-mono text-cyan-300"
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
              All downloaded files are written directly to your phone's standard gallery folders: <code className="text-cyan-300">Movies/OmniDrop</code> for video content and <code className="text-cyan-300">Music/OmniDrop</code> for audio extracts. Your files remain on your hardware and are never uploaded or mirrored to any external cloud service.
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
                <div className="text-cyan-300 font-bold">android.permission.INTERNET</div>
                <div className="text-slate-400 mt-0.5">Required to communicate with your Render backend server to download video and audio data.</div>
              </div>
              <div className="p-2.5 rounded-lg bg-slate-950/80 border border-slate-800">
                <div className="text-cyan-300 font-bold">SYSTEM_ALERT_WINDOW (Display over other apps)</div>
                <div className="text-slate-400 mt-0.5">Used strictly to render the floating edge drawer for quick 1-tap background downloading while browsing social apps.</div>
              </div>
              <div className="p-2.5 rounded-lg bg-slate-950/80 border border-slate-800">
                <div className="text-cyan-300 font-bold">POST_NOTIFICATIONS</div>
                <div className="text-slate-400 mt-0.5">Employed to deliver download progress percentages and finished notification alerts.</div>
              </div>
              <div className="p-2.5 rounded-lg bg-slate-950/80 border border-slate-800">
                <div className="text-cyan-300 font-bold">READ_MEDIA_VIDEO / READ_EXTERNAL_STORAGE</div>
                <div className="text-slate-400 mt-0.5">Required to query <code className="text-white">Movies/OmniDrop</code> so the in-app library can rebuild itself and play videos offline.</div>
              </div>
            </div>
          </div>

          <div className="pt-2 border-t border-slate-800 flex items-center justify-between text-slate-400">
            <span>Direct Inquiries:</span>
            <a
              href="mailto:fatormajohn64@gmail.com"
              className="font-mono text-cyan-300 hover:underline flex items-center gap-1"
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
        <div className="flex items-center gap-3 pb-2 border-b border-cyan-500/20">
          <button
            onClick={() => setActiveSubScreen(null)}
            className="machine-btn px-3 py-1.5 rounded-lg flex items-center gap-1.5 text-xs font-mono text-cyan-300"
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
            <div className="w-20 h-20 rounded-2xl bg-gradient-to-br from-cyan-500 via-indigo-600 to-purple-600 p-0.5 shadow-[0_0_20px_rgba(0,240,255,0.4)]">
              <div className="w-full h-full rounded-[14px] bg-slate-950 flex items-center justify-center">
                <span className="font-mono font-black text-2xl tracking-wider text-cyan-300">
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
            <p className="font-mono text-xs text-cyan-400 mt-0.5">
              Independent Software Engineer • Sierra Leone 🇸🇱
            </p>
          </div>

          <div className="p-3.5 rounded-xl bg-slate-950/70 border border-slate-800 text-xs text-slate-300 leading-relaxed font-sans text-left">
            "JTech is an independent software creator based in Sierra Leone, building fast, practical tools for everyday mobile life entirely from a smartphone."
          </div>

          <div className="flex items-center justify-center gap-2 text-xs font-mono text-slate-400">
            <Terminal className="w-3.5 h-3.5 text-cyan-400" />
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
            className="machine-btn px-3 py-1.5 rounded-lg font-mono text-xs text-cyan-300 flex items-center gap-1.5"
          >
            <Mail className="w-3.5 h-3.5 text-cyan-400" />
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
        <div className="flex items-center gap-3 pb-2 border-b border-cyan-500/20">
          <button
            onClick={() => setActiveSubScreen(null)}
            className="machine-btn px-3 py-1.5 rounded-lg flex items-center gap-1.5 text-xs font-mono text-cyan-300"
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
              <div className="font-mono text-[11px] text-cyan-400">PRODUCTION BUILD 240</div>
            </div>
            <span className="px-2.5 py-1 rounded-lg bg-cyan-950 border border-cyan-500/40 text-cyan-300 font-mono text-xs font-bold">
              v2.4.0
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
                  className="px-2 py-1 rounded bg-slate-900 border border-slate-700 hover:border-cyan-400 font-mono text-[10px] text-cyan-300 flex items-center gap-1 active:scale-95"
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

        <div className="machine-surface rounded-xl p-3.5 flex items-center justify-between text-xs font-mono">
          <div className="flex items-center gap-2">
            <div className="w-2.5 h-2.5 rounded-full led-emerald" />
            <span className="text-slate-300">CORE STATUS: SYSTEM OPERATIONAL</span>
          </div>
          <span className="text-cyan-400">100% ONLINE</span>
        </div>
      </div>
    );
  }

  // MAIN ABOUT MENU (4 BIG TILES)
  return (
    <div className="space-y-4 pb-28">
      {/* Monospace Section Header */}
      <div className="flex items-center justify-between pt-1">
        <div>
          <h2 className="text-xs font-mono font-bold text-cyan-400 uppercase tracking-widest flex items-center gap-2">
            <span className="w-2 h-2 rounded-full led-cyan" />
            ABOUT // SYSTEM INTEL
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">Documentation, privacy audit & developer profile</p>
        </div>
        <div className="font-mono text-[10px] px-2 py-0.5 rounded bg-cyan-950/60 border border-cyan-500/30 text-cyan-300 font-bold">
          BUILD 2.4.0
        </div>
      </div>

      {/* 4 Big Machine Tiles */}
      <div className="grid grid-cols-1 gap-3">
        {menuItems.map((item) => {
          const Icon = item.icon;
          return (
            <button
              key={item.id}
              onClick={() => setActiveSubScreen(item.id)}
              className="machine-btn w-full p-4 rounded-xl text-left flex items-center justify-between group transition-all"
            >
              <div className="flex items-start gap-3.5 min-w-0">
                <div className="w-10 h-10 rounded-lg bg-slate-950 border border-cyan-500/30 flex items-center justify-center shrink-0 shadow-inner group-hover:border-cyan-400 transition">
                  <Icon className={`w-5 h-5 ${item.color}`} />
                </div>
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-[10px] text-cyan-400/80 font-semibold tracking-wider">
                      {item.code}
                    </span>
                    <span className="text-[10px] font-mono px-1.5 py-0.2 rounded bg-slate-950 border border-slate-800 text-slate-300">
                      {item.badge}
                    </span>
                  </div>
                  <h3 className="font-mono font-bold text-sm text-white mt-0.5 tracking-wide group-hover:text-cyan-300 transition">
                    {item.title}
                  </h3>
                  <p className="text-xs text-slate-400 mt-0.5 leading-snug line-clamp-1">
                    {item.subtitle}
                  </p>
                </div>
              </div>

              <div className="pl-2 shrink-0">
                <div className="w-7 h-7 rounded-lg bg-slate-950 border border-slate-800 flex items-center justify-center text-slate-400 group-hover:text-cyan-400 group-hover:border-cyan-500/40 transition">
                  <ChevronRight className="w-4 h-4" />
                </div>
              </div>
            </button>
          );
        })}
      </div>

      {/* Hardware Badge Bottom */}
      <div className="machine-surface rounded-xl p-3 flex items-center justify-between text-xs font-mono text-slate-400">
        <div className="flex items-center gap-2">
          <Terminal className="w-4 h-4 text-cyan-400" />
          <span>OMNIDROP // HARDWARE HYBRID</span>
        </div>
        <span className="text-slate-500">2026 EDITION</span>
      </div>
    </div>
  );
};
