import { useEffect, useRef } from 'react';
import type { AssetPosition, TrackPoint } from '../api/types';
import { alerts } from '../state/alerts';
import { positions } from '../state/positions';
import { mercY, screenX, screenY, toLonLat, wrapLon, type PreparedZone, type View } from './geo';

/**
 * The live map: one <canvas>, drawn by hand. No tiles, fonts or map library are fetched, so it
 * works on an isolated network by construction. A frame is drawn only when something changed
 * (positions, alerts, view, selection), at most once per animation frame. Drawing 3,000
 * headings as one path per colour takes a few milliseconds.
 */
interface Props {
  zones: PreparedZone[];
  selectedAssetId: string | null;
  track: TrackPoint[];
  onSelect: (assetId: string | null) => void;
}

const COLORS = {
  sea: '#0f1d2b',
  grid: 'rgba(255,255,255,0.07)',
  restrictedFill: 'rgba(230,57,70,0.18)',
  restrictedLine: '#e63946',
  advisoryFill: 'rgba(244,162,97,0.14)',
  advisoryLine: '#f4a261',
  surface: '#8ecae6',
  air: '#c3f584',
  alerting: '#ff4d6d',
  selected: '#ffffff',
  track: '#ffd166',
  label: '#e9eef3',
};

export function MapCanvas({ zones, selectedAssetId, track, onSelect }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const viewRef = useRef<View>({ centerLon: 173.5, centerLat: 36.5, scale: 2600, width: 800, height: 600 });
  const dirtyRef = useRef(true);
  const propsRef = useRef({ zones, selectedAssetId, track });
  propsRef.current = { zones, selectedAssetId, track };

  useEffect(() => {
    dirtyRef.current = true;
  }, [zones, selectedAssetId, track]);

  useEffect(() => {
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext('2d', { alpha: false })!;
    let raf = 0;
    let seenPositions = -1;
    let seenAlerts = -1;

    const resize = () => {
      const dpr = window.devicePixelRatio || 1;
      const rect = canvas.getBoundingClientRect();
      canvas.width = Math.max(1, Math.floor(rect.width * dpr));
      canvas.height = Math.max(1, Math.floor(rect.height * dpr));
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      viewRef.current = { ...viewRef.current, width: rect.width, height: rect.height };
      dirtyRef.current = true;
    };
    const ro = new ResizeObserver(resize);
    ro.observe(canvas);
    resize();

    const frame = () => {
      raf = requestAnimationFrame(frame);
      if (positions.version !== seenPositions || alerts.version !== seenAlerts) {
        seenPositions = positions.version;
        seenAlerts = alerts.version;
        dirtyRef.current = true;
      }
      if (!dirtyRef.current) return;
      dirtyRef.current = false;
      draw(ctx, viewRef.current, propsRef.current.zones, propsRef.current.selectedAssetId, propsRef.current.track);
    };
    raf = requestAnimationFrame(frame);
    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
    };
  }, []);

  // Pan (drag), zoom (wheel), select (click), keyboard pan/zoom.
  useEffect(() => {
    const canvas = canvasRef.current!;
    let drag: { x: number; y: number; moved: boolean } | null = null;

    const zoomAt = (factor: number, x: number, y: number) => {
      const v = viewRef.current;
      const [lon, lat] = toLonLat(v, x, y);
      const scale = Math.max(150, Math.min(400_000, v.scale * factor));
      // Keep the point under the cursor fixed.
      const centerLon = wrapLon(lon - ((x - v.width / 2) * 180) / (Math.PI * scale));
      const centerMerc = mercY(lat) + (y - v.height / 2) / scale;
      const centerLat = (2 * Math.atan(Math.exp(centerMerc)) - Math.PI / 2) * (180 / Math.PI);
      viewRef.current = { ...v, scale, centerLon, centerLat };
      dirtyRef.current = true;
    };
    const panBy = (dx: number, dy: number) => {
      const v = viewRef.current;
      const centerLon = wrapLon(v.centerLon - (dx * 180) / (Math.PI * v.scale));
      const m = mercY(v.centerLat) + dy / v.scale;
      const centerLat = Math.max(-80, Math.min(80, (2 * Math.atan(Math.exp(m)) - Math.PI / 2) * (180 / Math.PI)));
      viewRef.current = { ...v, centerLon, centerLat };
      dirtyRef.current = true;
    };

    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const r = canvas.getBoundingClientRect();
      zoomAt(Math.exp(-e.deltaY * 0.0015), e.clientX - r.left, e.clientY - r.top);
    };
    const onDown = (e: PointerEvent) => {
      drag = { x: e.clientX, y: e.clientY, moved: false };
      canvas.setPointerCapture(e.pointerId);
    };
    const onMove = (e: PointerEvent) => {
      if (!drag) return;
      const dx = e.clientX - drag.x;
      const dy = e.clientY - drag.y;
      if (Math.abs(dx) + Math.abs(dy) > 3) drag.moved = true;
      if (drag.moved) {
        panBy(dx, dy);
        drag.x = e.clientX;
        drag.y = e.clientY;
      }
    };
    const onUp = (e: PointerEvent) => {
      const wasClick = drag && !drag.moved;
      drag = null;
      if (!wasClick) return;
      const r = canvas.getBoundingClientRect();
      onSelect(hitTest(viewRef.current, e.clientX - r.left, e.clientY - r.top));
    };
    const onKey = (e: KeyboardEvent) => {
      const v = viewRef.current;
      const step = 80;
      switch (e.key) {
        case 'ArrowLeft': panBy(step, 0); break;
        case 'ArrowRight': panBy(-step, 0); break;
        case 'ArrowUp': panBy(0, step); break;
        case 'ArrowDown': panBy(0, -step); break;
        case '+': case '=': zoomAt(1.4, v.width / 2, v.height / 2); break;
        case '-': case '_': zoomAt(1 / 1.4, v.width / 2, v.height / 2); break;
        case 'Escape': onSelect(null); break;
        default: return;
      }
      e.preventDefault();
    };
    canvas.addEventListener('wheel', onWheel, { passive: false });
    canvas.addEventListener('pointerdown', onDown);
    canvas.addEventListener('pointermove', onMove);
    canvas.addEventListener('pointerup', onUp);
    canvas.addEventListener('keydown', onKey);
    return () => {
      canvas.removeEventListener('wheel', onWheel);
      canvas.removeEventListener('pointerdown', onDown);
      canvas.removeEventListener('pointermove', onMove);
      canvas.removeEventListener('pointerup', onUp);
      canvas.removeEventListener('keydown', onKey);
    };
  }, [onSelect]);

  // Centre on a newly selected asset when it is off screen.
  useEffect(() => {
    if (!selectedAssetId) return;
    const p = positions.byId.get(selectedAssetId);
    if (!p) return;
    const v = viewRef.current;
    const x = screenX(v, p.lon);
    const y = screenY(v, p.lat);
    if (x < 0 || y < 0 || x > v.width || y > v.height) {
      viewRef.current = { ...v, centerLon: p.lon, centerLat: p.lat };
      dirtyRef.current = true;
    }
  }, [selectedAssetId]);

  return (
    <canvas
      ref={canvasRef}
      className="map"
      tabIndex={0}
      role="img"
      aria-label="Live map of assets and zones. Arrow keys pan, plus and minus zoom, Escape clears the selection. The Zones tab lists the same information as text."
    />
  );
}

