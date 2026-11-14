import React from 'react';
import { DownloadCloud, Play, Image, Settings, Info } from 'lucide-react';

export type NavTab = 'home' | 'videos' | 'photos' | 'settings' | 'about';

interface BottomNavProps {
  currentTab: NavTab;
  onSelectTab: (tab: NavTab) => void;
  videoCount?: number;
  photoCount?: number;
  hasUpdate?: boolean;
  hasUnreadWhatsNew?: boolean;
}

export const BottomNav: React.FC<BottomNavProps> = ({
  currentTab,
  onSelectTab,
  videoCount,
  photoCount,
  hasUpdate,
  hasUnreadWhatsNew,
}) => {
  const tabs = [
    {
      id: 'home' as NavTab,
      label: 'Download',
      icon: DownloadCloud,
    },
    {
      id: 'videos' as NavTab,
      label: 'Videos',
      icon: Play,
      badge: videoCount && videoCount > 0 ? videoCount : undefined,
    },
    {
      id: 'photos' as NavTab,
      label: 'Photos',
      icon: Image,
      badge: photoCount && photoCount > 0 ? photoCount : undefined,
    },
    {
      id: 'settings' as NavTab,
      label: 'Settings',
      icon: Settings,
    },
    {
      id: 'about' as NavTab,
      label: 'About',
      icon: Info,
    },
  ];

  const activeIndex = Math.max(
    0,
    tabs.findIndex((t) => t.id === currentTab)
  );

  return (
    <nav className="fixed bottom-0 left-0 right-0 z-40 bg-[var(--od-bg-canvas)]/95 backdrop-blur-lg border-t border-accent px-2 pb-safe pt-2">
      <div className="max-w-md mx-auto relative grid grid-cols-5 items-center pb-1.5">
        {/* Sliding glowing indicator under the active tab */}
        <div
          aria-hidden="true"
          className="absolute bottom-0 left-0 w-1/5 flex justify-center pointer-events-none transition-transform duration-200 ease-out"
          style={{ transform: `translateX(${activeIndex * 100}%)` }}
        >
          <div className="w-8 h-1 rounded-full led-accent" />
        </div>

        {tabs.map((tab) => {
          const Icon = tab.icon;
          const isActive = currentTab === tab.id;

          return (
            <button
              key={tab.id}
              onClick={() => onSelectTab(tab.id)}
              className={`relative flex flex-col items-center justify-center py-1.5 px-1 rounded-lg transition-colors duration-150 ${
                isActive
                  ? 'text-accent-soft font-bold'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <div className="relative flex items-center justify-center">
                <Icon
                  className={`w-5 h-5 transition-transform duration-150 ${
                    isActive
                      ? 'scale-110 text-accent drop-shadow-[0_0_8px_rgba(var(--od-accent-rgb),0.75)]'
                      : ''
                  }`}
                />
                {tab.badge !== undefined && (
                  <span className="absolute -top-1.5 -right-3 px-1 py-0.2 text-[9px] font-mono font-bold rounded-full bg-accent text-slate-950 min-w-4 text-center tabular-nums glow-accent-sm whitespace-nowrap">
                    {tab.badge > 99 ? '99+' : tab.badge}
                  </span>
                )}
                {tab.id === 'settings' && hasUpdate && (
                  <span className="absolute -top-1 -right-1.5 w-2.5 h-2.5 rounded-full bg-amber-400 shadow-[0_0_8px_rgba(245,158,11,0.95)] animate-pulse" />
                )}
                {tab.id === 'about' && hasUnreadWhatsNew && (
                  <span className="absolute -top-1 -right-1.5 w-2.5 h-2.5 rounded-full led-accent animate-pulse" />
                )}
              </div>

              <span className="text-[11px] font-mono mt-1 tracking-wide uppercase whitespace-nowrap">
                {tab.label}
              </span>
            </button>
          );
        })}
      </div>
    </nav>
  );
};
