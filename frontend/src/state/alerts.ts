import type { Alert } from '../api/types';
import { Observable } from './store';

/**
 * Alerts known to the console. Copies are merged by version (it grows with every change on the
 * server), so a stale REST answer can never overwrite a newer live event or vice versa.
 */
export class AlertStore extends Observable {
  readonly byId = new Map<string, Alert>();
  private newListeners = new Set<(a: Alert) => void>();

  /** fromLive: alerts first seen in the live stream are announced; snapshot ones are not. */
  merge(items: readonly Alert[], fromLive: boolean): void {
    let changed = false;
    for (const a of items) {
      const cur = this.byId.get(a.alertId);
      if (!cur || (a.version ?? 0) >= (cur.version ?? 0)) {
        if (cur && cur.version === a.version && cur.status === a.status) continue;
        this.byId.set(a.alertId, a);
        changed = true;
        if (!cur && fromLive && a.status === 'OPEN') {
          for (const fn of this.newListeners) fn(a);
        }
      }
    }
    if (changed) this.changed();
  }

  /** Active alerts held locally but missing from a fresh snapshot changed while we were offline. */
  staleActive(snapshotIds: Set<string>): string[] {
    const ids: string[] = [];
    for (const a of this.byId.values()) {
      if (a.status !== 'RESOLVED' && !snapshotIds.has(a.alertId)) ids.push(a.alertId);
    }
    return ids;
  }

  onNewAlert(fn: (a: Alert) => void): () => void {
    this.newListeners.add(fn);
    return () => this.newListeners.delete(fn);
  }

  clear(): void {
    this.byId.clear();
    this.changed();
  }
}

export const alerts = new AlertStore();
