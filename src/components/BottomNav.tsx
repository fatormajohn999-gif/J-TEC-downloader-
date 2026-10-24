import React from 'react';
import { DownloadCloud, Play, Image, Settings, Info } from 'lucide-react';

export type NavTab = 'home' | 'videos' | 'photos' | 'settings' | 'about';

interface BottomNavProps {
  currentTab: NavTab;
  onSelectTab: (tab: NavTab) => void;
  videoCount?: number;
  photoCount?: number;
}

export const BottomNav: React.FC<BottomNavProps> = ({
  currentTab,
  onSelectTab,
  videoCount,
  photoCount,
}) => {
  const tabs = [
    {
      id: 'home' as NavTab,
      label: 'Download',
      code: 'DL',
      icon: DownloadCloud,
    },
    {
      id: 'videos' as NavTab,
      label: 'Videos',
      code: 'VID',
      icon: Play,
      badge: videoCount && videoCount > 0 ? videoCount : undefined,
    },
    {
      id: 'photos' as NavTab,
      label: 'Photos',
      code: 'PIC',
      icon: Image,
      badge: photoCount && photoCount > 0 ? photoCount : undefined,
    },
    {
      id: 'settings' as NavTab,
      label: 'Settings',
      code: 'SET',
      icon: Settings,
    },
    {
      id: 'about' as NavTab,
      label: 'About',
      code: 'SYS',
      icon: Info,
    },
  ];

  return (
    <nav className="fixed bottom-0 left-0 right-0 z-40 bg-[#080B10]/95 backdrop-blur-lg border-t border-cyan-500/20 px-3 pb-safe pt-2">
      <div className="max-w-md mx-auto flex items-center justify-around">
        {tabs.map((tab) => {
          const Icon = tab.icon;
          const isActive = currentTab === tab.id;

          return (
            <button
              key={tab.id}
              onClick={() => onSelectTab(tab.id)}
              className={`relative flex flex-col items-center py-1.5 px-3 rounded-lg transition-all duration-150 active:scale-95 ${
                isActive
                  ? 'text-cyan-300 font-bold'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <div className="relative">
                <Icon
                  className={`w-5 h-5 transition-transform duration-150 ${
                    isActive ? 'scale-110 text-cyan-400 drop-shadow-[0_0_8px_rgba(0,240,255,0.7)]' : ''
                  }`}
                />
                {tab.badge !== undefined && (
                  <span className="absolute -top-1 -right-2.5 px-1 py-0.2 text-[9px] font-mono font-bold rounded-full bg-cyan-400 text-slate-950 min-w-3.5 text-center shadow-[0_0_6px_rgba(0,240,255,0.8)]">
                    {tab.badge > 99 ? '99+' : tab.badge}
                  </span>
                )}
              </div>

              <span className="text-[11px] font-mono mt-1 tracking-wider uppercase">
                {tab.label}
              </span>

              {/* Machine LED indicator dot when active */}
              {isActive && (
                <div className="absolute -bottom-1 w-4 h-1 rounded-full led-cyan" />
              )}
            </button>
          );
        })}
      </div>
    </nav>
  );
};
