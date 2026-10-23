import React from 'react';
import { DownloadCloud, Smartphone, Share2, RefreshCw } from 'lucide-react';
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

  // Compute machine latency chip state
  // Green under 400ms, Yellow under 1500ms, Red above or unreachable, "SLEEPING" when over 3 seconds
  let chipClass = 'border-rose-500/40 text-rose-300 bg-rose-950/40';
  let ledClass = 'led-rose';
  let label = 'OFFLINE';

  if (serverStatus === 'checking') {
    chipClass = 'border-amber-500/40 text-amber-300 bg-amber-950/40 animate-pulse';
    ledClass = 'led-amber';
    label = 'PING...';
  } else if (serverStatus === 'connected' && serverLatency !== null) {
    if (serverLatency > 3000) {
      chipClass = 'border-amber-500/60 text-amber-300 bg-amber-950/60 animate-pulse';
      ledClass = 'led-amber';
      label = 'SLEEPING';
    } else if (serverLatency < 400) {
      chipClass = 'border-emerald-500/40 text-emerald-300 bg-emerald-950/40';
      ledClass = 'led-emerald';
      label = `${serverLatency}ms`;
    } else if (serverLatency < 1500) {
      chipClass = 'border-amber-500/40 text-amber-300 bg-amber-950/40';
      ledClass = 'led-amber';
      label = `${serverLatency}ms`;
    } else {
      chipClass = 'border-rose-500/40 text-rose-300 bg-rose-950/40';
      ledClass = 'led-rose';
      label = `${serverLatency}ms`;
    }
  }

  return (
    <header className="sticky top-0 z-40 bg-[#080B10]/95 backdrop-blur-md border-b border-cyan-500/20 px-4 py-2.5">
      <div className="max-w-md mx-auto flex items-center justify-between">
        {/* Brand in Robotic Style */}
        <div className="flex items-center gap-2.5">
          <div className="relative flex items-center justify-center w-9 h-9 rounded-lg bg-slate-950 border border-cyan-400/40 shadow-[0_0_12px_rgba(0,240,255,0.25)]">
            <DownloadCloud className="w-4 h-4 text-cyan-400" />
            <div className="absolute -top-0.5 -right-0.5 w-1.5 h-1.5 rounded-full led-cyan" />
          </div>
          <div>
            <div className="flex items-center gap-1.5">
              <span className="font-mono font-black text-sm tracking-widest text-white uppercase">
                OMNIDROP
              </span>
              <span className="font-mono text-[9px] font-bold px-1.5 py-0.2 rounded bg-cyan-950 text-cyan-300 border border-cyan-500/30">
                PRO
              </span>
            </div>
            <p className="font-mono text-[10px] text-slate-400 tracking-tight">INGESTION UNIT</p>
          </div>
        </div>

        {/* Status Chip & Actions */}
        <div className="flex items-center gap-2">
          {/* PWA Install Button */}
          {isInstallable && !isInstalled && (
            <button
              onClick={install}
              className="machine-btn flex items-center gap-1 text-[11px] font-mono px-2 py-1 rounded-md text-cyan-300"
              title="Install app to home screen"
            >
              <Smartphone className="w-3 h-3 text-cyan-400" />
              <span>INSTALL</span>
            </button>
          )}

          {isIOS && !isInstalled && (
            <button
              onClick={() => setShowIOSModal(true)}
              className="machine-btn flex items-center gap-1 text-[11px] font-mono px-2 py-1 rounded-md text-cyan-300"
              title="Add to iOS Home Screen"
            >
              <Share2 className="w-3 h-3 text-cyan-400" />
              <span>INSTALL</span>
            </button>
          )}

          {/* Machine Latency Chip */}
          <button
            onClick={onRefreshHealth}
            className={`machine-btn flex items-center gap-1.5 text-xs font-mono px-2.5 py-1 rounded-lg border transition ${chipClass}`}
            title="Click to ping backend server"
          >
            <span className={`w-1.5 h-1.5 rounded-full ${ledClass}`} />
            <span className="font-bold tracking-wider">{label}</span>
          </button>
        </div>
      </div>

      {/* iOS Modal */}
      {showIOSModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4 backdrop-blur-sm animate-fade-in">
          <div className="machine-surface w-full max-w-sm rounded-xl p-5 shadow-2xl space-y-4">
            <div className="flex items-center gap-3">
              <div className="p-2 rounded-lg bg-cyan-950 border border-cyan-500/40 text-cyan-400">
                <Share2 className="w-5 h-5" />
              </div>
              <h3 className="font-mono text-sm font-bold text-white uppercase tracking-wider">
                Install OmniDrop on iOS
              </h3>
            </div>
            <ol className="text-xs text-slate-300 space-y-2 list-decimal list-inside bg-slate-950 p-3 rounded-lg border border-slate-800 font-mono">
              <li>Tap the <strong className="text-white">Share</strong> button in Safari.</li>
              <li>Select <strong className="text-cyan-400">Add to Home Screen</strong>.</li>
              <li>Tap <strong className="text-white">Add</strong> in top right.</li>
            </ol>
            <button
              onClick={() => setShowIOSModal(false)}
              className="machine-btn-primary w-full py-2 rounded-lg font-mono text-xs uppercase"
            >
              CONFIRM // CLOSE
            </button>
          </div>
        </div>
      )}
    </header>
  );
};
