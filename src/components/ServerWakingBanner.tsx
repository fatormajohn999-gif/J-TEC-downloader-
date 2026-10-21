import React from 'react';
import { Loader2, Server, Sparkles } from 'lucide-react';

interface ServerWakingBannerProps {
  isWaking: boolean;
  attempt: number;
}

export const ServerWakingBanner: React.FC<ServerWakingBannerProps> = ({ isWaking, attempt }) => {
  if (!isWaking) return null;

  return (
    <div className="mb-4 p-3.5 rounded-2xl bg-gradient-to-r from-indigo-950/80 via-purple-950/80 to-slate-900 border border-indigo-500/40 shadow-lg shadow-indigo-950/50 animate-pulse">
      <div className="flex items-center gap-3">
        <div className="p-2 rounded-xl bg-indigo-500/20 text-indigo-400 shrink-0">
          <Loader2 className="w-5 h-5 animate-spin" />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center justify-between">
            <h4 className="text-xs font-semibold text-indigo-200 flex items-center gap-1.5">
              <Server className="w-3.5 h-3.5" />
              Waking up server, please wait...
            </h4>
            <span className="text-[11px] font-mono text-indigo-400">
              Ping #{attempt}
            </span>
          </div>
          <p className="text-[11px] text-slate-300 mt-0.5">
            Render free-tier instances sleep when inactive. Connecting automatically...
          </p>
        </div>
      </div>
    </div>
  );
};
