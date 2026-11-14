import React, { useEffect, useState } from 'react';
import { DownloadCloud } from 'lucide-react';

interface BootSplashProps {
  reduceAnimations?: boolean;
  onFinish: () => void;
}

export const BootSplash: React.FC<BootSplashProps> = ({
  reduceAnimations = false,
  onFinish,
}) => {
  const [segmentCount, setSegmentCount] = useState(reduceAnimations ? 16 : 2);
  const [fadingOut, setFadingOut] = useState(false);

  useEffect(() => {
    if (reduceAnimations) {
      onFinish();
      return;
    }

    const totalSegments = 16;
    const stepMs = 920 / (totalSegments - 2);
    const interval = setInterval(() => {
      setSegmentCount((prev) => {
        if (prev >= totalSegments) {
          clearInterval(interval);
          return totalSegments;
        }
        return prev + 1;
      });
    }, stepMs);

    const fadeTimer = setTimeout(() => {
      setFadingOut(true);
    }, 950);

    const doneTimer = setTimeout(() => {
      onFinish();
    }, 1200);

    return () => {
      clearInterval(interval);
      clearTimeout(fadeTimer);
      clearTimeout(doneTimer);
    };
  }, [reduceAnimations, onFinish]);

  if (reduceAnimations) return null;

  const pct = Math.round((segmentCount / 16) * 100);

  return (
    <div
      className={`fixed inset-0 z-[100] bg-[var(--od-bg-canvas)] bg-grid-machine flex flex-col items-center justify-center px-6 transition-opacity duration-250 ${
        fadingOut ? 'opacity-0 pointer-events-none' : 'opacity-100'
      }`}
    >
      <div className="w-full max-w-xs flex flex-col items-center space-y-6">
        {/* Glowing Hardware Logo */}
        <div className="relative flex items-center justify-center w-20 h-20 rounded-2xl machine-surface border-accent-strong glow-accent">
          <DownloadCloud className="w-10 h-10 text-accent drop-shadow-[0_0_12px_rgba(var(--od-accent-rgb),0.7)]" />
          <span className="absolute -top-1 -right-1 w-2.5 h-2.5 rounded-full led-accent" />
          <span className="absolute -bottom-1 -left-1 w-2 h-2 rounded-full led-emerald" />
        </div>

        {/* Boot Title */}
        <div className="text-center space-y-1">
          <div className="font-mono text-sm font-black tracking-[0.22em] text-white uppercase whitespace-nowrap">
            OMNIDROP // INITIALIZING
          </div>
          <div className="font-mono text-[10px] tracking-widest text-accent-soft uppercase whitespace-nowrap">
            MEDIA INGESTION ENGINE
          </div>
        </div>

        {/* 16-Segment Boot Loading Bar */}
        <div className="w-full space-y-2">
          <div className="grid grid-cols-16 gap-1 p-1.5 rounded-lg bg-slate-950 border border-accent">
            {Array.from({ length: 16 }).map((_, idx) => {
              const active = idx < segmentCount;
              return (
                <div
                  key={idx}
                  className={`h-2.5 rounded-xs transition-colors duration-75 ${
                    active
                      ? 'bg-accent glow-accent-sm'
                      : 'bg-slate-900 border border-slate-800'
                  }`}
                />
              );
            })}
          </div>

          <div className="flex items-center justify-between font-mono text-[10px] text-slate-400">
            <span className="whitespace-nowrap">SYSTEM CHECK: OK</span>
            <span className="tabular-nums text-accent-soft font-bold w-10 text-right whitespace-nowrap">
              {pct}%
            </span>
          </div>
        </div>
      </div>
    </div>
  );
};
