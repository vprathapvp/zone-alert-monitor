import type { TrackPoint } from '../api/types';
import { positions } from '../state/positions';
import { useStore } from '../state/store';

/** Latest report and recent track of the selected asset. */
export function AssetDetail({ assetId, track, trackError, onClose }: {
  assetId: string;
  track: TrackPoint[];
  trackError: string | null;
  onClose: () => void;
}) {
  useStore(positions);
  const p = positions.byId.get(assetId);
  return (
    <section className="panel" aria-labelledby="asset-title">
      <div className="panel-head">
        <h2 id="asset-title">Asset {assetId}</h2>
        <button type="button" onClick={onClose} aria-label="Close asset detail">Close</button>
      </div>
      {!p ? (
        <p className="muted">No position known yet.</p>
      ) : (
        <dl className="facts">
          <dt>Observed</dt><dd>{p.observedAt}</dd>
          <dt>Position</dt><dd>{p.lat.toFixed(5)}, {p.lon.toFixed(5)}</dd>
          <dt>Kind</dt><dd>{p.kind ?? '—'}</dd>
          <dt>Speed</dt><dd>{p.speedKn != null && p.speedKn >= 0 ? `${p.speedKn} kn` : 'unknown'}</dd>
          <dt>Heading</dt><dd>{p.headingDeg != null ? `${(((p.headingDeg % 360) + 360) % 360).toFixed(1)}°` : 'unknown'}</dd>
          <dt>Source</dt><dd>{p.source ?? '—'}</dd>
          <dt>Source type</dt><dd>{p.sourceType ?? '—'}</dd>
          <dt>Report</dt><dd className="mono">{p.reportId}</dd>
        </dl>
      )}
      <h3>Recent track ({track.length} points, last 15 minutes of event time)</h3>
      {trackError && <p role="alert" className="error">{trackError}</p>}
      <div className="track-wrap">
        <table className="track">
          <thead><tr><th scope="col">Observed</th><th scope="col">Lat</th><th scope="col">Lon</th><th scope="col">Source</th><th scope="col">Source type</th></tr></thead>
          <tbody>
            {track.slice(-50).reverse().map((t) => (
              <tr key={t.reportId}>
                <td>{t.observedAt.slice(11, 23)}</td><td>{t.lat.toFixed(4)}</td><td>{t.lon.toFixed(4)}</td><td>{t.source ?? '—'}</td><td>{t.sourceType ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
