import type { ZoneDto } from '../api/types';

/** Wraps a longitude difference into [-180, 180). */
export function wrapLon(d: number): number {
  return ((((d + 180) % 360) + 360) % 360) - 180;
}

/** Web Mercator y in radians. Latitudes are clamped so the poles stay finite. */
export function mercY(lat: number): number {
  const c = Math.max(-85, Math.min(85, lat));
  return Math.log(Math.tan(Math.PI / 4 + (c * Math.PI) / 360));
}

export function invMercY(y: number): number {
  return ((2 * Math.atan(Math.exp(y)) - Math.PI / 2) * 180) / Math.PI;
}

export interface View {
  centerLon: number;
  centerLat: number;
  /** Pixels per radian of Mercator. */
  scale: number;
  width: number;
  height: number;
}

/**
 * Screen x for a longitude. Longitudes are taken relative to the centre and wrapped, so the map
 * is seamless across the 180th meridian (the data sits on both sides of it).
 */
export function screenX(v: View, lon: number): number {
  return v.width / 2 + (wrapLon(lon - v.centerLon) * Math.PI * v.scale) / 180;
}

export function screenY(v: View, lat: number): number {
  return v.height / 2 - (mercY(lat) - mercY(v.centerLat)) * v.scale;
}

export function toLonLat(v: View, x: number, y: number): [number, number] {
  const lon = v.centerLon + ((x - v.width / 2) * 180) / (Math.PI * v.scale);
  const lat = invMercY(mercY(v.centerLat) - (y - v.height / 2) / v.scale);
  return [wrapLon(lon), lat];
}

/** Even-odd ray casting; same algorithm as the backend (ZoneGeometry.inRing). */
function inRing(ring: number[][], x: number, y: number): boolean {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const pi = ring[i]!;
    const pj = ring[j]!;
    const xi = pi[0]!, yi = pi[1]!, xj = pj[0]!, yj = pj[1]!;
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

export interface PreparedZone extends ZoneDto {
  bbox: [number, number, number, number]; // minLon, minLat, maxLon, maxLat (unwrapped)
  labelLon: number;
  labelLat: number;
}

export function prepareZone(z: ZoneDto): PreparedZone {
  let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity;
  for (const poly of z.polygons) {
    for (const [lon, lat] of poly[0] ?? []) {
      minLon = Math.min(minLon, lon!);
      maxLon = Math.max(maxLon, lon!);
      minLat = Math.min(minLat, lat!);
      maxLat = Math.max(maxLat, lat!);
    }
  }
  return { ...z, bbox: [minLon, minLat, maxLon, maxLat], labelLon: (minLon + maxLon) / 2, labelLat: maxLat };
}

/** Point in zone: outer ring and not in a hole, any part, tested on both sides of the meridian. */
export function zoneContains(z: PreparedZone, lat: number, lon: number): boolean {
  if (lat < z.bbox[1] || lat > z.bbox[3]) return false;
  for (const candidate of [lon, lon + 360, lon - 360]) {
    if (candidate < z.bbox[0] || candidate > z.bbox[2]) continue;
    for (const poly of z.polygons) {
      const outer = poly[0];
      if (!outer || !inRing(outer, candidate, lat)) continue;
      let inHole = false;
      for (let h = 1; h < poly.length; h++) {
        if (inRing(poly[h]!, candidate, lat)) {
          inHole = true;
          break;
        }
      }
      if (!inHole) return true;
    }
  }
  return false;
}
