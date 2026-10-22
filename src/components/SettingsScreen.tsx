import React, { useState } from 'react';
import {
  Settings,
  Server,
  Key,
  Sliders,
  CheckCircle2,
  AlertCircle,
  Loader2,
  Radio,
  Bell,
  Layers,
  Sparkles,
  ExternalLink,
  Shield,
  Smartphone,
} from 'lucide-react';
import { AppSettings } from '../types';
import { checkServerHealth, cleanBackendUrl } from '../services/api';
import { VideoDownloader } from '../plugins/videoDownloader';

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
  const [backendUrl, setBackendUrl] = useState(settings.backendUrl || '');
  const [apiKey, setApiKey] = useState(settings.apiKey || '');
  const [defaultQuality, setDefaultQuality] = useState(settings.defaultQuality || 'best');
  const [autoDetectClipboard, setAutoDetectClipboard] = useState(settings.autoDetectClipboard ?? true);

  const [isBubbleActive, setIsBubbleActive] = useState(settings.floatingBubbleEnabled || false);
  const [isPersistentActive, setIsPersistentActive] = useState(settings.persistentNotificationEnabled || false);

  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'success' | 'error'>('idle');
  const [testResult, setTestResult] = useState<string>('');
  const [savedNotification, setSavedNotification] = useState(false);

  const handleSave = () => {
    const updated: AppSettings = {
      ...settings,
      backendUrl: backendUrl.trim(),
      apiKey: apiKey.trim(),
      defaultQuality,
      autoDetectClipboard,
      floatingBubbleEnabled: isBubbleActive,
      persistentNotificationEnabled: isPersistentActive,
    };
    onUpdateSettings(updated);
    VideoDownloader.saveNativeSettings({
      backendUrl: backendUrl.trim(),
      apiKey: apiKey.trim(),
      defaultQuality,
      autoDownload: true,
    }).catch(() => {});
    setSavedNotification(true);
    setTimeout(() => setSavedNotification(false), 2500);
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
      setTestResult(`Connected! Response time: ${res.latencyMs}ms`);
    } else {
      setTestStatus('error');
      setTestResult(`Failed: ${res.error || 'Server unreachable'}`);
    }
  };

  const handleToggleBubble = async () => {
    try {
      if (!isBubbleActive) {
        // Turning ON
        const perm = await VideoDownloader.hasOverlayPermission();
        if (!perm.granted) {
          await VideoDownloader.requestOverlayPermission();
          alert('Please grant "Display over other apps" permission in Android settings, then toggle this switch again.');
          return;
        }
        await VideoDownloader.startBubble();
        setIsBubbleActive(true);
      } else {
        // Turning OFF
        await VideoDownloader.stopBubble();
        setIsBubbleActive(false);
      }
    } catch (e: any) {
      alert(`Native feature notice: ${e?.message || 'Available when installed as Android APK.'}`);
      // Still toggle local state for preview
      setIsBubbleActive(!isBubbleActive);
    }
  };

  const handleTogglePersistentNotification = async () => {
    try {
      if (!isPersistentActive) {
        await VideoDownloader.showPersistentNotification({
          title: 'OmniDrop Quick Grab',
          message: 'Tap "Download from clipboard" anytime',
        });
        setIsPersistentActive(true);
      } else {
        await VideoDownloader.dismissPersistentNotification();
        setIsPersistentActive(false);
      }
    } catch {
      setIsPersistentActive(!isPersistentActive);
    }
  };

  return (
    <div className="space-y-5 pb-28">
      <div className="flex items-center justify-between pt-1">
        <div>
          <h2 className="text-base font-bold text-white flex items-center gap-2">
            <Settings className="w-4 h-4 text-indigo-400" />
            Application Settings
          </h2>
          <p className="text-xs text-slate-400">Configure backend API and native integrations</p>
        </div>

        <button
          onClick={handleSave}
          className="px-4 py-1.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 font-semibold text-xs text-white shadow-lg shadow-indigo-600/30 transition active:scale-95"
        >
          Save
        </button>
      </div>

      {savedNotification && (
        <div className="p-3 rounded-xl bg-emerald-950/70 border border-emerald-800/80 flex items-center gap-2 text-xs text-emerald-200 animate-fade-in">
          <CheckCircle2 className="w-4 h-4 text-emerald-400" />
          Settings saved successfully!
        </div>
      )}

      {/* Backend API Configuration */}
      <div className="rounded-2xl bg-slate-900 border border-slate-800 p-4 space-y-4">
        <div className="flex items-center gap-2 pb-2 border-b border-slate-800">
          <Server className="w-4 h-4 text-indigo-400" />
          <h3 className="text-xs font-semibold text-white uppercase tracking-wider">
            Backend API Configuration
          </h3>
        </div>

        <div>
          <label className="block text-xs font-medium text-slate-300 mb-1">
            Backend URL
          </label>
          <input
            type="text"
            value={backendUrl}
            onChange={(e) => setBackendUrl(e.target.value)}
            placeholder="e.g. https://omnidrop-api.onrender.com (or leave empty for local)"
            className="w-full rounded-xl bg-slate-950 border border-slate-800 px-3 py-2.5 text-xs text-slate-200 placeholder:text-slate-600 focus:outline-none focus:border-indigo-500 font-mono transition"
          />
          <p className="text-[11px] text-slate-500 mt-1">
            Leave blank to use this app&apos;s built-in local backend, or paste your deployed Render URL.
          </p>
        </div>

        <div>
          <label className="block text-xs font-medium text-slate-300 mb-1">
            API Key (Optional)
          </label>
          <div className="relative">
            <input
              type="password"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              placeholder="x-api-key header value"
              className="w-full rounded-xl bg-slate-950 border border-slate-800 px-3 py-2.5 text-xs text-slate-200 placeholder:text-slate-600 focus:outline-none focus:border-indigo-500 font-mono transition"
            />
          </div>
          <p className="text-[11px] text-slate-500 mt-1">
            Required only if you set <code className="text-slate-400">APP_API_KEY</code> on your backend server.
          </p>
        </div>

        {/* Test Connection Button */}
        <div className="pt-1 flex items-center justify-between">
          <button
            type="button"
            onClick={handleTestConnection}
            disabled={testStatus === 'testing'}
            className="px-3 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-xs font-medium text-slate-200 flex items-center gap-2 transition active:scale-95 disabled:opacity-50"
          >
            {testStatus === 'testing' ? (
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
            ) : (
              <Radio className="w-3.5 h-3.5 text-indigo-400" />
            )}
            Test Server Connection
          </button>

          {testStatus === 'success' && (
            <span className="text-xs text-emerald-400 font-medium flex items-center gap-1">
              <CheckCircle2 className="w-3.5 h-3.5" />
              {testResult}
            </span>
          )}

          {testStatus === 'error' && (
            <span className="text-xs text-rose-400 font-medium flex items-center gap-1">
              <AlertCircle className="w-3.5 h-3.5" />
              {testResult}
            </span>
          )}
        </div>
      </div>

      {/* Preferences */}
      <div className="rounded-2xl bg-slate-900 border border-slate-800 p-4 space-y-4">
        <div className="flex items-center gap-2 pb-2 border-b border-slate-800">
          <Sliders className="w-4 h-4 text-purple-400" />
          <h3 className="text-xs font-semibold text-white uppercase tracking-wider">
            Download Preferences
          </h3>
        </div>

        <div>
          <label className="block text-xs font-medium text-slate-300 mb-1.5">
            Default Preferred Quality
          </label>
          <div className="grid grid-cols-4 gap-2">
            {['best', '1080p', '720p', 'audio'].map((q) => (
              <button
                key={q}
                type="button"
                onClick={() => setDefaultQuality(q)}
                className={`py-2 px-2 rounded-xl border text-xs font-medium uppercase transition ${
                  defaultQuality === q
                    ? 'bg-purple-600 border-purple-500 text-white'
                    : 'bg-slate-950 border-slate-800 text-slate-400 hover:border-slate-700'
                }`}
              >
                {q === 'audio' ? 'MP3' : q}
              </button>
            ))}
          </div>
        </div>

        <div className="pt-2 flex items-center justify-between">
          <div>
            <h4 className="text-xs font-semibold text-white">Auto-Detect Clipboard</h4>
            <p className="text-[11px] text-slate-400">Offer to download copied links on app focus</p>
          </div>
          <button
            type="button"
            onClick={() => setAutoDetectClipboard(!autoDetectClipboard)}
            className={`w-11 h-6 rounded-full transition-colors relative ${
              autoDetectClipboard ? 'bg-indigo-600' : 'bg-slate-800'
            }`}
          >
            <div
              className={`w-4 h-4 rounded-full bg-white absolute top-1 transition-transform ${
                autoDetectClipboard ? 'left-6' : 'left-1'
              }`}
            />
          </button>
        </div>
      </div>

      {/* Native Android Features */}
      <div className="rounded-2xl bg-slate-900 border border-slate-800 p-4 space-y-4">
        <div className="flex items-center justify-between pb-2 border-b border-slate-800">
          <div className="flex items-center gap-2">
            <Smartphone className="w-4 h-4 text-emerald-400" />
            <h3 className="text-xs font-semibold text-white uppercase tracking-wider">
              Native Android Integrations
            </h3>
          </div>
          <span className="text-[10px] px-2 py-0.5 rounded bg-emerald-950/60 text-emerald-400 border border-emerald-800/60 font-semibold">
            Capacitor Ready
          </span>
        </div>

        {/* Floating Bubble */}
        <div className="flex items-center justify-between">
          <div className="space-y-0.5">
            <h4 className="text-xs font-semibold text-white flex items-center gap-1.5">
              <Layers className="w-3.5 h-3.5 text-indigo-400" />
              Floating Quick-Download Bubble
            </h4>
            <p className="text-[11px] text-slate-400 max-w-[240px]">
              Draws a floating button over other apps. Tap to instantly grab link from clipboard.
            </p>
          </div>
          <button
            type="button"
            onClick={handleToggleBubble}
            className={`w-11 h-6 rounded-full transition-colors relative ${
              isBubbleActive ? 'bg-indigo-600' : 'bg-slate-800'
            }`}
          >
            <div
              className={`w-4 h-4 rounded-full bg-white absolute top-1 transition-transform ${
                isBubbleActive ? 'left-6' : 'left-1'
              }`}
            />
          </button>
        </div>

        {/* Persistent Notification */}
        <div className="flex items-center justify-between pt-2 border-t border-slate-800/80">
          <div className="space-y-0.5">
            <h4 className="text-xs font-semibold text-white flex items-center gap-1.5">
              <Bell className="w-3.5 h-3.5 text-purple-400" />
              Persistent Quick-Grab Notification
            </h4>
            <p className="text-[11px] text-slate-400 max-w-[240px]">
              Notification with a &ldquo;Download from clipboard&rdquo; button.
            </p>
          </div>
          <button
            type="button"
            onClick={handleTogglePersistentNotification}
            className={`w-11 h-6 rounded-full transition-colors relative ${
              isPersistentActive ? 'bg-purple-600' : 'bg-slate-800'
            }`}
          >
            <div
              className={`w-4 h-4 rounded-full bg-white absolute top-1 transition-transform ${
                isPersistentActive ? 'left-6' : 'left-1'
              }`}
            />
          </button>
        </div>
      </div>

      {/* Deployment & Info Card */}
      <div className="p-4 rounded-2xl bg-gradient-to-br from-slate-900 to-indigo-950/40 border border-slate-800 space-y-2.5 text-xs text-slate-300">
        <h4 className="font-semibold text-white flex items-center gap-1.5">
          <Shield className="w-4 h-4 text-indigo-400" />
          Deployment Architecture
        </h4>
        <p className="text-[11px] text-slate-400 leading-relaxed">
          OmniDrop is built as a unified repository with:
        </p>
        <ul className="text-[11px] text-slate-400 space-y-1 list-disc list-inside">
          <li><strong>/backend</strong>: Python 3.11 + FastAPI + yt-dlp + FFmpeg (Render Docker service).</li>
          <li><strong>/app</strong>: React + Vite + PWA + Capacitor.</li>
          <li><strong>.github/workflows</strong>: CI/CD for automated GitHub Pages and Debug APK builds.</li>
        </ul>
      </div>
    </div>
  );
};
