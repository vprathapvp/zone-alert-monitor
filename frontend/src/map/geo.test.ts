import { describe, expect, it } from 'vitest';
import { isNewer } from '../state/positions';
import { prepareZone, screenX, wrapLon, zoneContains } from './geo';

const antimeridian = prepareZone({
  zoneId: 'Z-08', name: 'Hotel Line', zoneClass: 'RESTRICTED',
  // As served by the backend: unwrapped, so -179.4 becomes 180.6.
  polygons: [[[[179.3, 34], [180.6, 34], [180.6, 35], [179.3, 35], [179.3, 34]]]],
});

const withHole = prepareZone({
  zoneId: 'Z-03', name: 'Charlie Basin', zoneClass: 'RESTRICTED',
  polygons: [[
    [[172.5, 39], [173.9, 39], [173.9, 40.1], [172.5, 40.1], [172.5, 39]],
    [[173, 39.4], [173, 39.8], [173.4, 39.8], [173.4, 39.4], [173, 39.4]],
  ]],
});

describe('zone containment (same rules as the backend)', () => {
  it('finds points on both sides of the 180th meridian', () => {
    expect(zoneContains(antimeridian, 34.5, 179.6)).toBe(true);
    expect(zoneContains(antimeridian, 34.5, -179.6)).toBe(true);
    expect(zoneContains(antimeridian, 34.5, 170)).toBe(false);
  });
  it('excludes holes', () => {
    expect(zoneContains(withHole, 39.2, 172.7)).toBe(true);
    expect(zoneContains(withHole, 39.6, 173.2)).toBe(false);
  });
});

describe('projection', () => {
  it('is seamless across the meridian', () => {
    const v = { centerLon: 179.9, centerLat: 35, scale: 1000, width: 800, height: 600 };
    expect(screenX(v, -179.9)).toBeGreaterThan(screenX(v, 179.9));
    expect(wrapLon(190)).toBeCloseTo(-170);
  });
});

describe('position merge', () => {
  const p = (observedAt: string, reportId: string) => ({ assetId: 'A', reportId, observedAt, lat: 0, lon: 0 });
  it('never moves backwards in event time', () => {
    expect(isNewer(p('2026-11-02T06:00:02.000Z', 'a'), p('2026-11-02T06:00:01.000Z', 'b'))).toBe(true);
    expect(isNewer(p('2026-11-02T06:00:01.000Z', 'z'), p('2026-11-02T06:00:02.000Z', 'a'))).toBe(false);
    expect(isNewer(p('2026-11-02T06:00:01.000Z', 'a'), p('2026-11-02T06:00:01.000Z', 'a'))).toBe(false);
  });
});
