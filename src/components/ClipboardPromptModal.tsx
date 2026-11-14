import React from 'react';
import { Clipboard, Download, X, Link as LinkIcon } from 'lucide-react';
import { detectPlatform } from '../services/api';

interface ClipboardPromptModalProps {
  detectedUrl: string;
  onConfirm: (url: string) => void;
  onDismiss: () => void;
}

export const ClipboardPromptModal: React.FC<ClipboardPromptModalProps> = ({
  detectedUrl,
  onConfirm,
  onDismiss,
}) => {
  if (!detectedUrl) return null;

  const platform = detectPlatform(detectedUrl);
  const platformName =
    platform === 'tiktok'
      ? 'TikTok'
      : platform === 'youtube'
      ? 'YouTube'
      : platform === 'instagram'
      ? 'Instagram'
      : platform === 'facebook'
      ? 'Facebook'
      : 'Video';

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-fade-in">
      <div className="machine-surface w-full max-w-sm rounded-xl p-5 shadow-2xl space-y-4">
        <div className="flex items-center justify-between pb-2 border-b border-accent">
          <div className="flex items-center gap-2.5 min-w-0">
            <div className="p-2 rounded-lg bg-accent-soft border border-accent text-accent shrink-0">
              <Clipboard className="w-4 h-4" />
            </div>
            <div className="min-w-0">
              <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider truncate">
                CLIPBOARD LINK DETECTED
              </h3>
              <p className="text-[11px] font-mono text-accent-soft truncate">
                SOURCE: {platformName.toUpperCase()} URL
              </p>
            </div>
          </div>
          <button
            onClick={onDismiss}
            className="p-1.5 rounded-lg text-slate-400 hover:text-white transition shrink-0"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-3 rounded-lg bg-slate-950 border border-slate-800 flex items-center gap-2 overflow-hidden">
          <LinkIcon className="w-4 h-4 text-accent shrink-0" />
          <span className="text-xs font-mono text-slate-300 truncate">
            {detectedUrl}
          </span>
        </div>

        <div className="flex gap-2 pt-1">
          <button
            onClick={onDismiss}
            className="machine-btn flex-1 py-2.5 px-3 rounded-lg text-slate-300 text-xs font-mono uppercase whitespace-nowrap"
          >
            IGNORE
          </button>
          <button
            onClick={() => onConfirm(detectedUrl)}
            className="machine-btn-primary flex-1 py-2.5 px-3 rounded-lg text-xs font-mono uppercase flex items-center justify-center gap-1.5 whitespace-nowrap"
          >
            <Download className="w-3.5 h-3.5" />
            <span>INGEST LINK</span>
          </button>
        </div>
      </div>
    </div>
  );
};
