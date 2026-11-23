import { useEffect, useState } from 'react';
import { zoneContains, type PreparedZone } from '../map/geo';
import { positions } from '../state/positions';

interface Row {
  zone: PreparedZone;
  assets: string[];
}

/**
 * The non-map way to see which assets are inside which zone (WCAG: information must not be
 * conveyed only by the map). Recomputed every 2 seconds while visible, not on every update.
 */
export function ZoneOccupancy({ zones, onSelectAsset }: { zones: PreparedZone[]; onSelectAsset: (id: string) => void }) {
  const [rows, setRows] = useState<Row[]>([]);

  useEffect(() => {
    const compute = () => {
      const out: Row[] = zones.map((zone) => ({ zone, assets: [] }));
      for (const p of positions.byId.values()) {
        for (const r of out) if (zoneContains(r.zone, p.lat, p.lon)) r.assets.push(p.assetId);
      }
      out.forEach((r) => r.assets.sort());
      setRows(out);
    };
    compute();
    const t = setInterval(compute, 2000);
    return () => clearInterval(t);
  }, [zones]);

  return (
    <section className="panel" aria-labelledby="zones-title">
      <h2 id="zones-title">Assets inside zones</h2>
      <p className="muted small">Updated every 2 seconds.</p>
      {rows.map(({ zone, assets }) => (
        <details key={zone.zoneId} className="zone-row">
          <summary>
            <strong>{zone.name}</strong> ({zone.zoneId}, {zone.zoneClass.toLowerCase()}): {assets.length} asset{assets.length === 1 ? '' : 's'}
          </summary>
          {assets.length === 0 ? <p className="muted small">None.</p> : (
            <ul className="chips">
              {assets.slice(0, 200).map((id) => (
                <li key={id}><button type="button" onClick={() => onSelectAsset(id)}>{id}</button></li>
              ))}
              {assets.length > 200 && <li className="muted">…and {assets.length - 200} more</li>}
            </ul>
          )}
        </details>
      ))}
    </section>
  );
}
