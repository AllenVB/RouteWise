import type { ShipmentStatus, VerificationStatus } from '../types/api';

const km = new Intl.NumberFormat('tr-TR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const num = new Intl.NumberFormat('tr-TR', { maximumFractionDigits: 1 });

export const formatKm = (meters: number) => `${km.format(meters / 1000)} km`;
export const formatNumber = (n: number) => num.format(n);

const tl = new Intl.NumberFormat('tr-TR', { style: 'currency', currency: 'TRY', maximumFractionDigits: 0 });
export const formatTry = (n: number) => tl.format(n);

/** Rota renkleri — araç sırasıyla; harita çizgisi, işaretçi ve liste aynı rengi kullanır. */
export const ROUTE_COLORS = ['#1f5fa8', '#c2410c', '#15803d', '#7c3aed', '#b91c1c', '#0e7490', '#a16207', '#be185d'];
export const routeColor = (index: number) => ROUTE_COLORS[index % ROUTE_COLORS.length] ?? '#1f5fa8';

export const algorithmLabel: Record<string, string> = {
  ROUTEWISE: 'RouteWise',
  SAVINGS: 'Clarke-Wright',
  GREEDY_NN: 'En yakın komşu',
  NAIVE: 'Geliş sırası',
};

export function formatDuration(seconds: number): string {
  const total = Math.round(seconds / 60);
  const h = Math.floor(total / 60);
  const m = total % 60;
  return h > 0 ? `${h} sa ${m} dk` : `${m} dk`;
}

/** Saat:dakika, depo saatiyle — tarayıcı başka dilimde olsa da kurye Bandırma saatini görür. */
export function formatClock(iso: string): string {
  return new Date(iso).toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Istanbul' });
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('tr-TR', { dateStyle: 'short', timeStyle: 'short' });
}

/** Rozet metni ve tonu — durum adları ekranda hiç ham haliyle görünmez. */
export const verificationLabel: Record<VerificationStatus, { text: string; tone: string }> = {
  TASLAK: { text: 'Taslak', tone: '' },
  DOGRULAMA_BEKLIYOR: { text: 'Doğrulama bekliyor', tone: 'warn' },
  DOGRULANDI: { text: 'Doğrulandı', tone: 'ok' },
  REDDEDILDI: { text: 'Reddedildi', tone: 'danger' },
};

export const shipmentLabel: Record<ShipmentStatus, { text: string; tone: string }> = {
  BEKLEMEDE: { text: 'Beklemede', tone: 'info' },
  PLANLANDI: { text: 'Planlandı', tone: 'ok' },
  YOLDA: { text: 'Yolda', tone: 'warn' },
  TESLIM_EDILDI: { text: 'Teslim edildi', tone: 'ok' },
  ERTELENDI: { text: 'Ertelendi', tone: 'danger' },
};

export const excludedReasonLabel: Record<string, string> = {
  ADDRESS_NOT_VERIFIED: 'Adres onaylı değil ya da onaydan sonra değişti',
  NO_ROAD_NODE: 'Adres onaylı ama yol ağına bağlanmamış',
  UNREACHABLE: 'Depodan bu noktaya gidiş-dönüş yolu yok',
  EXCEEDS_WORKDAY: 'Tek başına bile çalışma gününe sığmıyor',
};

/** Tarayıcının yerel günü — UTC değil; Bandırma'da gün 03:00'te dönmesin. */
export function todayLocal(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
