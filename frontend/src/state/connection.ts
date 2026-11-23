import { Observable } from './store';

export type LinkStatus = 'connecting' | 'live' | 'reconnecting';

/** Live-update link state shown to the operator. */
export class ConnectionStore extends Observable {
  status: LinkStatus = 'connecting';
  since = Date.now();
  instance: string | null = null;
  lastEventAt: number | null = null;

  set(status: LinkStatus, instance?: string | null): void {
    if (status !== this.status) {
      this.status = status;
      this.since = Date.now();
    }
    if (instance !== undefined) this.instance = instance;
    this.changed();
  }

  touch(): void {
    this.lastEventAt = Date.now();
  }
}

export const connection = new ConnectionStore();
