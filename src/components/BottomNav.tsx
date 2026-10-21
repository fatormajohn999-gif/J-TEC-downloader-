import React from 'react';
import { DownloadCloud, History, Settings } from 'lucide-react';

export type NavTab = 'home' | 'history' | 'settings';

interface BottomNavProps {
  currentTab: NavTab;
  onSelectTab: (tab: NavTab) => void;
  historyCount: number;
}

export const BottomNav: React.FC<BottomNavProps> = ({
  currentTab,
  onSelectTab,
  historyCount,
}) => {
  const tabs = [
    {
      id: 'home' as NavTab,
      label: 'Download',
      icon: DownloadCloud,
    },
    {
      id: 'history' as NavTab,
      label: 'History',
      icon: History,
      badge: historyCount > 0 ? historyCount : undefined,
    },
    {
      id: 'settings' as NavTab,
      label: 'Settings',
      icon: Settings,
    },
  ];

  return (
    <nav className="fixed bottom-0 left-0 right-0 z-40 bg-slate-950/90 backdrop-blur-lg border-t border-slate-800/80 px-4 pb-safe pt-2">
      <div className="max-w-md mx-auto flex items-center justify-around">
        {tabs.map((tab) => {
          const Icon = tab.icon;
          const isActive = currentTab === tab.id;

          return (
            <button
              key={tab.id}
              onClick={() => onSelectTab(tab.id)}
              className={`relative flex flex-col items-center py-1.5 px-4 rounded-xl transition-all duration-200 active:scale-95 ${
                isActive ? 'text-indigo-400 font-semibold' : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <div className="relative">
                <Icon className={`w-5 h-5 transition-transform duration-200 ${isActive ? 'scale-110' : ''}`} />
                {tab.badge !== undefined && (
                  <span className="absolute -top-1 -right-2.5 px-1.5 py-0.2 text-[10px] font-bold rounded-full bg-indigo-600 text-white min-w-4 text-center">
                    {tab.badge > 99 ? '99+' : tab.badge}
                  </span>
                )}
              </div>
              <span className="text-[11px] mt-1">{tab.label}</span>
              {isActive && (
                <div className="absolute -bottom-1 w-6 h-0.5 rounded-full bg-gradient-to-r from-indigo-500 to-purple-500 shadow-[0_0_8px_rgba(99,102,241,0.8)]" />
              )}
            </button>
          );
        })}
      </div>
    </nav>
  );
};
