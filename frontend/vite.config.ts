import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    // Container dışından erişilebilsin.
    host: '0.0.0.0',
    port: 5173,
    watch: {
      // Windows + Docker bind-mount: dosya değişiklik olayları container'a
      // iletilmez, bu olmadan hot reload hiç tetiklenmez.
      usePolling: true,
      interval: 300,
    },
    proxy: {
      // Tarayıcı aynı kaynaktan istek atsın; CORS yapılandırmasına gerek kalmaz.
      // Yol yeniden yazılmaz: sözleşmedeki /api/v1 öneki backend'de de aynıdır.
      '/api/v1': {
        target: process.env.BACKEND_ORIGIN ?? 'http://backend:8080',
        changeOrigin: true,
      },
      // Actuator ve Swagger kökte durur — sağlık kontrolü oraya bakar.
      '/actuator': {
        target: process.env.BACKEND_ORIGIN ?? 'http://backend:8080',
        changeOrigin: true,
      },
    },
  },
});
