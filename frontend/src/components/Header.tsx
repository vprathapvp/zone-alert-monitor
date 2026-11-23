import { useEffect, useState } from 'react';
import { connection } from '../state/connection';
import { positions } from '../state/positions';
import { useStore } from '../state/store';

/** Who is signed in, and whether live updates are flowing. */
export function Header({ username, role, onLogout }: { username: string; role: string; onLogout: () => void }) {
  useStore(connection);
  const [, tick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);

  const secs = Math.round((Date.now() - connection.since) / 1000);
  const label =
    connection.status === 'live'
      ? 'Live'
      : connection.status === 'connecting'
        ? 'Connecting…'
        : `Live updates interrupted for ${secs} s, reconnecting…`;

  return (
    <header className="topbar">
      <h1>Zone console</h1>
      <div className={`link link-${connection.status}`} role="status" aria-live="polite">
        <span className="dot" aria-hidden="true" />
        {label}
        {connection.status === 'live' && <span className="muted"> · {positions.byId.size} assets</span>}
      </div>
      <div className="user">
        Signed in as <strong>{username}</strong> ({role.toLowerCase()})
        <button type="button" onClick={onLogout}>Sign out</button>
      </div>
    </header>
  );
}
