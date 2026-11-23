import { useSyncExternalStore } from 'react';

/**
 * Minimal observable used by all client stores. Components subscribe with useStore(); the map
 * canvas does not subscribe at all, it reads the version number in its animation frame.
 */
export class Observable {
  private listeners = new Set<() => void>();
  version = 0;

  subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  };

  getVersion = (): number => this.version;

  protected changed(): void {
    this.version++;
    for (const fn of this.listeners) fn();
  }
}

/** Re-renders the component when the store changes; returns the store's version. */
export function useStore(store: Observable): number {
  return useSyncExternalStore(store.subscribe, store.getVersion);
}
