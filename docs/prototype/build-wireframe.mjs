/**
 * Prototipten PDF wireframe üretir.
 *
 * Tek kaynak ilkesi: ekranları yeniden çizmez, prototipin kendi render
 * fonksiyonlarını kullanır. Prototip değişince wireframe de değişir.
 *
 *   node docs/prototype/build-wireframe.mjs
 *   chrome --headless --print-to-pdf=... wireframe.html
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = path.dirname(fileURLToPath(import.meta.url));
const src = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');

const styleBlocks = [...src.matchAll(/<style>([\s\S]*?)<\/style>/g)].map((m) => m[1]);
const scriptBlocks = [...src.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
if (!styleBlocks.length || !scriptBlocks.length) throw new Error('index.html ayrıştırılamadı');

// Yönlendirme ve açılış kodunu at: wireframe DOM kabuğunu kullanmaz.
const logic = scriptBlocks
  .join('\n')
  .split('/* ============ yönlendirme ============ */')[0];

// Ekran notları — her sayfanın altındaki açıklama.
const NOT = {
  F1: 'Üç rol aynı giriş ekranını paylaşır; rol JWT içindeki yetkiden çözülür.',
  F2: 'Özet önce, detay sonra. "Dikkat gerektirenler" doğrudan ilgili ekrana götürür.',
  F3: 'Listede değişiklik rota yeniden hesaplamasını tetikler (~500 ms debounce).',
  F4: 'Sipariş numarası salt okunur: sistem üretir, elle girilmez.',
  F5: 'Beş katmanlı kontrolün insan onayı adımı. Yola 142 m uzaktaki nokta onaylanamaz.',
  F6: 'Optimizasyon asenkron koşar; ilerleme göstergesi arayüzü kilitlemez.',
  F7: 'Durak sırası, ETA ve kapasite kullanımı bir arada.',
  F8: 'İki karar seviyesi ayrı tablolarda — metrikleri farklıdır.',
  F9: 'Excel ve PDF dışa aktarma. Grafik haftalık kırılım; aylık/yıllık da aynı veriden.',
  K1: 'Kurye girişi.',
  K2: 'Kuryenin ana ekranı. Sıradaki durak vurgulu, tamamlananlar işaretli.',
  K3: 'Teslim/ulaşılamadı işaretlemesi burada yapılır; sistem konumu buradan bilir.',
  K4: 'Harita görünümü — rota ve sıradaki durak.',
  K5: 'Rota değişince neyin değiştiği açıkça yazılır; sessiz değişiklik yapılmaz.',
  A1: 'Alıcı girişi.',
  A2: 'Alıcının kargo listesi ve tahmini teslim saatleri.',
  A3: '"Başka gün gelsin" ve adres değiştirme buradan.',
  A4: 'Adres değişikliğinin sonucu açıkça uyarılır: yeniden doğrulama gerekir.',
  A5: 'Uygulama içi ve e-posta bildirimleri.',
};

const page = (rolAd, renk, kod, ad, govde, not) => `
<section class="wf">
  <header class="wfhead">
    <span class="wfrole" style="--r:${renk}">${rolAd}</span>
    <span class="wfcode">${kod}</span>
    <h2>${ad}</h2>
  </header>
  <div class="wfbox"><div class="wfinner"><div class="screen">${govde}</div></div></div>
  <p class="wfnote">${not ?? ''}</p>
</section>`;

// Basılı wireframe daima açık temada. Prototipin dark bloğu
// :root:not([data-theme="light"]) ile korunduğu için bu öznitelik yeterli.
const html = `<!doctype html>
<html lang="tr" data-theme="light"><head><meta charset="utf-8">
<title>RouteWise Ekran Wireframe</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wght@600;700&family=IBM+Plex+Mono:wght@400;500&family=IBM+Plex+Sans:wght@400;500;600&display=swap">
<style>
${styleBlocks.join('\n')}

/* ---- wireframe sayfa düzeni ---- */
@page { size: A4 landscape; margin: 0; }
html, body { height: auto; background: #fff; }
.wf {
  width: 297mm; height: 208mm; padding: 11mm 13mm 8mm;
  display: flex; flex-direction: column; overflow: hidden;
  page-break-after: always; break-after: page; background: #fff;
}
.wf:last-child { page-break-after: auto; break-after: auto; }
.wfhead {
  display: flex; align-items: baseline; gap: 9px; flex: 0 0 auto;
  border-bottom: 1.5px solid #16202c; padding-bottom: 5px; margin-bottom: 7px;
}
.wfrole {
  font-family: var(--f-body); font-size: 9.5pt; font-weight: 600; color: #fff;
  background: var(--r); border-radius: 4px; padding: 2px 8px;
}
.wfcode { font-family: var(--f-mono); font-size: 10pt; font-weight: 500; color: #1f5fa8; }
.wfhead h2 { font-size: 15pt; font-weight: 600; color: #16202c; }
.wfbox { flex: 1 1 auto; min-height: 0; overflow: hidden; }
.wfinner { width: 1080px; }
.wfinner .screen { padding: 0; max-width: 1080px; }
.wfnote {
  flex: 0 0 auto; margin: 7px 0 0; padding-top: 5px; border-top: 1px solid #d9e0ea;
  font-family: var(--f-body); font-size: 8.5pt; color: #5b6b7d; line-height: 1.45;
}
/* Wireframe daima açık temada basılır. */
.wf, .wf * { color-scheme: light; }

/* Haritalar ekranda 16:10; basılı sayfada bu kadar yükseklik, ekranın
   tamamını okunmayacak kadar küçültüyor. Yüksekliği sabitliyoruz. */
.wf .map { aspect-ratio: auto; height: 190px; }
</style></head>
<body>
<script>
${logic}

const NOT = ${JSON.stringify(NOT)};
const page = ${page.toString()};
const sayfalar = [];
for (const [, r] of Object.entries(ROL)) {
  for (const [kod, ad] of r.ekranlar) {
    const fn = EKRAN[kod];
    sayfalar.push(page(r.ad, r.renk, kod, ad, fn ? fn() : '', NOT[kod]));
  }
}
document.body.insertAdjacentHTML('beforeend', sayfalar.join(''));

/* Her sayfayı kendi içeriğine göre ölçekle — uzun tablolar taşmasın. */
for (const box of document.querySelectorAll('.wfbox')) {
  const inner = box.querySelector('.wfinner');
  const sw = box.clientWidth / inner.scrollWidth;
  const sh = box.clientHeight / inner.scrollHeight;
  const s = Math.min(sw, sh, 1);
  /* zoom, transform'un aksine düzeni ölçekler: PDF'te metin metin kalır,
     kontura dönüşmez ve aranabilirliğini korur. */
  inner.style.zoom = s;
}
</script>
</body></html>`;

fs.writeFileSync(path.join(dir, 'wireframe.html'), html, 'utf8');
const n = (html.match(/class="wf"/g) || []).length;
console.log('wireframe.html yazıldı');
console.log('ekran sayısı (çalışma zamanında üretilir):', Object.keys(NOT).length);
