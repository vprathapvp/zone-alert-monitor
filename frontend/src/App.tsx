import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, setToken, setUnauthenticatedHandler } from './api/client';
import { LiveClient } from './api/live';
import type { LoginResponse, TrackPoint } from './api/types';
import { AlertPanel } from './components/AlertPanel';
import { Announcer } from './components/Announcer';
import { AssetDetail } from './components/AssetDetail';
import { Header } from './components/Header';
import { Login } from './components/Login';
import { ZoneOccupancy } from './components/ZoneOccupancy';
import { MapCanvas } from './map/MapCanvas';
import { prepareZone, type PreparedZone } from './map/geo';
import { alerts } from './state/alerts';
import { positions } from './state/positions';

const SESSION_KEY = 'console.session';
type Tab = 'alerts' | 'zones' | 'asset';

function loadSession(): LoginResponse | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    return raw ? (JSON.parse(raw) as LoginResponse) : null;
  } catch {
    return null;
  }
}

export function App() {
  const [session, setSession] = useState<LoginResponse | null>(() => {
    const s = loadSession();
    if (s) setToken(s.accessToken);
    return s;
  });

  const logout = useCallback(() => {
    sessionStorage.removeItem(SESSION_KEY);
    setToken(null);
    positions.clear();
    alerts.clear();
    setSession(null);
  }, []);

  useEffect(() => setUnauthenticatedHandler(logout), [logout]);

  const login = (r: LoginResponse) => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(r));
    setToken(r.accessToken);
    setSession(r);
  };

  if (!session) return <Login onLogin={login} />;
  return <Console session={session} onLogout={logout} />;
}

function Console({ session, onLogout }: { session: LoginResponse; onLogout: () => void }) {
  const [zones, setZones] = useState<PreparedZone[]>([]);
  const [tab, setTab] = useState<Tab>('alerts');
  const [selected, setSelected] = useState<string | null>(null);
  const [track, setTrack] = useState<TrackPoint[]>([]);
  const [trackError, setTrackError] = useState<string | null>(null);

  useEffect(() => {
    const live = new LiveClient();
    live.start();
    let cancelled = false;
    const loadZones = async () => {
      for (let attempt = 0; !cancelled; attempt++) {
        try {
          const res = await api.zones();
          if (!cancelled) setZones(res.items.map(prepareZone));
          return;
        } catch {
          await new Promise((r) => setTimeout(r, Math.min(5000, 500 * 2 ** attempt)));
        }
      }
    };
    void loadZones();
    return () => {
      cancelled = true;
      live.stop();
    };
  }, []);

  // Track of the selected asset: the last 15 minutes of event time, refreshed every 5 seconds.
  useEffect(() => {
    if (!selected) {
      setTrack([]);
      return;
    }
    let cancelled = false;
    const load = async () => {
      const p = positions.byId.get(selected);
      const from = p ? new Date(Date.parse(p.observedAt) - 15 * 60_000).toISOString() : null;
      try {
        const res = await api.track(selected, from, 2000);
        if (!cancelled) {
          setTrack(res.items);
          setTrackError(null);
        }
      } catch {
        if (!cancelled) setTrackError('Could not load the track.');
      }
    };
    void load();
    const t = setInterval(load, 5000);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, [selected]);

  const zoneNames = useMemo(() => new Map(zones.map((z) => [z.zoneId, z.name])), [zones]);
  const zoneName = useCallback((id: string) => zoneNames.get(id) ?? id, [zoneNames]);

  const select = useCallback((id: string | null) => {
    setSelected(id);
    if (id) setTab('asset');
  }, []);

  const tabs: { id: Tab; label: string }[] = [
    { id: 'alerts', label: 'Alerts' },
    { id: 'zones', label: 'Zones' },
    { id: 'asset', label: selected ? `Asset ${selected}` : 'Asset' },
  ];

  return (
    <div className="app">
      <a href="#sidebar" className="skip">Skip to alerts</a>
      <Header username={session.user.username} role={session.user.role} onLogout={onLogout} />
      <Announcer zoneName={zoneName} />
      <div className="layout">
        <MapCanvas zones={zones} selectedAssetId={selected} track={track} onSelect={select} />
        <aside id="sidebar" className="sidebar" aria-label="Operator panels">
          <nav className="tabs" aria-label="Panels">
            {tabs.map((t) => (
              <button key={t.id} type="button" aria-pressed={tab === t.id} onClick={() => setTab(t.id)}
                disabled={t.id === 'asset' && !selected}>
                {t.label}
              </button>
            ))}
          </nav>
          {tab === 'alerts' && (
            <AlertPanel username={session.user.username} isSupervisor={session.user.role === 'SUPERVISOR'}
              zoneName={zoneName} onSelectAsset={select} />
          )}
          {tab === 'zones' && <ZoneOccupancy zones={zones} onSelectAsset={select} />}
          {tab === 'asset' && selected && (
            <AssetDetail assetId={selected} track={track} trackError={trackError}
              onClose={() => { setSelected(null); setTab('alerts'); }} />
          )}
        </aside>
      </div>
    </div>
  );
}
