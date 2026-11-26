import { Bar, BarChart, CartesianGrid, Tooltip, XAxis, YAxis } from 'recharts';
import { formatNumber } from '../../lib/format';

export interface BarDatum {
  name: string;
  value: number;
}

/**
 * Tek ölçülü yatay olmayan çubuk grafik. Sayıların kendisi tabloda; grafik
 * büyüklük farkını bir bakışta gösterir. Ekran okuyucu için başlık ve değerler
 * ayrıca listelenir (SVG içeriği okunmaz).
 */
export function BarMetricChart({ title, unit, data }: { title: string; unit: string; data: BarDatum[] }) {
  const label = (v: number) => `${formatNumber(Math.round(v * 100) / 100)} ${unit}`;
  return (
    <figure className="card" style={{ margin: 0 }} aria-label={title}>
      <figcaption><b>{title}</b></figcaption>
      <BarChart data={data} responsive style={{ width: '100%', height: 220 }} margin={{ top: 12, right: 8, bottom: 0, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" vertical={false} />
        <XAxis dataKey="name" tick={{ fontSize: 12 }} interval={0} />
        <YAxis width="auto" tick={{ fontSize: 12 }} />
        <Tooltip formatter={(v) => label(Number(v))} />
        <Bar dataKey="value" name={title} fill="#1f5fa8" radius={[4, 4, 0, 0]} />
      </BarChart>
      <ul className="sr-only">
        {data.map((d) => <li key={d.name}>{d.name}: {label(d.value)}</li>)}
      </ul>
    </figure>
  );
}
