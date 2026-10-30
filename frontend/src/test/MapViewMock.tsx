import type { LngLat, MapLine, MapMarker } from '../components/map/MapView';

export type { LngLat, MapLine, MapMarker };

/** Testlerde harita: işaretçileri ve çizgileri listeler, tıklamayı düğmeyle taklit eder. */
export function MapView({ markers = [], lines = [], onPick }: {
  markers?: MapMarker[];
  lines?: MapLine[];
  onPick?: (lat: number, lon: number) => void;
}) {
  return (
    <div data-testid="map">
      <span data-testid="map-markers">{markers.map((m) => m.label ?? m.id).join(',')}</span>
      <span data-testid="map-lines">{lines.map((l) => `${l.color}:${l.coordinates.length}`).join(',')}</span>
      {onPick && (
        <>
          <button type="button" onClick={() => onPick(40.3521, 27.9767)}>Haritada Bandırma'ya tıkla</button>
          <button type="button" onClick={() => onPick(41.0082, 28.9784)}>Haritada İstanbul'a tıkla</button>
        </>
      )}
    </div>
  );
}
