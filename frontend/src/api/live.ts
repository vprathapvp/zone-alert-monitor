import { api, getToken } from './client';
import type { Alert, AssetPosition } from './types';
import { alerts } from '../state/alerts';
import { connection } from '../state/connection';
import { positions } from '../state/positions';

/**
 * Keeps the console correct: opens the live stream, loads a snapshot after every (re)connect,
 * and reconnects with backoff. The stream is read with fetch so the bearer token travels in a
 * header (EventSource cannot set headers).
 *
 * Order matters: the server registers the subscriber before it sends "hello", and the snapshot is
 * loaded only after "hello". Everything committed after that point arrives as an event, and the
 * stores merge by event time / version, so snapshot and events can arrive in any order.
 */
const SILENCE_LIMIT_MS = 20_000; // server heartbeat is every 15 s; data events come every 0.5 s
const WORLD = '-180,-90,180,90';

export class LiveClient {
  private abort: AbortController | null = null;
  private stopped = false;
  private attempt = 0;
  private watchdog: ReturnType<typeof setTimeout> | null = null;

  start(): void {
    this.stopped = false;
    void this.loop();
  }

  stop(): void {
    this.stopped = true;
    this.abort?.abort();
    if (this.watchdog) clearTimeout(this.watchdog);
  }

  private async loop(): Promise<void> {
    while (!this.stopped) {
      connection.set(this.attempt === 0 ? 'connecting' : 'reconnecting');
      try {
        await this.connectOnce();
      } catch {
        // falls through to the backoff below
      }
      if (this.stopped) return;
      connection.set('reconnecting');
      this.attempt++;
      const delay = Math.min(5000, 500 * 2 ** Math.min(this.attempt, 4));
      await new Promise((r) => setTimeout(r, delay));
    }
  }

  private armWatchdog(): void {
    if (this.watchdog) clearTimeout(this.watchdog);
    this.watchdog = setTimeout(() => this.abort?.abort(), SILENCE_LIMIT_MS);
  }

  private async connectOnce(): Promise<void> {
    this.abort = new AbortController();
    this.armWatchdog();
    const res = await fetch('/api/v1/live', {
      headers: { Authorization: `Bearer ${getToken() ?? ''}`, Accept: 'text/event-stream' },
      signal: this.abort.signal,
      cache: 'no-store',
    });
    if (!res.ok || !res.body) throw new Error(`live stream refused: ${res.status}`);
    const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      this.armWatchdog();
      buffer += value;
      let sep: number;
      while ((sep = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        this.dispatch(block);
      }
    }
  }

  private dispatch(block: string): void {
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) continue; // heartbeat comment
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (data.length === 0) return;
    connection.touch();
    const payload: unknown = JSON.parse(data.join('\n'));
    switch (event) {
      case 'hello':
        this.attempt = 0;
        connection.set('live', (payload as { instance: string }).instance);
        // A failed snapshot leaves the console incomplete: drop the stream and start over.
        loadSnapshot().catch(() => this.abort?.abort());
        break;
      case 'positions':
        positions.merge(payload as AssetPosition[]);
        break;
      case 'alerts':
        alerts.merge(payload as Alert[], true);
        break;
      case 'resync':
        loadSnapshot().catch(() => this.abort?.abort());
        break;
      default:
        break;
    }
  }
}

export async function loadSnapshot(): Promise<void> {
  const page = await api.assetsInBox(WORLD, 5000);
  positions.merge(page.items);

  const seen = new Set<string>();
  for (const status of ['OPEN', 'CLAIMED'] as const) {
    let cursor: string | null = null;
    do {
      const res = await api.alerts(status, cursor);
      res.items.forEach((a) => seen.add(a.alertId));
      alerts.merge(res.items, false);
      cursor = res.nextCursor;
    } while (cursor);
  }
  // An alert we showed as active but that is no longer active changed while we were away.
  for (const id of alerts.staleActive(seen)) {
    try {
      const res = await fetch(`/api/v1/alerts/${id}`, { headers: { Authorization: `Bearer ${getToken() ?? ''}` } });
      if (res.ok) alerts.merge([(await res.json()) as Alert], false);
    } catch {
      // next snapshot will retry
    }
  }
}
