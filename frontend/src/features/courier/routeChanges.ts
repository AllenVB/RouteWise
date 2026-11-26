import type { RouteChange } from './api';

/** Tek satırlık özet: rota ekranındaki "Rota değişti" bandı. */
export function changeSummary(c: RouteChange): string {
  const parts: string[] = [];
  if (c.removed.length > 0) parts.push(`${c.removed.length} durak çıktı`);
  if (c.moved.length > 0) parts.push(`${c.moved.length} durağın adresi değişti`);
  if (c.reordered.length > 0) parts.push(`${c.reordered.length} durağın sırası değişti`);
  if (parts.length === 0) parts.push('sıra aynı kaldı');
  const head = parts.join(', ');
  const tail = c.orderKept ? ' Bugünkü değişiklik limiti doldu; kalan sıran korunuyor.' : '';
  return `${head[0]?.toUpperCase() ?? ''}${head.slice(1)} · ${c.openStops} açık durak kaldı.${tail}`;
}

const SEEN_KEY = 'routewise.courier.changesSeen';

/**
 * Kuryenin gördüğü son değişiklik — yalnızca bu cihazda bandı kapatmak için.
 * Depolama kapalıysa (gizli pencere) bant her açılışta yine görünür; kayıp yok.
 */
export function seenChangeId(routeId: number): number {
  try {
    const raw = localStorage.getItem(`${SEEN_KEY}.${routeId}`);
    const n = raw === null ? 0 : Number(raw);
    return Number.isFinite(n) ? n : 0;
  } catch {
    return 0;
  }
}

export function markChangesSeen(routeId: number, changeId: number): void {
  try {
    localStorage.setItem(`${SEEN_KEY}.${routeId}`, String(changeId));
  } catch {
    // depolama yok: bant bir sonraki açılışta yine görünür
  }
}
