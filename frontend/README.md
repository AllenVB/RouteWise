# frontend/

React 19 · TypeScript (strict) · Vite · MapLibre GL

**Durum: boş — Faz 1a'da kurulacak.**

```
src/
  lib/          apiClient · authStore · queryClient
  types/        zod şemalarından türetilen API tipleri
  components/   ui/ map/ charts/ layout/
  features/
    auth/ shipments/ addresses/ verification/
    planning/ routes/ comparison/ reports/
    courier/ recipient/
```

## Windows + Docker notu

Container içinde hot reload **kendiliğinden çalışmaz**: bind-mount dosya
olayları Windows'tan Linux container'a iletilmez. `vite.config.ts` içinde
şunlar zorunludur:

```ts
server: {
  host: '0.0.0.0',
  watch: { usePolling: true },
}
```

Ekran envanteri (19 ekran, üç rol): [../docs/roadmap.md](../docs/roadmap.md) — Faz 1b