function hitTest(v: View, x: number, y: number): string | null {
  let best: string | null = null;
  let bestD = 10 * 10;
  for (const p of positions.byId.values()) {
    const dx = screenX(v, p.lon) - x;
    const dy = screenY(v, p.lat) - y;
    const d = dx * dx + dy * dy;
    if (d < bestD) {
      bestD = d;
      best = p.assetId;
    }
  }
  return best;
}

function draw(ctx: CanvasRenderingContext2D, v: View, zones: PreparedZone[], selected: string | null, track: TrackPoint[]) {
  ctx.fillStyle = COLORS.sea;
  ctx.fillRect(0, 0, v.width, v.height);
  drawGrid(ctx, v);

  for (const z of zones) drawZone(ctx, v, z);

  // Assets with an active alert are drawn in the alert colour.
  const alerting = new Set<string>();
  for (const a of alerts.byId.values()) if (a.status !== 'RESOLVED') alerting.add(a.assetId);

  const size = v.scale > 20_000 ? 7 : v.scale > 5_000 ? 5 : 4;
  const groups: Record<'surface' | 'air' | 'alerting', Path2D> = { surface: new Path2D(), air: new Path2D(), alerting: new Path2D() };
  let selectedPos: AssetPosition | undefined;
  for (const p of positions.byId.values()) {
    const x = screenX(v, p.lon);
    const y = screenY(v, p.lat);
    if (x < -10 || y < -10 || x > v.width + 10 || y > v.height + 10) continue;
    if (p.assetId === selected) selectedPos = p;
    const path = alerting.has(p.assetId) ? groups.alerting : p.kind === 'AIR' ? groups.air : groups.surface;
    addHeadingSymbol(path, x, y, p.headingDeg, size);
  }
  ctx.fillStyle = COLORS.surface;
  ctx.fill(groups.surface);
  ctx.fillStyle = COLORS.air;
  ctx.fill(groups.air);
  ctx.fillStyle = COLORS.alerting;
  ctx.fill(groups.alerting);

  if (track.length > 1) {
    ctx.strokeStyle = COLORS.track;
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    track.forEach((t, i) => {
      const x = screenX(v, t.lon);
      const y = screenY(v, t.lat);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();
  }
  if (selectedPos) {
    ctx.strokeStyle = COLORS.selected;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(screenX(v, selectedPos.lon), screenY(v, selectedPos.lat), size + 6, 0, Math.PI * 2);
    ctx.stroke();
  }
}

/** A triangle pointing along the heading; a dot when the heading is unknown. */
function addHeadingSymbol(path: Path2D, x: number, y: number, heading: number | null | undefined, size: number) {
  if (heading == null || !Number.isFinite(heading)) {
    path.moveTo(x + size * 0.6, y);
    path.arc(x, y, size * 0.6, 0, Math.PI * 2);
    return;
  }
  const a = (heading * Math.PI) / 180; // 0 = north, clockwise
  const sin = Math.sin(a);
  const cos = Math.cos(a);
  const pt = (dx: number, dy: number): [number, number] => [x + dx * cos - dy * sin, y + dx * sin + dy * cos];
  const [x1, y1] = pt(0, -size * 1.4);
  const [x2, y2] = pt(size * 0.8, size);
  const [x3, y3] = pt(-size * 0.8, size);
  path.moveTo(x1, y1);
  path.lineTo(x2, y2);
  path.lineTo(x3, y3);
  path.closePath();
}

function drawZone(ctx: CanvasRenderingContext2D, v: View, z: PreparedZone) {
  const restricted = z.zoneClass === 'RESTRICTED';
  const path = new Path2D();
  for (const poly of z.polygons) {
    for (const ring of poly) {
      const first = ring[0];
      if (!first) continue;
      // Shift the whole ring once so it stays continuous across the meridian.
      const offset = wrapLon(first[0]! - v.centerLon) - (first[0]! - v.centerLon);
      ring.forEach(([lon, lat], i) => {
        const x = v.width / 2 + ((lon! - v.centerLon + offset) * Math.PI * v.scale) / 180;
        const y = screenY(v, lat!);
        if (i === 0) path.moveTo(x, y);
        else path.lineTo(x, y);
      });
      path.closePath();
    }
  }
  ctx.fillStyle = restricted ? COLORS.restrictedFill : COLORS.advisoryFill;
  ctx.fill(path, 'evenodd');
  ctx.strokeStyle = restricted ? COLORS.restrictedLine : COLORS.advisoryLine;
  ctx.lineWidth = 1.5;
  if (!restricted) ctx.setLineDash([6, 4]);
  ctx.stroke(path);
  ctx.setLineDash([]);

  ctx.fillStyle = COLORS.label;
  ctx.font = '12px system-ui, sans-serif';
  ctx.textAlign = 'center';
  const lx = screenX(v, z.labelLon);
  const ly = screenY(v, z.labelLat) - 6;
  ctx.fillText(`${z.name} (${restricted ? 'RESTRICTED' : 'ADVISORY'})`, lx, ly);
}

function drawGrid(ctx: CanvasRenderingContext2D, v: View) {
  const step = v.scale > 8000 ? 1 : v.scale > 1500 ? 5 : 10;
  ctx.strokeStyle = COLORS.grid;
  ctx.lineWidth = 1;
  ctx.beginPath();
  const [lonL] = toLonLat(v, 0, 0);
  const spanDeg = (v.width * 180) / (Math.PI * v.scale);
  const start = Math.floor(lonL / step) * step;
  for (let lon = start; lon <= lonL + spanDeg + step; lon += step) {
    const x = screenX(v, lon);
    ctx.moveTo(x, 0);
    ctx.lineTo(x, v.height);
  }
  const [, latTop] = toLonLat(v, 0, 0);
  const [, latBottom] = toLonLat(v, 0, v.height);
  for (let lat = Math.floor(latBottom / step) * step; lat <= latTop + step; lat += step) {
    const y = screenY(v, lat);
    ctx.moveTo(0, y);
    ctx.lineTo(v.width, y);
  }
  ctx.stroke();
}
