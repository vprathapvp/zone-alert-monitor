import type {
  Alert, AlertPage, AssetPosition, AuditEntry, LoginResponse, Paged, Problem, TrackPoint, ZoneDto,
} from './types';

/** An error answer from the API, carrying the problem code from the contract catalogue. */
export class ApiError extends Error {
  constructor(public readonly status: number, public readonly problem: Problem | null) {
    super(problem?.detail ?? problem?.title ?? `HTTP ${status}`);
  }
  get code(): string | undefined {
    return this.problem?.code;
  }
}

let token: string | null = null;
let onUnauthenticated: () => void = () => {};

export function setToken(t: string | null): void {
  token = t;
}

export function getToken(): string | null {
  return token;
}

export function setUnauthenticatedHandler(fn: () => void): void {
  onUnauthenticated = fn;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let problem: Problem | null = null;
    try {
      problem = (await res.json()) as Problem;
    } catch {
      problem = null;
    }
    if (res.status === 401 && path !== '/api/v1/auth/login') onUnauthenticated();
    throw new ApiError(res.status, problem);
  }
  return (await res.json()) as T;
}

export const api = {
  login: (username: string, password: string) =>
    request<LoginResponse>('POST', '/api/v1/auth/login', { username, password }),
  zones: () => request<{ items: ZoneDto[] }>('GET', '/api/v1/zones'),
  assetsInBox: (bbox: string, limit: number) =>
    request<Paged<AssetPosition>>('GET', `/api/v1/assets?bbox=${bbox}&limit=${limit}`),
  track: (assetId: string, from: string | null, limit: number) =>
    request<Paged<TrackPoint>>(
      'GET',
      `/api/v1/assets/${encodeURIComponent(assetId)}/track?limit=${limit}${from ? `&from=${encodeURIComponent(from)}` : ''}`,
    ),
  alerts: (status: string, cursor: string | null) =>
    request<AlertPage>('GET', `/api/v1/alerts?status=${status}&limit=500${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`),
  audit: (alertId: string) => request<{ items: AuditEntry[] }>('GET', `/api/v1/alerts/${alertId}/audit`),
  claim: (alertId: string) => request<Alert>('POST', `/api/v1/alerts/${alertId}/claim`),
  release: (alertId: string) => request<Alert>('POST', `/api/v1/alerts/${alertId}/release`),
  resolve: (alertId: string, note: string) => request<Alert>('POST', `/api/v1/alerts/${alertId}/resolve`, { note }),
};
