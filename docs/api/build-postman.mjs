/**
 * API sözleşmesinden Postman collection üretir.
 *
 *   node docs/api/build-postman.mjs
 *
 * Çıktı ~/Docs/2. Postman Collections/ altına yazılır.
 * Environment dosyası ÜRETİLMEZ: tüm değişkenler collection'ın kendi `variable`
 * bloğundadır, collection tek başına çalışır.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const J = (o) => ({ mode: 'raw', raw: JSON.stringify(o, null, 2),
  options: { raw: { language: 'json' } } });

/** [metot, yol, ad, gövde?, sorgu?] */
const GRUPLAR = [
  ['Kimlik', [
    ['POST', '/auth/login', 'Giriş', { email: 'firma@routewise.local', password: '{{parola}}' }],
    ['POST', '/auth/refresh', 'Token yenile', { refreshToken: '{{refreshToken}}' }],
    ['POST', '/auth/logout', 'Çıkış'],
    ['GET', '/auth/me', 'Oturum sahibi'],
  ]],
  ['Firma · Kargo', [
    ['GET', '/dashboard', 'Gösterge paneli', null, { date: '{{tarih}}' }],
    ['GET', '/shipments', 'Kargo listesi', null,
      { page: '0', size: '25', sort: 'createdAt,desc', status: '', q: '' }],
    ['POST', '/shipments', 'Kargo oluştur',
      { recipientId: 12, addressId: 31, weightKg: 4.2, priority: 2, serviceTimeMin: 5 }],
    ['GET', '/shipments/{{kargoId}}', 'Kargo detayı'],
    ['PATCH', '/shipments/{{kargoId}}', 'Kargo güncelle', { weightKg: 5.0, priority: 1 }],
    ['DELETE', '/shipments/{{kargoId}}', 'Kargo sil'],
    ['GET', '/recipients', 'Alıcı ara', null, { q: 'Elif' }],
    ['GET', '/recipients/{{aliciId}}/addresses', 'Alıcının adresleri'],
  ]],
  ['Firma · Adres doğrulama', [
    ['GET', '/addresses', 'Doğrulama kuyruğu', null, { status: 'DOGRULAMA_BEKLIYOR' }],
    ['GET', '/addresses/{{adresId}}', 'Adres detayı'],
    ['POST', '/addresses/{{adresId}}/verify', 'Adresi onayla'],
    ['POST', '/addresses/{{adresId}}/reject', 'Adresi reddet',
      { reason: 'Nokta yola 142 m uzakta, araç ulaşamaz.' }],
  ]],
  ['Firma · Planlama', [
    ['GET', '/plans', 'Günün planı', null, { date: '{{tarih}}' }],
    ['POST', '/plans', 'Plan aç', { date: '{{tarih}}' }],
    ['POST', '/plans/{{planId}}/optimize', 'Optimize et',
      { algorithm: 'ROUTEWISE', randomSeed: 42 }],
    ['GET', '/plans/{{planId}}/runs', 'Plana ait koşular'],
    ['GET', '/runs/{{kosuId}}', 'Koşu detayı'],
    ['POST', '/plans/{{planId}}/dispatch', 'Kuryelere sevk et'],
    ['GET', '/routes/{{rotaId}}', 'Rota detayı'],
    ['GET', '/vehicles', 'Filo'],
  ]],
  ['Firma · Karşılaştırma ve analiz', [
    ['POST', '/plans/{{planId}}/compare/tour', 'Seviye 2 — tur sırası',
      { algorithms: ['NAIVE', 'GREEDY_NN', 'SAVINGS', 'ROUTEWISE'], repeats: 10 }],
    ['POST', '/compare/path', 'Seviye 1 — yol bulma',
      { fromNodeId: 48219, toNodeId: 51004,
        algorithms: ['DIJKSTRA', 'ASTAR', 'BFS', 'DFS'] }],
    ['GET', '/comparisons/{{karsilastirmaId}}', 'Karşılaştırma sonucu'],
    ['GET', '/analytics/savings', 'Tasarruf analizi', null,
      { period: 'WEEK', from: '2026-09-01', to: '2026-10-15' }],
    ['GET', '/analytics/export', 'Rapor dışa aktar', null, { format: 'XLSX', period: 'WEEK' }],
  ]],
  ['Kurye', [
    ['GET', '/courier/route', 'Bugünkü rotam', null, { date: '{{tarih}}' }],
    ['GET', '/courier/stops/{{durakId}}', 'Durak detayı'],
    ['POST', '/courier/stops/{{durakId}}/deliver', 'Teslim edildi'],
    ['POST', '/courier/stops/{{durakId}}/fail', 'Ulaşılamadı',
      { reason: 'Adreste kimse yok' }],
    ['GET', '/courier/route/changes', 'Rota değişiklikleri', null,
      { since: '2026-10-15T06:00:00Z' }],
  ]],
  ['Alıcı', [
    ['GET', '/me/shipments', 'Kargolarım'],
    ['GET', '/me/shipments/{{kargoId}}', 'Kargo detayı'],
    ['POST', '/me/shipments/{{kargoId}}/postpone', 'Başka gün gelsin',
      { date: '2026-10-17' }],
    ['GET', '/me/addresses', 'Adreslerim'],
    ['POST', '/me/addresses', 'Adres ekle',
      { text: 'Yeni Mahalle, 1204 Sk. No:7', lat: 40.3521, lon: 27.9767 }],
    ['PUT', '/me/addresses/{{adresId}}', 'Adresi güncelle',
      { text: 'Çınarlı Mah., 290 Sk. No:6', lat: 40.3488, lon: 27.9712 }],
    ['GET', '/me/notifications', 'Bildirimler'],
    ['POST', '/me/notifications/read', 'Okundu işaretle', { all: true }],
  ]],
  ['Ortak', [
    ['POST', '/addresses/validate', 'Konum doğrula (kaydetmeden)',
      { lat: 40.3521, lon: 27.9767 }],
    ['GET', '/system/status', 'Sistem durumu'],
  ]],
];

