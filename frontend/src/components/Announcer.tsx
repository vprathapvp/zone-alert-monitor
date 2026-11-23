import { useEffect, useRef, useState } from 'react';
import type { Alert } from '../api/types';
import { alerts } from '../state/alerts';

const MIN_GAP_MS = 10_000;

/**
 * Screen-reader announcements for new alerts that do not flood: at most one message every
 * 10 seconds. Alerts that arrive in between are summarised ("4 new alerts …"). Can be muted.
 */
export function Announcer({ zoneName }: { zoneName: (id: string) => string }) {
  const [message, setMessage] = useState('');
  const [muted, setMuted] = useState(false);
  const pending = useRef<Alert[]>([]);
  const lastAt = useRef(0);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const mutedRef = useRef(muted);
  mutedRef.current = muted;

  useEffect(() => {
    const flush = () => {
      timer.current = null;
      const batch = pending.current;
      pending.current = [];
      if (batch.length === 0 || mutedRef.current) return;
      lastAt.current = Date.now();
      const first = batch[0]!;
      setMessage(
        batch.length === 1
          ? `New alert: ${first.assetId} entered ${zoneName(first.zoneId)}.`
          : `${batch.length} new alerts, latest ${first.assetId} entered ${zoneName(first.zoneId)}.`,
      );
    };
    const off = alerts.onNewAlert((a) => {
      pending.current.unshift(a);
      if (timer.current) return;
      const wait = Math.max(0, lastAt.current + MIN_GAP_MS - Date.now());
      timer.current = setTimeout(flush, wait);
    });
    return () => {
      off();
      if (timer.current) clearTimeout(timer.current);
    };
  }, [zoneName]);

  return (
    <div className="announcer">
      <div className="sr-only" role="status" aria-live="polite" aria-atomic="true">{message}</div>
      <label className="mute">
        <input type="checkbox" checked={muted} onChange={(e) => setMuted(e.target.checked)} /> Mute alert announcements
      </label>
    </div>
  );
}
