import React from 'react';
import { LucideIcon } from 'lucide-react';

interface EmptyStateProps {
  icon: LucideIcon;
  title: string;
  message: string;
  compact?: boolean;
  tone?: 'accent' | 'emerald' | 'amber' | 'muted';
}

export const EmptyState: React.FC<EmptyStateProps> = ({
  icon: Icon,
  title,
  message,
  compact = false,
  tone = 'accent',
}) => {
  const iconBoxStyle =
    tone === 'emerald'
      ? 'border-emerald-500/40 text-emerald-400 bg-emerald-950/30 shadow-[0_0_14px_rgba(16,185,129,0.2)]'
      : tone === 'amber'
      ? 'border-amber-500/40 text-amber-400 bg-amber-950/30 shadow-[0_0_14px_rgba(245,158,11,0.2)]'
      : tone === 'muted'
      ? 'border-slate-700 text-slate-400 bg-slate-900/70'
      : 'border-accent text-accent bg-accent-subtle glow-accent-sm';

  return (
    <div
      className={`machine-surface rounded-xl text-center flex flex-col items-center justify-center ${
        compact ? 'px-4 py-5 space-y-2' : 'px-6 py-7 space-y-2.5'
      }`}
    >
      <div
        className={`rounded-xl border flex items-center justify-center shrink-0 ${
          compact ? 'w-10 h-10' : 'w-12 h-12'
        } ${iconBoxStyle}`}
      >
        <Icon className={compact ? 'w-5 h-5' : 'w-6 h-6'} />
      </div>
      <div className="space-y-1 max-w-xs mx-auto">
        <h4 className="font-mono text-xs font-bold text-white uppercase tracking-wider truncate">
          {title}
        </h4>
        <p className="text-xs text-slate-400 font-sans leading-relaxed">
          {message}
        </p>
      </div>
    </div>
  );
};
