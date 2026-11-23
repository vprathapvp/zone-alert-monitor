// Types that mirror openapi.yaml (the fixed contract). Field names and nullability follow the
// schemas exactly; additions made by this system (Alert.version, zones, live events) are marked.

export type Role = 'INGEST' | 'OPERATOR' | 'SUPERVISOR';

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSec: number;
  user: { username: string; role: Role };
}

export interface AssetPosition {
  assetId: string;
  kind?: string | null;
  reportId: string;
  observedAt: string;
  lat: number;
  lon: number;
  speedKn?: number | null;
  headingDeg?: number | null;
  source?: string | null;
  /** 1.1.0 (CR-1). */
  sourceType?: SourceType;
}

/** 1.1.0 (CR-1): derived from source. */
export type SourceType = 'SATELLITE' | 'GROUND' | 'MOBILE' | 'UNKNOWN';

export interface TrackPoint {
  reportId: string;
  observedAt: string;
  lat: number;
  lon: number;
  source?: string | null;
  /** 1.1.0 (CR-1); absent when served by a 1.0.0 instance during the rolling upgrade. */
  sourceType?: SourceType;
}

export type AlertStatus = 'OPEN' | 'CLAIMED' | 'RESOLVED';

export interface Alert {
  alertId: string;
  assetId: string;
  zoneId: string;
  status: AlertStatus;
  openedAt: string;
  triggerReportId: string;
  triggerObservedAt?: string;
  claimedBy?: string | null;
  claimedAt?: string | null;
  resolvedBy?: string | null;
  resolvedAt?: string | null;
  resolutionNote?: string | null;
  /** Addition: grows with every change; used to merge snapshot and live copies. */
  version?: number;
}

export interface AuditEntry {
  at: string;
  actor: string;
  action: 'OPENED' | 'CLAIMED' | 'RELEASED' | 'RESOLVED';
  fromStatus?: string | null;
  toStatus: AlertStatus;
}

export interface Paged<T> {
  items: T[];
  truncated: boolean;
}

export interface AlertPage {
  items: Alert[];
  nextCursor: string | null;
}

export interface Stats {
  received: number;
  accepted: number;
  duplicates: number;
  rejected: number;
  assets: number;
  alerts: { open: number; claimed: number; resolved: number };
}

export interface Problem {
  type: string;
  title: string;
  status: number;
  code: string;
  detail?: string;
  instance?: string;
}

/** Addition: zones for the map. Polygons are arrays of rings of [lon, lat]; lon may exceed 180. */
export interface ZoneDto {
  zoneId: string;
  name: string;
  zoneClass: 'RESTRICTED' | 'ADVISORY';
  polygons: number[][][][];
}
