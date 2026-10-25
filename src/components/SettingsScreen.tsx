import React, { useState, useEffect } from 'react';
import {
  Settings,
  Server,
  Sliders,
  CheckCircle2,
  AlertCircle,
  Loader2,
  Layers,
  HardDrive,
  Trash2,
  Shield,
  RefreshCw,
  ExternalLink,
  Cpu,
} from 'lucide-react';
import { AppSettings } from '../types';
import { checkServerHealth } from '../services/api';
import { VideoDownloader } from '../plugins/videoDownloader';
import { Capacitor } from '@capacitor/core';

interface SettingsScreenProps {
  settings: AppSettings;
  onUpdateSettings: (newSettings: AppSettings) => void;
  onRefreshHealth: () => void;
}

export const SettingsScreen: React.FC<SettingsScreenProps> = ({
  settings,
  onUpdateSettings,
  onRefreshHealth,
}) => {
  const isNative = Capacitor.isNativePlatform();

  // Settings state
  const [backendUrl, setBackendUrl] = useState(settings.backendUrl || '');
  const [apiKey, setApiKey] = useState(settings.apiKey || '');
  const [defaultQuality, setDefaultQuality] = useState(settings.defaultQuality || 'best');
  const [autoDetectClipboard, setAutoDetectClipboard] = useState(settings.autoDetectClipboard ?? true);
  const [autoDownloadOnPaste, setAutoDownloadOnPaste] = useState(settings.autoDownloadOnPaste ?? false);
  const [autoPlayNext, setAutoPlayNext] = useState(settings.autoPlayNext ?? true);
  const [repeatMode, setRepeatMode] = useState<'off' | 'one' | 'all'>(settings.repeatMode || 'off');

  // Floating tab settings
  const [isBubbleActive, setIsBubbleActive] = useState(settings.floatingBubbleEnabled || false);
  const [floatingSide, setFloatingSide] = useState<'left' | 'right'>(settings.floatingSide || 'right');
  const [floatingShowMs, setFloatingShowMs] = useState(settings.floatingShowMs ?? true);
  const [floatingAutoClear, setFloatingAutoClear] = useState(settings.floatingAutoClear ?? true);
  const [floatingVibrate, setFloatingVibrate] = useState(settings.floatingVibrate ?? true);
  const [hasOverlayPerm, setHasOverlayPerm] = useState<boolean>(false);

  // Connection test state
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'success' | 'error'>('idle');
  const [testResult, setTestResult] = useState<string>('');
  const [isSaved, setIsSaved] = useState(false);

  // Storage calculation state
  const [librarySizeMb, setLibrarySizeMb] = useState<number>(0);
  const [libraryCount, setLibraryCount] = useState<number>(0);
  const [isClearingCache, setIsClearingCache] = useState(false);
  const [cacheClearMsg, setCacheClearMsg] = useState<string | null>(null);

  // Check overlay permission
  const checkOverlayPermission = async () => {
    try {
      const res = await VideoDownloader.hasOverlayPermission();
      setHasOverlayPerm(res.granted);
    } catch {
      setHasOverlayPerm(false);
    }
  };

  // Check storage size
  const loadStorageStats = async () => {
    try {
      const res = await VideoDownloader.getVideoLibrary();
      if (res && res.videos) {
        const totalBytes = res.videos.reduce((acc, v) => acc + (v.size || 0), 0);
        setLibrarySizeMb(Math.round(totalBytes / (1024 * 1024)));
        setLibraryCount(res.videos.length);
      }
    } catch {}
  };

  useEffect(() => {
    checkOverlayPermission();
    loadStorageStats();
  }, []);

  const handleSave = () => {
    const updated: AppSettings = {
      ...settings,
      backendUrl: backendUrl.trim(),
      apiKey: apiKey.trim(),
      defaultQuality,
      autoDetectClipboard,
      autoDownloadOnPaste,
      autoPlayNext,
      repeatMode,
      floatingBubbleEnabled: isBubbleActive,
      floatingSide,
      floatingShowMs,
      floatingAutoClear,
      floatingVibrate,
    };
    onUpdateSettings(updated);

    VideoDownloader.saveNativeSettings({
      backendUrl: backendUrl.trim(),
      apiKey: apiKey.trim(),
      defaultQuality,
      autoDownload: true,
      autoDownloadOnPaste,
      autoPlayNext,
      repeatMode,
      floatingSide,
      floatingShowMs,
      floatingAutoClear,
      floatingVibrate,
    }).catch(() => {});

    setIsSaved(true);
    setTimeout(() => setIsSaved(false), 2200);
    onRefreshHealth();
  };

  const handleTestConnection = async () => {
    setTestStatus('testing');
    setTestResult('');
    const tempSettings: AppSettings = {
      ...settings,
      backendUrl: backendUrl.trim(),
      apiKey: apiKey.trim(),
    };
    const res = await checkServerHealth(tempSettings);
    if (res.ok) {
      setTestStatus('success');
      setTestResult(`ONLINE • Response time: ${res.latencyMs}ms`);
    } else {
      setTestStatus('error');
      setTestResult(`FAILED • ${res.error || 'Server unreachable'}`);
    }
  };

  const handleToggleBubble = async () => {
    try {
      if (!isBubbleActive) {
        const perm = await VideoDownloader.hasOverlayPermission();
        if (!perm.granted) {
          await VideoDownloader.requestOverlayPermission();
          alert('Please grant "Display over other apps" permission in system settings, then toggle again.');
          return;
        }
        await VideoDownloader.startBubble();
        setIsBubbleActive(true);
      } else {
        await VideoDownloader.stopBubble();
        setIsBubbleActive(false);
      }
    } catch (e: any) {
      setIsBubbleActive(!isBubbleActive);
    }
  };

  const handleGrantOverlay = async () => {
    try {
      await VideoDownloader.requestOverlayPermission();
      setTimeout(checkOverlayPermission, 1500);
    } catch {}
  };

  const handleClearMetadataCache = async () => {
    if (confirm('Clear metadata and thumbnail cache? Downloaded video files will not be deleted.')) {
      setIsClearingCache(true);
      setCacheClearMsg(null);
      try {
        await VideoDownloader.clearMetadataCache();
        try {
          localStorage.removeItem('omnidrop_metadata_cache');
        } catch {}
        setCacheClearMsg('Metadata cache cleared successfully!');
        setTimeout(() => setCacheClearMsg(null), 3000);
      } catch (e: any) {
        setCacheClearMsg(`Notice: ${e?.message || 'Cache cleared'}`);
      } finally {
        setIsClearingCache(false);
      }
    }
  };

  // Reusable mechanical toggle switch with LED
  const renderMechanicalToggle = (
    label: string,
    sublabel: string,
    checked: boolean,
    onChange: () => void
  ) => {
    return (
      <div className="flex items-center justify-between py-2 border-b border-slate-800/60 last:border-0">
        <div className="min-w-0 pr-3">
          <div className="font-mono text-xs font-bold text-white uppercase">{label}</div>
          <div className="text-[11px] text-slate-400 font-sans mt-0.5">{sublabel}</div>
        </div>

        <button
          onClick={onChange}
          className={`machine-btn relative w-12 h-6.5 rounded-full p-0.5 flex items-center transition-all duration-200 shrink-0 ${
            checked ? 'border-cyan-400 bg-cyan-950/60' : 'border-slate-700 bg-slate-950'
          }`}
        >
          <div
            className={`w-5 h-5 rounded-full flex items-center justify-center transition-all duration-200 transform ${
              checked
                ? 'translate-x-5.5 bg-cyan-400 led-cyan text-slate-950 shadow-[0_0_8px_rgba(0,240,255,0.8)]'
                : 'translate-x-0 bg-slate-700 text-slate-400'
            }`}
          >
            <div className={`w-1.5 h-1.5 rounded-full ${checked ? 'bg-slate-950' : 'bg-slate-400'}`} />
          </div>
        </button>
      </div>
    );
  };

  return (
    <div className="space-y-4 pb-28">
      {/* Top Header & Mechanical Save Key */}
      <div className="flex items-center justify-between pt-1">
        <div>
          <h2 className="text-xs font-mono font-bold text-cyan-400 uppercase tracking-widest flex items-center gap-2">
            <span className="w-2 h-2 rounded-full led-cyan" />
            SETTINGS // CONTROL
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">Hardware preferences & server configuration</p>
        </div>

        {/* Mechanical Bevelled Save Key */}
        <button
          onClick={handleSave}
          className={`machine-btn px-4 py-1.5 rounded-lg font-mono text-xs font-bold uppercase tracking-wider transition-all duration-200 ${
            isSaved
              ? 'machine-btn-primary bg-emerald-500 border-emerald-400 text-slate-950 shadow-[0_0_12px_rgba(16,185,129,0.8)]'
              : 'text-cyan-300'
          }`}
        >
          {isSaved ? 'SAVED' : 'SAVE'}
        </button>
      </div>

      {/* 1. SERVER // CONFIG */}
      <div className="machine-surface rounded-xl p-4 space-y-3">
        <div className="flex items-center gap-2 pb-2 border-b border-cyan-500/20">
          <Server className="w-4 h-4 text-cyan-400" />
          <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
            SERVER // CONFIG
          </h3>
        </div>

        <div className="space-y-2.5">
          <div>
            <label className="block text-[10px] font-mono text-slate-400 mb-1 uppercase">
              BACKEND URL (RENDER / CUSTOM ENDPOINT)
            </label>
            <input
              type="url"
              value={backendUrl}
              onChange={(e) => setBackendUrl(e.target.value)}
              placeholder="https://your-omnidrop.onrender.com"
              className="w-full rounded-lg bg-slate-950 border border-slate-800 focus:border-cyan-400 px-3 py-2 text-xs font-mono text-slate-200 placeholder:text-slate-600 outline-none"
            />
          </div>

          <div>
            <label className="block text-[10px] font-mono text-slate-400 mb-1 uppercase">
              API KEY (OPTIONAL HEADER X-API-KEY)
            </label>
            <input
              type="password"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              placeholder="••••••••••••"
              className="w-full rounded-lg bg-slate-950 border border-slate-800 focus:border-cyan-400 px-3 py-2 text-xs font-mono text-slate-200 placeholder:text-slate-600 outline-none"
            />
          </div>

          {/* Test Connection Button with Live LED */}
          <div className="pt-1 flex items-center gap-2">
            <button
              onClick={handleTestConnection}
              disabled={testStatus === 'testing'}
              className="machine-btn px-3 py-1.5 rounded-lg text-xs font-mono text-cyan-300 flex items-center gap-1.5 disabled:opacity-50"
            >
              {testStatus === 'testing' ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <RefreshCw className="w-3.5 h-3.5" />
              )}
              <span>TEST SERVER CONNECTION</span>
            </button>

            {testStatus !== 'idle' && (
              <div
                className={`flex-1 p-1.5 rounded-lg border font-mono text-[10px] flex items-center gap-1.5 truncate ${
                  testStatus === 'testing'
                    ? 'border-amber-500/40 text-amber-300 bg-amber-950/40'
                    : testStatus === 'success'
                    ? 'border-emerald-500/40 text-emerald-300 bg-emerald-950/40'
                    : 'border-rose-500/40 text-rose-300 bg-rose-950/40'
                }`}
              >
                <span
                  className={`w-1.5 h-1.5 rounded-full shrink-0 ${
                    testStatus === 'testing'
                      ? 'led-amber'
                      : testStatus === 'success'
                      ? 'led-emerald'
                      : 'led-rose'
                  }`}
                />
                <span className="truncate">{testResult || 'PINGING...'}</span>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* 2. DOWNLOADS // CONTROL */}
      <div className="machine-surface rounded-xl p-4 space-y-3">
        <div className="flex items-center gap-2 pb-2 border-b border-cyan-500/20">
          <Sliders className="w-4 h-4 text-cyan-400" />
          <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
            DOWNLOADS // CONTROL
          </h3>
        </div>

        {/* Default Quality Selector */}
        <div>
          <label className="block text-[10px] font-mono text-slate-400 mb-1.5 uppercase">
            DEFAULT QUALITY STREAM
          </label>
          <div className="grid grid-cols-4 gap-1 p-1 bg-slate-950 rounded-lg border border-slate-800">
            {[
              { id: 'best', label: 'BEST' },
              { id: '1080p', label: '1080P' },
              { id: '720p', label: '720P' },
              { id: 'audio', label: 'MP3' },
            ].map((q) => {
              const isSelected = defaultQuality === q.id;
              return (
                <button
                  key={q.id}
                  onClick={() => setDefaultQuality(q.id)}
                  className={`py-1.5 rounded text-center font-mono text-xs font-bold transition-all ${
                    isSelected
                      ? 'machine-btn-primary text-slate-950'
                      : 'text-slate-400 hover:text-slate-200'
                  }`}
                >
                  {q.label}
                </button>
              );
            })}
          </div>
        </div>

        {/* Toggles */}
        <div className="space-y-1">
          {renderMechanicalToggle(
            'AUTO-DOWNLOAD ON PASTE',
            'Begin stream fetch immediately when pasting supported link',
            autoDownloadOnPaste,
            () => setAutoDownloadOnPaste(!autoDownloadOnPaste)
          )}

          {renderMechanicalToggle(
            'AUTO-PLAY NEXT VIDEO',
            'Queue and launch sequential video when playback completes',
            autoPlayNext,
            () => setAutoPlayNext(!autoPlayNext)
          )}

          {/* Repeat Mode Segmented */}
          <div className="py-2">
            <div className="font-mono text-xs font-bold text-white uppercase mb-1">
              PLAYER REPEAT MODE
            </div>
            <div className="grid grid-cols-3 gap-1 p-1 bg-slate-950 rounded-lg border border-slate-800">
              {[
                { id: 'off', label: 'OFF' },
                { id: 'one', label: 'REPEAT ONE' },
                { id: 'all', label: 'REPEAT ALL' },
              ].map((m) => {
                const isSelected = repeatMode === m.id;
                return (
                  <button
                    key={m.id}
                    onClick={() => setRepeatMode(m.id as any)}
                    className={`py-1.5 rounded text-center font-mono text-xs font-bold transition-all ${
                      isSelected
                        ? 'machine-btn-primary text-slate-950'
                        : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {m.label}
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      </div>

      {/* 3. FLOATING TAB // SYSTEM OVERLAY */}
      <div className="machine-surface rounded-xl p-4 space-y-3">
        <div className="flex items-center gap-2 pb-2 border-b border-cyan-500/20">
          <Layers className="w-4 h-4 text-cyan-400" />
          <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
            FLOATING TAB // SYSTEM OVERLAY
          </h3>
        </div>

        <div className="space-y-2">
          {renderMechanicalToggle(
            'ENABLE FLOATING TAB',
            'Persistent edge drawer for 1-tap background downloads in social apps',
            isBubbleActive,
            handleToggleBubble
          )}

          {/* Side Selector */}
          <div className="py-2 border-b border-slate-800/60">
            <div className="font-mono text-xs font-bold text-white uppercase mb-1">
              EDGE DOCK POSITION
            </div>
            <div className="grid grid-cols-2 gap-1 p-1 bg-slate-950 rounded-lg border border-slate-800">
              {[
                { id: 'left', label: 'LEFT EDGE' },
                { id: 'right', label: 'RIGHT EDGE' },
              ].map((s) => {
                const isSelected = floatingSide === s.id;
                return (
                  <button
                    key={s.id}
                    onClick={() => setFloatingSide(s.id as any)}
                    className={`py-1.5 rounded text-center font-mono text-xs font-bold transition-all ${
                      isSelected
                        ? 'machine-btn-primary text-slate-950'
                        : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {s.label}
                  </button>
                );
              })}
            </div>
          </div>

          {renderMechanicalToggle(
            'SHOW LATENCY IN TAB',
            'Display live server ping counter on floating edge handle',
            floatingShowMs,
            () => setFloatingShowMs(!floatingShowMs)
          )}

          {renderMechanicalToggle(
            'AUTO-CLEAR AFTER DOWNLOAD',
            'Automatically clear link field and progress 4s after success',
            floatingAutoClear,
            () => setFloatingAutoClear(!floatingAutoClear)
          )}

          {renderMechanicalToggle(
            'VIBRATE WHEN FINISHED',
            'Short haptic pulse on download success or failure',
            floatingVibrate,
            () => setFloatingVibrate(!floatingVibrate)
          )}

          {/* Overlay Permission Status Bar */}
          <div className="p-3 rounded-lg bg-slate-950 border border-slate-800 flex items-center justify-between">
            <div className="min-w-0 pr-2">
              <div className="font-mono text-xs text-white uppercase font-bold flex items-center gap-1.5">
                <span
                  className={`w-2 h-2 rounded-full ${
                    hasOverlayPerm ? 'led-emerald' : 'led-amber'
                  }`}
                />
                <span>SYSTEM OVERLAY STATUS</span>
              </div>
              <div className="text-[11px] text-slate-400 mt-0.5">
                {hasOverlayPerm ? 'Granted • Ready for drawer service' : 'Required for edge tab display'}
              </div>
            </div>

            {!hasOverlayPerm && (
              <button
                onClick={handleGrantOverlay}
                className="machine-btn px-2.5 py-1 rounded text-[11px] font-mono text-cyan-300 font-bold shrink-0"
              >
                GRANT ACCESS
              </button>
            )}
          </div>
        </div>
      </div>

      {/* 4. STORAGE // CACHE CONTROL */}
      <div className="machine-surface rounded-xl p-4 space-y-3">
        <div className="flex items-center gap-2 pb-2 border-b border-cyan-500/20">
          <HardDrive className="w-4 h-4 text-cyan-400" />
          <h3 className="font-mono text-xs font-bold text-white uppercase tracking-wider">
            STORAGE // CACHE CONTROL
          </h3>
        </div>

        <div className="space-y-3">
          <div className="flex items-center justify-between p-3 rounded-lg bg-slate-950 border border-slate-800 font-mono text-xs">
            <span className="text-slate-400">TOTAL GALLERY USAGE:</span>
            <span className="text-cyan-300 font-bold">
              {librarySizeMb > 1024
                ? `${(librarySizeMb / 1024).toFixed(2)} GB`
                : `${librarySizeMb} MB`}{' '}
              <span className="text-slate-500">({libraryCount} videos)</span>
            </span>
          </div>

          <div className="flex items-center justify-between">
            <div className="min-w-0 pr-2">
              <div className="font-mono text-xs text-white uppercase font-bold">
                METADATA & THUMBNAILS
              </div>
              <div className="text-[11px] text-slate-400">
                Resets cached titles and base64 video previews
              </div>
            </div>

            <button
              onClick={handleClearMetadataCache}
              disabled={isClearingCache}
              className="machine-btn px-3 py-1.5 rounded-lg text-xs font-mono text-rose-300 flex items-center gap-1.5 shrink-0"
            >
              {isClearingCache ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Trash2 className="w-3.5 h-3.5 text-rose-400" />
              )}
              <span>CLEAR CACHE</span>
            </button>
          </div>

          {cacheClearMsg && (
            <div className="p-2.5 rounded-lg bg-emerald-950/50 border border-emerald-500/40 text-emerald-300 font-mono text-xs">
              {cacheClearMsg}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
