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
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-4 bg-black/75 backdrop-blur-sm animate-fade-in">
      <div className="w-full max-w-sm rounded-2xl bg-slate-900 border border-slate-800 p-5 shadow-2xl space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="p-2 rounded-xl bg-indigo-500/20 text-indigo-400">
              <Clipboard className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-white">Link Detected in Clipboard</h3>
              <p className="text-[11px] text-slate-400">Found {platformName} URL</p>
            </div>
          </div>
          <button
            onClick={onDismiss}
            className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-3 rounded-xl bg-slate-950/80 border border-slate-800/80 flex items-center gap-2 overflow-hidden">
          <LinkIcon className="w-4 h-4 text-indigo-400 shrink-0" />
          <span className="text-xs font-mono text-slate-300 truncate">
            {detectedUrl}
          </span>
        </div>

        <div className="flex gap-2 pt-1">
          <button
            onClick={onDismiss}
            className="flex-1 py-2.5 px-3 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-medium transition"
          >
            Ignore
          </button>
          <button
            onClick={() => onConfirm(detectedUrl)}
            className="flex-1 py-2.5 px-3 rounded-xl bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-500 hover:to-purple-500 text-white text-xs font-medium flex items-center justify-center gap-1.5 shadow-lg shadow-indigo-600/30 transition"
          >
            <Download className="w-3.5 h-3.5" />
            Download this link?
          </button>
        </div>
      </div>
    </div>
  );
};
