import React from 'react';
import { Loader2, Server } from 'lucide-react';

interface ServerWakingBannerProps {
  isWaking: boolean;
  attempt: number;
}

export const ServerWakingBanner: React.FC<ServerWakingBannerProps> = ({ isWaking, attempt }) => {
  if (!isWaking) return null;

  return (
    <div className="mb-4 p-3.5 rounded-xl machine-surface border-accent-strong glow-accent-sm animate-pulse">
      <div className="flex items-center gap-3">
        <div className="p-2 rounded-lg bg-accent-soft border border-accent text-accent shrink-0">
          <Loader2 className="w-5 h-5 animate-spin" />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center justify-between gap-2">
            <h4 className="font-mono text-xs font-bold text-white flex items-center gap-1.5 uppercase truncate">
              <Server className="w-3.5 h-3.5 text-accent shrink-0" />
              <span className="truncate">WAKING UP SERVER...</span>
            </h4>
            <span className="text-[11px] font-mono text-accent-soft font-bold tabular-nums whitespace-nowrap shrink-0">
              PING #{attempt}
            </span>
          </div>
          <p className="text-[11px] text-slate-300 mt-0.5">
            Render free-tier instances sleep when idle. Connecting automatically...
          </p>
        </div>
      </div>
    </div>
  );
};
