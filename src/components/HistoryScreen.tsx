import React from 'react';
import { History, Download, Trash2, ExternalLink, Copy, Check, Clock, Film } from 'lucide-react';
import { DownloadHistoryItem } from '../types';

interface HistoryScreenProps {
  history: DownloadHistoryItem[];
  onReDownload: (url: string) => void;
  onDeleteItem: (id: string) => void;
  onClearAll: () => void;
}

export const HistoryScreen: React.FC<HistoryScreenProps> = ({
  history,
  onReDownload,
  onDeleteItem,
  onClearAll,
}) => {
  const [copiedId, setCopiedId] = React.useState<string | null>(null);

  const copyUrl = (id: string, url: string) => {
    navigator.clipboard.writeText(url);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  const formatDate = (timestamp: number) => {
    const d = new Date(timestamp);
    return d.toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  return (
    <div className="space-y-4 pb-24">
      <div className="flex items-center justify-between pt-1">
        <div>
          <h2 className="text-base font-bold text-white flex items-center gap-2">
            <History className="w-4 h-4 text-indigo-400" />
            Download History
          </h2>
          <p className="text-xs text-slate-400">
            {history.length} {history.length === 1 ? 'video' : 'videos'} saved on this device
          </p>
        </div>

        {history.length > 0 && (
          <button
            onClick={onClearAll}
            className="flex items-center gap-1 text-xs text-rose-400 hover:text-rose-300 px-2.5 py-1.5 rounded-lg bg-rose-950/30 border border-rose-900/50 transition active:scale-95"
          >
            <Trash2 className="w-3.5 h-3.5" />
            Clear All
          </button>
        )}
      </div>

      {history.length === 0 ? (
        <div className="p-8 text-center rounded-2xl bg-slate-900/40 border border-slate-800/80 space-y-3">
          <div className="w-12 h-12 mx-auto rounded-2xl bg-slate-800 flex items-center justify-center text-slate-500">
            <Film className="w-6 h-6" />
          </div>
          <h3 className="text-sm font-semibold text-slate-300">No downloads yet</h3>
          <p className="text-xs text-slate-500 max-w-xs mx-auto">
            Paste a link from TikTok, YouTube, Instagram or Facebook on the home tab to start downloading.
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          {history.map((item) => (
            <div
              key={item.id}
              className="p-3 rounded-2xl bg-slate-900 border border-slate-800 flex gap-3 items-center hover:border-slate-700 transition"
            >
              {/* Thumbnail */}
              <div className="relative w-20 h-14 rounded-xl overflow-hidden bg-slate-950 shrink-0 border border-slate-800">
                {item.thumbnail ? (
                  <img
                    src={item.thumbnail}
                    alt={item.title}
                    className="w-full h-full object-cover"
                    loading="lazy"
                  />
                ) : (
                  <div className="w-full h-full flex items-center justify-center text-slate-600">
                    <Film className="w-5 h-5" />
                  </div>
                )}
                <span className="absolute bottom-0.5 right-0.5 px-1 rounded bg-black/80 text-[9px] font-mono text-white">
                  {item.quality.toUpperCase()}
                </span>
              </div>

              {/* Info */}
              <div className="flex-1 min-w-0">
                <h4 className="text-xs font-semibold text-white truncate" title={item.title}>
                  {item.title}
                </h4>
                <div className="flex items-center gap-2 mt-1 text-[11px] text-slate-400">
                  <span className="capitalize px-1.5 py-0.2 rounded bg-slate-800 text-[10px] text-slate-300">
                    {item.platform}
                  </span>
                  <span className="flex items-center gap-1 text-slate-500">
                    <Clock className="w-3 h-3" />
                    {formatDate(item.downloadedAt)}
                  </span>
                </div>
              </div>

              {/* Actions */}
              <div className="flex items-center gap-1 shrink-0">
                <button
                  onClick={() => copyUrl(item.id, item.url)}
                  className="p-2 rounded-lg bg-slate-800 text-slate-400 hover:text-white transition"
                  title="Copy original link"
                >
                  {copiedId === item.id ? (
                    <Check className="w-3.5 h-3.5 text-emerald-400" />
                  ) : (
                    <Copy className="w-3.5 h-3.5" />
                  )}
                </button>
                <button
                  onClick={() => onReDownload(item.url)}
                  className="p-2 rounded-lg bg-indigo-600/30 text-indigo-300 border border-indigo-500/40 hover:bg-indigo-600/50 transition"
                  title="Re-download video"
                >
                  <Download className="w-3.5 h-3.5" />
                </button>
                <button
                  onClick={() => onDeleteItem(item.id)}
                  className="p-2 rounded-lg bg-slate-800 text-slate-500 hover:text-rose-400 transition"
                  title="Remove from history"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
