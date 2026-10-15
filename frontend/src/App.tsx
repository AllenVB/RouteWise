import { useEffect, useState } from 'react';

/**
 * Faz 1a iskeleti.
 *
 * Tek işi var: backend ve Python servisine ulaşılabildiğini göstermek.
 * Gerçek ekranlar (19 ekran, üç rol) Faz 1b'de tasarlanacak.
 */

type Durum = 'yukleniyor' | 'ok' | 'hata';

interface ServisDurumu {
  durum: Durum;
  detay: string;
}

const BASLANGIC: ServisDurumu = { durum: 'yukleniyor', detay: '…' };

async function kontrolEt(url: string, ozet: (v: unknown) => string): Promise<ServisDurumu> {
  try {
    const r = await fetch(url);
    if (!r.ok) return { durum: 'hata', detay: `HTTP ${r.status}` };
    return { durum: 'ok', detay: ozet(await r.json()) };
  } catch (e) {
    return { durum: 'hata', detay: e instanceof Error ? e.message : 'bilinmeyen hata' };
  }
}

export function App() {
  const [backend, setBackend] = useState<ServisDurumu>(BASLANGIC);
  const [python, setPython] = useState<ServisDurumu>(BASLANGIC);

  useEffect(() => {
    void kontrolEt('/actuator/health', (v) => {
      const d = v as { status?: string };
      return `durum: ${d.status ?? '?'}`;
    }).then(setBackend);

    // Python servisine backend üzerinden ulaşılır — frontend doğrudan çağırmaz.
    void kontrolEt('/api/v1/system/status', (v) => {
      const d = v as { optimizer?: { graph?: { node_count?: number; road_km?: number } } };
      const g = d.optimizer?.graph;
      if (!g) return 'graf yüklenmedi';
      return `${g.node_count?.toLocaleString('tr')} düğüm · ${g.road_km} km`;
    }).then(setPython);
  }, []);

  return (
    <main style={styles.main}>
      <h1 style={styles.h1}>RouteWise</h1>
      <p style={styles.alt}>Faz 1a — iskelet. Servis bağlantıları doğrulanıyor.</p>

      <div style={styles.kartlar}>
        <Kart ad="Backend" alt="Spring Boot · PostgreSQL" durum={backend} />
        <Kart ad="Python servisi" alt="FastAPI · yol grafı" durum={python} />
      </div>
    </main>
  );
}

function Kart({ ad, alt, durum }: { ad: string; alt: string; durum: ServisDurumu }) {
  const renk = durum.durum === 'ok' ? '#2d7a4b' : durum.durum === 'hata' ? '#b3382c' : '#64748b';
  return (
    <section style={{ ...styles.kart, borderColor: renk }}>
      <div style={styles.kartBas}>
        <span style={{ ...styles.nokta, background: renk }} />
        <strong>{ad}</strong>
      </div>
      <div style={styles.kartAlt}>{alt}</div>
      <div style={{ ...styles.kartDetay, color: renk }}>{durum.detay}</div>
    </section>
  );
}

const styles: Record<string, React.CSSProperties> = {
  main: {
    fontFamily: 'Segoe UI, system-ui, sans-serif',
    color: '#1b2430',
    maxWidth: 720,
    margin: '0 auto',
    padding: '48px 24px',
  },
  h1: { fontSize: 34, margin: 0, color: '#1f5fa8', letterSpacing: '-0.02em' },
  alt: { color: '#64748b', marginTop: 6, fontSize: 14 },
  kartlar: { display: 'grid', gap: 14, marginTop: 28 },
  kart: { border: '2px solid', borderRadius: 10, padding: '14px 16px' },
  kartBas: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 15 },
  nokta: { width: 9, height: 9, borderRadius: '50%', display: 'inline-block' },
  kartAlt: { color: '#64748b', fontSize: 12.5, marginTop: 4 },
  kartDetay: { fontSize: 13.5, marginTop: 8, fontWeight: 600 },
};
