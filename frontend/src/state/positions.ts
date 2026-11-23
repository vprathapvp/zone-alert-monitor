import type { AssetPosition } from '../api/types';
import { Observable } from './store';

/**
 * Current position of every asset. Server data (snapshot and live events) is merged by event
 * time, so a late or repeated copy never moves an asset backwards: the same rule as R2 on the
 * server. Listeners are notified at most once per animation frame however many events arrive.
 */
export class PositionStore extends Observable {
  readonly byId = new Map<string, AssetPosition>();
  private pending = false;

  merge(items: readonly AssetPosition[]): void {
    let changed = false;
    for (const p of items) {
      const cur = this.byId.get(p.assetId);
      if (!cur || isNewer(p, cur)) {
        this.byId.set(p.assetId, p);
        changed = true;
      }
    }
    if (changed) this.schedule();
  }

  clear(): void {
    this.byId.clear();
    this.schedule();
  }

  private schedule(): void {
    if (this.pending) return;
    this.pending = true;
    const flush = () => {
      this.pending = false;
      this.changed();
    };
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(flush);
    else flush();
  }
}

/** The API formats observedAt identically (ms, Z), so string order is time order. */
export function isNewer(candidate: AssetPosition, current: AssetPosition): boolean {
  if (candidate.observedAt !== current.observedAt) return candidate.observedAt > current.observedAt;
  return candidate.reportId !== current.reportId && candidate.reportId > current.reportId;
}

export const positions = new PositionStore();
