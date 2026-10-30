import { useEffect, useRef, useState } from 'react';
import maplibregl, { type Map as MlMap, type Marker } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';

/**
 * MapLibre sarmalayıcısı. Harita React dışı bir sistemdir; efektler onu
 * prop'larla eşitler (türetilmiş durum değil, dış sistem senkronizasyonu).
 *
 * Altlık OSM raster karolarıdır ve yalnızca görüntü içindir: mesafe ve rota
 * hiçbir zaman buradan gelmez, kendi yol grafımızdan gelir.
 */

export type LngLat = [number, number];

export interface MapMarker {
  id: string | number;
  lat: number;
  lon: number;
  label?: string;
  tone?: 'depot' | 'stop' | 'pending' | 'selected';
  /** Verilirse tonun rengini ezer — çok araçlı planda rota rengi. */
  color?: string;
  title?: string;
}

export interface MapLine {
  id: string | number;
  coordinates: LngLat[];
  color: string;
}

interface Props {
  markers?: MapMarker[];
  lines?: MapLine[];
  onPick?: (lat: number, lon: number) => void;
  height?: number;
  /** İçerik değişince haritayı işaretçilere/çizgilere sığdır. */
  fit?: boolean;
}

// Bandırma merkezi
const CENTER: LngLat = [27.9767, 40.3521];

const STYLE: maplibregl.StyleSpecification = {
  version: 8,
  sources: {
    osm: {
      type: 'raster',
      tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
      tileSize: 256,
      maxzoom: 19,
      attribution: '© OpenStreetMap katkıda bulunanlar',
    },
  },
  layers: [{ id: 'osm', type: 'raster', source: 'osm' }],
};

export function MapView({ markers = [], lines = [], onPick, height = 420, fit = true }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<MlMap | null>(null);
  const placed = useRef<Marker[]>([]);
  const pick = useRef(onPick);
  const [ready, setReady] = useState(false);

  // Tıklama dinleyicisi harita bir kez kurulurken bağlanır; güncel callback ref'ten okunur.
  useEffect(() => {
    pick.current = onPick;
  }, [onPick]);

  useEffect(() => {
    if (!container.current) return;
    const m = new maplibregl.Map({ container: container.current, style: STYLE, center: CENTER, zoom: 12 });
    m.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
    m.on('click', (e) => pick.current?.(e.lngLat.lat, e.lngLat.lng));
    m.on('load', () => {
      // Tek kaynak, tüm rotalar: renk her çizginin kendi özelliğinden okunur.
      m.addSource('routes', { type: 'geojson', data: toCollection([]) });
      m.addLayer({
        id: 'routes',
        type: 'line',
        source: 'routes',
        layout: { 'line-join': 'round', 'line-cap': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': 4, 'line-opacity': 0.85 },
      });
      setReady(true);
    });
    map.current = m;
    return () => {
      m.remove();
      map.current = null;
    };
  }, []);

  useEffect(() => {
    const m = map.current;
    if (!m) return;
    placed.current.forEach((p) => p.remove());
    placed.current = markers.map((mk) => {
      const el = document.createElement('div');
      el.className = `pin ${mk.tone ?? 'stop'}`;
      if (mk.color) el.style.background = mk.color;
      el.textContent = mk.label ?? '';
      if (mk.title) el.title = mk.title;
      return new maplibregl.Marker({ element: el }).setLngLat([mk.lon, mk.lat]).addTo(m);
    });
  }, [markers]);

  useEffect(() => {
    const m = map.current;
    if (!m || !ready) return;
    (m.getSource('routes') as maplibregl.GeoJSONSource | undefined)?.setData(toCollection(lines));
  }, [lines, ready]);

  useEffect(() => {
    const m = map.current;
    if (!m || !fit) return;
    const points: LngLat[] = [...markers.map((mk): LngLat => [mk.lon, mk.lat]), ...lines.flatMap((l) => l.coordinates)];
    if (points.length === 0) return;
    if (points.length === 1) {
      m.easeTo({ center: points[0], zoom: Math.max(m.getZoom(), 15) });
      return;
    }
    const bounds = points.reduce((b, p) => b.extend(p), new maplibregl.LngLatBounds(points[0], points[0]));
    m.fitBounds(bounds, { padding: 48, maxZoom: 16, duration: 400 });
  }, [markers, lines, fit]);

  return <div ref={container} className="map" style={{ height }} />;
}

function toCollection(lines: MapLine[]): maplibregl.GeoJSONSourceSpecification['data'] {
  return {
    type: 'FeatureCollection',
    features: lines
      .filter((l) => l.coordinates.length > 1)
      .map((l) => ({
        type: 'Feature' as const,
        properties: { id: l.id, color: l.color },
        geometry: { type: 'LineString' as const, coordinates: l.coordinates },
      })),
  };
}
