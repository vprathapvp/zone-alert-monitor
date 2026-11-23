import { useCallback, useMemo, useState } from 'react';
import { ApiError, api, getToken } from '../api/client';
import type { Alert } from '../api/types';
import { alerts } from '../state/alerts';
import { useStore } from '../state/store';
import { ResolveDialog } from './ResolveDialog';

const MAX_ROWS = 300;
const ORDER = { OPEN: 0, CLAIMED: 1, RESOLVED: 2 } as const;

interface Props {
  username: string;
  isSupervisor: boolean;
  zoneName: (id: string) => string;
  onSelectAsset: (assetId: string) => void;
}

/**
 * The operator workflow. Rows are a plain list of buttons, so everything works with the keyboard
 * and a screen reader. A lost race (409) is explained in words, not shown as a generic error.
 */
export function AlertPanel({ username, isSupervisor, zoneName, onSelectAsset }: Props) {
  const version = useStore(alerts);
  const [filter, setFilter] = useState<'active' | 'mine' | 'all'>('active');
  const [notice, setNotice] = useState<{ kind: 'info' | 'error'; text: string } | null>(null);
  const [resolving, setResolving] = useState<Alert | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const rows = useMemo(() => {
    const list = [...alerts.byId.values()].filter((a) =>
      filter === 'all' ? true : filter === 'mine' ? a.claimedBy === username && a.status === 'CLAIMED' : a.status !== 'RESOLVED');
    list.sort((a, b) => ORDER[a.status] - ORDER[b.status] || (a.openedAt < b.openedAt ? 1 : -1));
    return list;
  }, [version, filter, username]);

  const counts = useMemo(() => {
    let open = 0, claimed = 0;
    for (const a of alerts.byId.values()) {
      if (a.status === 'OPEN') open++;
      else if (a.status === 'CLAIMED') claimed++;
    }
    return { open, claimed };
  }, [version]);

  const label = useCallback((a: Alert) => `alert ${a.alertId}: ${a.assetId} in ${zoneName(a.zoneId)}`, [zoneName]);

  const run = async (a: Alert, action: 'claim' | 'release', fn: () => Promise<Alert>) => {
    setBusyId(a.alertId);
    setNotice(null);
    try {
      const updated = await fn();
      alerts.merge([updated], false);
      setNotice({ kind: 'info', text: `${action === 'claim' ? 'You claimed' : 'You released'} ${label(a)}.` });
    } catch (err) {
      setNotice({ kind: 'error', text: explain(err, a, label(a)) });
      await refresh(a.alertId);
    } finally {
      setBusyId(null);
    }
  };

  const resolve = async (note: string) => {
    const a = resolving!;
    try {
      const updated = await api.resolve(a.alertId, note);
      alerts.merge([updated], false);
      setNotice({ kind: 'info', text: `You resolved ${label(a)}.` });
      setResolving(null);
    } catch (err) {
      setNotice({ kind: 'error', text: explain(err, a, label(a)) });
      setResolving(null);
      await refresh(a.alertId);
    }
  };

  return (
    <section className="panel" aria-labelledby="alerts-title">
      <div className="panel-head">
        <h2 id="alerts-title">Alerts</h2>
        <span className="muted">{counts.open} open · {counts.claimed} claimed</span>
      </div>
      <fieldset className="filters">
        <legend className="sr-only">Show</legend>
        {(['active', 'mine', 'all'] as const).map((f) => (
          <label key={f}>
            <input type="radio" name="alert-filter" checked={filter === f} onChange={() => setFilter(f)} />
            {f === 'active' ? 'Open and claimed' : f === 'mine' ? 'Claimed by me' : 'All, including resolved'}
          </label>
        ))}
      </fieldset>
      <div role="alert" aria-live="assertive" className={notice ? `notice notice-${notice.kind}` : 'notice'}>
        {notice?.text}
      </div>
      {rows.length === 0 && <p className="muted">No alerts to show.</p>}
      <ul className="alert-list">
        {rows.slice(0, MAX_ROWS).map((a) => {
          const mine = a.claimedBy === username;
          const canAct = mine || isSupervisor;
          const busy = busyId === a.alertId;
          return (
            <li key={a.alertId} className={`alert alert-${a.status.toLowerCase()}`}>
              <div className="alert-main">
                <span className={`badge badge-${a.status.toLowerCase()}`}>{a.status}</span>
                <span><strong>{zoneName(a.zoneId)}</strong> · {a.assetId}</span>
              </div>
              <div className="muted small">
                #{a.alertId} · opened {time(a.openedAt)}
                {a.claimedBy && a.status === 'CLAIMED' && <> · owner <strong>{mine ? 'you' : a.claimedBy}</strong></>}
                {a.status === 'RESOLVED' && <> · resolved by {a.resolvedBy}</>}
              </div>
              <div className="alert-actions">
                <button type="button" onClick={() => onSelectAsset(a.assetId)} aria-label={`Show ${a.assetId} on the map`}>Show</button>
                {a.status === 'OPEN' && (
                  <button type="button" disabled={busy} onClick={() => run(a, 'claim', () => api.claim(a.alertId))}
                    aria-label={`Claim ${label(a)}`}>Claim</button>
                )}
                {a.status === 'CLAIMED' && canAct && (
                  <>
                    <button type="button" disabled={busy} onClick={() => run(a, 'release', () => api.release(a.alertId))}
                      aria-label={`Release ${label(a)}`}>Release</button>
                    <button type="button" disabled={busy} onClick={() => setResolving(a)}
                      aria-label={`Resolve ${label(a)}`}>Resolve</button>
                  </>
                )}
              </div>
            </li>
          );
        })}
      </ul>
      {rows.length > MAX_ROWS && <p className="muted">Showing the first {MAX_ROWS} of {rows.length}.</p>}
      {resolving && <ResolveDialog alertLabel={label(resolving)} onCancel={() => setResolving(null)} onSubmit={resolve} />}
    </section>
  );
}

async function refresh(alertId: string) {
  try {
    const res = await fetch(`/api/v1/alerts/${alertId}`, { headers: { Authorization: `Bearer ${getToken() ?? ''}` } });
    if (res.ok) alerts.merge([(await res.json()) as Alert], false);
  } catch {
    // the live stream will bring the change
  }
}

/** Turns a refusal into a sentence an operator can act on. */
function explain(err: unknown, a: Alert, what: string): string {
  if (!(err instanceof ApiError)) return `Could not reach the server for ${what}. Check the connection and try again.`;
  switch (err.code) {
    case 'ALERT_ALREADY_CLAIMED':
      return `Someone else was faster: ${err.problem?.detail?.replace('Alert is already claimed by ', '') ?? 'another operator'} claimed ${what} first. It is theirs now.`;
    case 'INVALID_STATE':
      return `${what} changed before your action arrived (${err.problem?.detail ?? 'state changed'}). The list now shows its current state.`;
    case 'NOT_ALERT_OWNER':
      return `Only the owner or a supervisor can do that on ${what}.`;
    case 'VALIDATION_FAILED':
      return `The note for ${what} is not valid: ${err.problem?.detail ?? ''}`;
    default:
      return `The server refused the action on ${what} (${err.status}). ${a.status === 'RESOLVED' ? '' : 'Try again.'}`;
  }
}

function time(iso: string) {
  return iso.slice(11, 19) + 'Z';
}