const istek = ([metot, yol, ad, govde, sorgu]) => {
  const parcalar = yol.replace(/^\//, '').split('/');
  const url = {
    raw: '{{baseUrl}}' + yol + (sorgu
      ? '?' + Object.entries(sorgu).map(([k, v]) => `${k}=${v}`).join('&') : ''),
    host: ['{{baseUrl}}'],
    path: parcalar,
  };
  if (sorgu) url.query = Object.entries(sorgu)
    .map(([key, value]) => ({ key, value: String(value), disabled: value === '' }));

  return {
    name: ad,
    request: {
      method: metot,
      header: [{ key: 'Accept', value: 'application/json' }]
        .concat(govde ? [{ key: 'Content-Type', value: 'application/json' }] : []),
      url,
      ...(govde ? { body: J(govde) } : {}),
      description: `Sözleşme: docs/api-contract.md · ${metot} ${yol}`,
    },
  };
};

const collection = {
  info: {
    name: 'RouteWise API',
    description:
      'Bandırma içi kargo dağıtımı — rota optimizasyon sistemi.\n\n' +
      'Uçlar ekran prototipinden türetilmiştir; sözleşme: docs/api-contract.md\n\n' +
      'Tüm değişkenler bu collection içindedir, ayrı environment dosyası gerekmez.\n' +
      'Giriş isteği başarılı olduğunda accessToken otomatik olarak doldurulur.',
    schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
  },
  auth: { type: 'bearer', bearer: [{ key: 'token', value: '{{accessToken}}', type: 'string' }] },
  event: [{
    listen: 'test',
    script: {
      type: 'text/javascript',
      exec: [
        '// Giriş yanıtındaki token\'ları collection değişkenlerine yaz.',
        'if (pm.request.url.getPath().endsWith("/auth/login") && pm.response.code === 200) {',
        '  const b = pm.response.json();',
        '  if (b.accessToken)  pm.collectionVariables.set("accessToken",  b.accessToken);',
        '  if (b.refreshToken) pm.collectionVariables.set("refreshToken", b.refreshToken);',
        '}',
      ],
    },
  }],
  variable: [
    { key: 'baseUrl', value: 'http://localhost:8090/api/v1', type: 'string' },
    { key: 'accessToken', value: '', type: 'string' },
    { key: 'refreshToken', value: '', type: 'string' },
    { key: 'parola', value: 'RouteWise!Dev2026', type: 'string' },
    { key: 'tarih', value: '2026-10-15', type: 'string' },
    { key: 'kargoId', value: '417', type: 'string' },
    { key: 'aliciId', value: '12', type: 'string' },
    { key: 'adresId', value: '31', type: 'string' },
    { key: 'planId', value: '88', type: 'string' },
    { key: 'kosuId', value: '312', type: 'string' },
    { key: 'rotaId', value: '901', type: 'string' },
    { key: 'durakId', value: '5021', type: 'string' },
    { key: 'karsilastirmaId', value: '77', type: 'string' },
  ],
  item: GRUPLAR.map(([ad, istekler]) => ({ name: ad, item: istekler.map(istek) })),
};

const hedef = path.join(os.homedir(), 'Docs', '2. Postman Collections');
fs.mkdirSync(hedef, { recursive: true });
const dosya = path.join(hedef, 'routewise.postman_collection.json');
fs.writeFileSync(dosya, JSON.stringify(collection, null, 2) + '\n', 'utf8');

const n = GRUPLAR.reduce((t, [, g]) => t + g.length, 0);
console.log('yazıldı :', dosya);
console.log('klasör  :', GRUPLAR.length);
console.log('istek   :', n);
