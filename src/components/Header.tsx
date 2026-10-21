import React from 'react';
import { DownloadCloud, Sparkles, CheckCircle2, AlertCircle, Share2, Smartphone } from 'lucide-react';
import { usePWAInstall } from '../hooks/usePWAInstall';

interface HeaderProps {
  serverStatus: 'connected' | 'checking' | 'offline';
  serverLatency: number | null;
  onRefreshHealth: () => void;
  onOpenSettings: () => void;
}

export const Header: React.FC<HeaderProps> = ({
  serverStatus,
  serverLatency,
  onRefreshHealth,
  onOpenSettings,
}) => {
  const { isInstallable, isInstalled, isIOS, install } = usePWAInstall();
  const [showIOSModal, setShowIOSModal] = React.useState(false);

  return (
    <header className="sticky top-0 z-40 bg-slate-950/80 backdrop-blur-md border-b border-slate-800/80 px-4 py-3">
      <div className="max-w-md mx-auto flex items-center justify-between">
        {/* Brand */}
        <div className="flex items-center gap-2.5">
          <div className="relative flex items-center justify-center w-10 h-10 rounded-xl bg-gradient-to-tr from-indigo-600 via-purple-600 to-pink-500 shadow-lg shadow-indigo-500/20">
            <DownloadCloud className="w-5 h-5 text-white" />
            <div className="absolute -bottom-0.5 -right-0.5 w-3 h-3 bg-emerald-500 rounded-full border-2 border-slate-950" />
          </div>
          <div>
            <div className="flex items-center gap-1.5">
              <span className="font-bold text-base tracking-tight text-white">OmniDrop</span>
              <span className="text-[10px] font-semibold uppercase px-1.5 py-0.5 rounded bg-indigo-500/20 text-indigo-300 border border-indigo-500/30">
                PRO
              </span>
            </div>
            <p className="text-[11px] text-slate-400">Personal Video Downloader</p>
          </div>
        </div>

        {/* Actions & Status */}
        <div className="flex items-center gap-2">
          {/* PWA Install Button */}
          {isInstallable && !isInstalled && (
            <button
              onClick={install}
              className="flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-lg bg-indigo-600/30 text-indigo-200 border border-indigo-500/40 hover:bg-indigo-600/50 transition active:scale-95"
              title="Install app to home screen"
            >
              <Smartphone className="w-3.5 h-3.5 text-indigo-400" />
              <span>Install</span>
            </button>
          )}

          {isIOS && !isInstalled && (
            <button
              onClick={() => setShowIOSModal(true)}
              className="flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-lg bg-slate-800 text-slate-300 border border-slate-700 hover:bg-slate-700 transition"
              title="Add to iOS Home Screen"
            >
              <Share2 className="w-3.5 h-3.5 text-indigo-400" />
              <span>Install</span>
            </button>
          )}

          {/* Server Status Pill */}
          <button
            onClick={onRefreshHealth}
            className={`flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-lg border transition active:scale-95 ${
              serverStatus === 'connected'
                ? 'bg-emerald-950/40 border-emerald-800/60 text-emerald-300'
                : serverStatus === 'checking'
                ? 'bg-amber-950/40 border-amber-800/60 text-amber-300 animate-pulse'
                : 'bg-rose-950/40 border-rose-800/60 text-rose-300'
            }`}
            title="Click to re-check backend connection"
          >
            <span
              className={`w-2 h-2 rounded-full ${
                serverStatus === 'connected'
                  ? 'bg-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.8)]'
                  : serverStatus === 'checking'
                  ? 'bg-amber-400'
                  : 'bg-rose-500'
              }`}
            />
            <span className="font-mono text-[11px]">
              {serverStatus === 'connected'
                ? serverLatency !== null
                  ? `${serverLatency}ms`
                  : 'Ready'
                : serverStatus === 'checking'
                ? 'Ping...'
                : 'Offline'}
            </span>
          </button>
        </div>
      </div>

      {/* iOS Safari Guide Modal */}
      {showIOSModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4 backdrop-blur-sm animate-fade-in">
          <div className="w-full max-w-sm rounded-2xl bg-slate-900 border border-slate-800 p-6 shadow-2xl">
            <div className="flex items-center gap-3 mb-3">
              <div className="p-2.5 rounded-xl bg-indigo-500/20 text-indigo-400">
                <Share2 className="w-6 h-6" />
              </div>
              <h3 className="text-base font-semibold text-white">Install OmniDrop on iPhone</h3>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed mb-4">
              To install this personal video downloader as a native-like app on iOS:
            </p>
            <ol className="text-xs text-slate-400 space-y-2 list-decimal list-inside bg-slate-950/60 p-3 rounded-xl border border-slate-800">
              <li>Tap the <strong className="text-white">Share</strong> button in the bottom Safari bar.</li>
              <li>Scroll down and select <strong className="text-indigo-400">Add to Home Screen</strong>.</li>
              <li>Tap <strong className="text-white">Add</strong> in the top right corner.</li>
            </ol>
            <button
              onClick={() => setShowIOSModal(false)}
              className="mt-5 w-full py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 font-medium text-xs text-white transition"
            >
              Got it
            </button>
          </div>
        </div>
      )}
    </header>
  );
};
