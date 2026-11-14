import React, { useState, useEffect, useCallback } from 'react';
import {
  X,
  Server,
  Layers,
  HardDrive,
  BatteryCharging,
  ChevronRight,
  ChevronLeft,
  CheckCircle2,
  AlertCircle,
  Loader2,
  RefreshCw,
} from 'lucide-react';
import { AppSettings } from '../types';
import { checkServerHealth } from '../services/api';
import { VideoDownloader, SystemChecksResult } from '../plugins/videoDownloader';

interface FirstRunSetupModalProps {
  isOpen: boolean;
  settings: AppSettings;
  onUpdateSettings: (newSettings: AppSettings) => void;
  onClose: () => void;
  onRefreshHealth: () => void;
}

export const SETUP_COMPLETED_STORAGE_KEY = 'omnidrop_setup_completed_v1';

export const FirstRunSetupModal: React.FC<FirstRunSetupModalProps> = ({
  isOpen,
  settings,
  onUpdateSettings,
  onClose,
  onRefreshHealth,
}) => {
  const [step, setStep] = useState<number>(1);
  const [backendUrl, setBackendUrl] = useState<string>(settings.backendUrl || '');
  const [apiKey, setApiKey] = useState<string>(settings.apiKey || '');
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'ok' | 'error'>('idle');
  const [testMessage, setTestMessage] = useState<string>('');
  const [checks, setChecks] = useState<SystemChecksResult>({
    overlayGranted: false,
    notificationsGranted: false,
    mediaGranted: false,
    installUpdatesGranted: false,
    batteryUnrestricted: false,
    floatingServiceRunning: false,
  });

  useEffect(() => {
    if (isOpen) {
      setBackendUrl(settings.backendUrl || '');
      setApiKey(settings.apiKey || '');
      setStep(1);
      setTestStatus('idle');
      setTestMessage('');
    }
  }, [isOpen, settings.backendUrl, settings.apiKey]);

  const refreshChecks = useCallback(async () => {
    try {
      const res = await VideoDownloader.getSystemChecks();
      if (res) {
        setChecks(res);
      }
    } catch {}
  }, []);

  useEffect(() => {
    if (!isOpen) return;
    refreshChecks();

    const onFocus = () => refreshChecks();
    const onVisibility = () => {
      if (document.visibilityState === 'visible') refreshChecks();
    };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisibility);
    const poll = setInterval(refreshChecks, 2000);

    return () => {
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisibility);
      clearInterval(poll);
    };
  }, [isOpen, refreshChecks]);

  if (!isOpen) return null;

  const saveServerConfig = () => {
    const cleanUrl = backendUrl.trim();
    const cleanKey = apiKey.trim();
    if (cleanUrl !== settings.backendUrl || cleanKey !== settings.apiKey) {
      onUpdateSettings({
        ...settings,
        backendUrl: cleanUrl,
        apiKey: cleanKey,
      });
      onRefreshHealth();
    }
  };

  const handleTestServer = async () => {
    setTestStatus('testing');
    setTestMessage('');
    saveServerConfig();
    const res = await checkServerHealth({
      ...settings,
      backendUrl: backendUrl.trim(),
      apiKey: apiKey.trim(),
    });
    if (res.ok) {
      setTestStatus('ok');
      setTestMessage(`ONLINE • ${res.latencyMs}ms`);
    } else {
      setTestStatus('error');
      setTestMessage(res.error || 'Server unreachable');
    }
  };

  const markCompletedAndClose = () => {
    saveServerConfig();
    try {
      localStorage.setItem(SETUP_COMPLETED_STORAGE_KEY, 'true');
    } catch {}
    onClose();
  };

  const handleNext = () => {
    if (step === 1) {
      saveServerConfig();
    }
    if (step < 4) {
      setStep(step + 1);
      refreshChecks();
    } else {
      markCompletedAndClose();
    }
  };

  const handleFixOverlay = async () => {
    try {
      await VideoDownloader.requestOverlayPermission();
    } catch {}
    setTimeout(refreshChecks, 800);
  };

  const handleFixNotifications = async () => {
    try {
      await VideoDownloader.requestNotificationPermission();
    } catch {}
    setTimeout(refreshChecks, 800);
  };

  const handleFixMedia = async () => {
    try {
      await VideoDownloader.requestVideoPermission();
    } catch {}
    setTimeout(refreshChecks, 800);
  };

  const handleFixBattery = async () => {
    try {
      await VideoDownloader.requestBatteryUnrestricted();
    } catch {}
    setTimeout(refreshChecks, 800);
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4 animate-fade-in">
      <div className="w-full max-w-md rounded-2xl bg-[#090D15] border border-accent-strong glow-accent p-5 space-y-4">
        {/* Header */}
        <div className="flex items-center justify-between pb-2.5 border-b border-accent">
          <div className="flex items-center gap-2 min-w-0">
            <span className="w-2.5 h-2.5 rounded-full led-accent shrink-0" />
            <div className="min-w-0">
              <h3 className="font-mono text-xs font-bold uppercase tracking-wider text-white truncate">
                OMNIDROP // SETUP GUIDE
              </h3>
              <p className="font-mono text-[10px] text-accent-soft tabular-nums">
                STEP {step} OF 4
              </p>
            </div>
          </div>

          <button
            onClick={markCompletedAndClose}
            className="machine-btn px-2.5 py-1 rounded-md font-mono text-[10px] text-slate-400 hover:text-white flex items-center gap-1 shrink-0 whitespace-nowrap"
          >
            <span>SKIP</span>
            <X className="w-3 h-3" />
          </button>
        </div>

        {/* Stepper progress blocks */}
        <div className="grid grid-cols-4 gap-1.5">
          {[1, 2, 3, 4].map((idx) => (
            <div
              key={idx}
              className={`h-1.5 rounded-full transition-all ${
                idx <= step ? 'bg-accent glow-accent-sm' : 'bg-slate-800'
              }`}
            />
          ))}
        </div>

        {/* Step 1: Server URL */}
        {step === 1 && (
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <Server className="w-4 h-4 text-accent shrink-0" />
              <h4 className="font-mono text-xs font-bold text-white uppercase">
                1. SET THE SERVER URL
              </h4>
            </div>
            <p className="text-xs text-slate-400 leading-relaxed">
              Enter your Render / FastAPI backend URL so OmniDrop can inspect and download videos.
            </p>

            <div className="space-y-2.5">
              <div>
                <label className="block font-mono text-[10px] text-slate-400 uppercase mb-1">
                  BACKEND SERVER URL
                </label>
                <input
                  type="url"
                  value={backendUrl}
                  onChange={(e) => setBackendUrl(e.target.value)}
                  placeholder="https://your-omni-server.onrender.com"
                  className="machine-input w-full px-3 py-2 rounded-lg font-mono text-xs"
                />
              </div>

              <div>
                <label className="block font-mono text-[10px] text-slate-400 uppercase mb-1">
                  API SECURITY KEY (OPTIONAL)
                </label>
                <input
                  type="password"
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                  placeholder="••••••••••••••••"
                  className="machine-input w-full px-3 py-2 rounded-lg font-mono text-xs"
                />
              </div>

              <div className="flex items-center justify-between gap-2 pt-1">
                <button
                  onClick={handleTestServer}
                  disabled={testStatus === 'testing'}
                  className="machine-btn px-3 py-1.5 rounded-lg text-xs font-mono text-accent-soft flex items-center gap-1.5 shrink-0 whitespace-nowrap"
                >
                  {testStatus === 'testing' ? (
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  ) : (
                    <RefreshCw className="w-3.5 h-3.5" />
                  )}
                  <span>TEST LINK</span>
                </button>

                {testStatus === 'ok' && (
                  <span className="font-mono text-xs text-emerald-400 flex items-center gap-1 tabular-nums whitespace-nowrap">
                    <CheckCircle2 className="w-3.5 h-3.5 shrink-0" />
                    {testMessage}
                  </span>
                )}
                {testStatus === 'error' && (
                  <span className="font-mono text-xs text-rose-400 flex items-center gap-1 truncate">
                    <AlertCircle className="w-3.5 h-3.5 shrink-0" />
                    {testMessage}
                  </span>
                )}
              </div>
            </div>
          </div>
        )}

        {/* Step 2: Overlay & Notifications */}
        {step === 2 && (
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <Layers className="w-4 h-4 text-accent shrink-0" />
              <h4 className="font-mono text-xs font-bold text-white uppercase">
                2. ALLOW OVERLAY &amp; NOTIFICATIONS
              </h4>
            </div>
            <p className="text-xs text-slate-400 leading-relaxed">
              Required for the floating edge download tab and completion alerts while downloading in the background.
            </p>

            <div className="space-y-2">
              <div className="p-3 rounded-lg bg-slate-950 border border-slate-800 flex items-center justify-between gap-2">
                <div className="flex items-center gap-2.5 min-w-0">
                  <span
                    className={`w-2.5 h-2.5 rounded-full shrink-0 ${
                      checks.overlayGranted ? 'led-emerald' : 'led-amber'
                    }`}
                  />
                  <div className="min-w-0">
                    <div className="font-mono text-xs font-bold text-white truncate">
                      Display over other apps
                    </div>
                    <div className="font-mono text-[10px] text-slate-400">
                      {checks.overlayGranted ? 'GRANTED' : 'REQUIRED FOR FLOATING TAB'}
                    </div>
                  </div>
                </div>
                {!checks.overlayGranted && (
                  <button
                    onClick={handleFixOverlay}
                    className="machine-btn px-3 py-1 rounded text-[11px] font-mono font-bold text-accent-soft shrink-0 whitespace-nowrap"
                  >
                    FIX
                  </button>
                )}
              </div>

              <div className="p-3 rounded-lg bg-slate-950 border border-slate-800 flex items-center justify-between gap-2">
                <div className="flex items-center gap-2.5 min-w-0">
                  <span
                    className={`w-2.5 h-2.5 rounded-full shrink-0 ${
                      checks.notificationsGranted ? 'led-emerald' : 'led-amber'
                    }`}
                  />
                  <div className="min-w-0">
                    <div className="font-mono text-xs font-bold text-white truncate">
                      Notifications
                    </div>
                    <div className="font-mono text-[10px] text-slate-400">
                      {checks.notificationsGranted ? 'GRANTED' : 'DOWNLOAD FINISH & FAIL ALERTS'}
                    </div>
                  </div>
                </div>
                {!checks.notificationsGranted && (
                  <button
                    onClick={handleFixNotifications}
                    className="machine-btn px-3 py-1 rounded text-[11px] font-mono font-bold text-accent-soft shrink-0 whitespace-nowrap"
                  >
                    FIX
                  </button>
                )}
              </div>
            </div>
          </div>
        )}

        {/* Step 3: Media Access */}
        {step === 3 && (
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <HardDrive className="w-4 h-4 text-accent shrink-0" />
              <h4 className="font-mono text-xs font-bold text-white uppercase">
                3. ALLOW MEDIA ACCESS
              </h4>
            </div>
            <p className="text-xs text-slate-400 leading-relaxed">
              Allows OmniDrop to list and play videos, photos, and audio saved inside your gallery folders.
            </p>

            <div className="p-3.5 rounded-lg bg-slate-950 border border-slate-800 flex items-center justify-between gap-2">
              <div className="flex items-center gap-2.5 min-w-0">
                <span
                  className={`w-2.5 h-2.5 rounded-full shrink-0 ${
                    checks.mediaGranted ? 'led-emerald' : 'led-amber'
                  }`}
                />
                <div className="min-w-0">
                  <div className="font-mono text-xs font-bold text-white truncate">
                    Videos &amp; Photos Access
                  </div>
                  <div className="font-mono text-[10px] text-slate-400">
                    {checks.mediaGranted ? 'GRANTED' : 'REQUIRED FOR GALLERY LIBRARY'}
                  </div>
                </div>
              </div>
              {!checks.mediaGranted && (
                <button
                  onClick={handleFixMedia}
                  className="machine-btn px-3 py-1 rounded text-[11px] font-mono font-bold text-accent-soft shrink-0 whitespace-nowrap"
                >
                  FIX
                </button>
              )}
            </div>
          </div>
        )}

        {/* Step 4: Battery Unrestricted */}
        {step === 4 && (
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <BatteryCharging className="w-4 h-4 text-accent shrink-0" />
              <h4 className="font-mono text-xs font-bold text-white uppercase">
                4. BATTERY UNRESTRICTED
              </h4>
            </div>
            <p className="text-xs text-slate-400 leading-relaxed">
              Keeps background downloads and the floating edge tab keep-alive running without Android killing them.
            </p>

            <div className="p-3.5 rounded-lg bg-slate-950 border border-slate-800 flex items-center justify-between gap-2">
              <div className="flex items-center gap-2.5 min-w-0">
                <span
                  className={`w-2.5 h-2.5 rounded-full shrink-0 ${
                    checks.batteryUnrestricted ? 'led-emerald' : 'led-amber'
                  }`}
                />
                <div className="min-w-0">
                  <div className="font-mono text-xs font-bold text-white truncate">
                    Battery Optimization
                  </div>
                  <div className="font-mono text-[10px] text-slate-400">
                    {checks.batteryUnrestricted ? 'UNRESTRICTED' : 'RESTRICTED BY SYSTEM'}
                  </div>
                </div>
              </div>
              {!checks.batteryUnrestricted && (
                <button
                  onClick={handleFixBattery}
                  className="machine-btn px-3 py-1 rounded text-[11px] font-mono font-bold text-accent-soft shrink-0 whitespace-nowrap"
                >
                  FIX
                </button>
              )}
            </div>

            <div className="p-3 rounded-lg bg-amber-950/30 border border-amber-500/40 font-mono text-[11px] text-amber-200 leading-relaxed">
              Choose OmniDrop and set it to Unrestricted / Don&apos;t optimize.
            </div>
          </div>
        )}

        {/* Footer navigation */}
        <div className="flex items-center justify-between gap-2 pt-2 border-t border-slate-800">
          {step > 1 ? (
            <button
              onClick={() => setStep(step - 1)}
              className="machine-btn px-3 py-2 rounded-lg font-mono text-xs text-slate-300 flex items-center gap-1 whitespace-nowrap"
            >
              <ChevronLeft className="w-3.5 h-3.5" />
              <span>BACK</span>
            </button>
          ) : (
            <button
              onClick={markCompletedAndClose}
              className="machine-btn px-3 py-2 rounded-lg font-mono text-xs text-slate-400 whitespace-nowrap"
            >
              SKIP SETUP
            </button>
          )}

          <button
            onClick={handleNext}
            className="machine-btn-primary px-4 py-2 rounded-lg font-mono text-xs font-bold flex items-center gap-1.5 whitespace-nowrap"
          >
            <span>{step < 4 ? 'NEXT STEP' : 'FINISH'}</span>
            {step < 4 && <ChevronRight className="w-3.5 h-3.5" />}
          </button>
        </div>
      </div>
    </div>
  );
};
