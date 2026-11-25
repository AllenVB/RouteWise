import type { z } from 'zod';
import { apiErrorSchema, tokenResponseSchema, type ApiErrorBody } from '../types/api';
import { authStore } from './authStore';

const BASE = '/api/v1';

/** Sunucunun `ApiError` gövdesi. Ekran `code`'a göre dallanır, mesajı olduğu gibi gösterir. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fieldErrors: ApiErrorBody['fieldErrors'] = [],
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

interface RequestOptions<T> {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  schema?: z.ZodType<T, unknown>;
}

export async function api<T = void>(path: string, opts: RequestOptions<T> = {}): Promise<T> {
  let res = await send(path, opts);
  if (res.status === 401 && authStore.get() && (await refreshOnce())) {
    res = await send(path, opts);
  }
  if (!res.ok) throw await toError(res);
  if (res.status === 204 || !opts.schema) return undefined as T;
  return opts.schema.parse(await res.json());
}

/**
 * Dosya indirir (ör. Excel/PDF rapor): aynı oturum ve token yenileme akışı, gövde
 * JSON değil dosyanın kendisi. Dosya adı sunucunun `Content-Disposition` başlığından.
 */
export async function apiFile(path: string, fallbackName: string): Promise<{ blob: Blob; filename: string }> {
  let res = await send(path, { accept: '*/*' });
  if (res.status === 401 && authStore.get() && (await refreshOnce())) {
    res = await send(path, { accept: '*/*' });
  }
  if (!res.ok) throw await toError(res);
  const disposition = res.headers.get('Content-Disposition') ?? '';
  const filename = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? fallbackName;
  return { blob: await res.blob(), filename };
}

/** Tarayıcıya dosyayı kaydettirir; geçici bağlantı hemen bırakılır. */
export function saveFile(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

function send(path: string, { method = 'GET', body, accept = 'application/json' }: RequestOptions<unknown> & { accept?: string }): Promise<Response> {
  const headers: Record<string, string> = { Accept: accept };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const token = authStore.get()?.accessToken;
  if (token) headers.Authorization = `Bearer ${token}`;
  return fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
}

async function toError(res: Response): Promise<ApiError> {
  try {
    const parsed = apiErrorSchema.safeParse(await res.json());
    if (parsed.success) {
      const e = parsed.data;
      return new ApiError(res.status, e.code, e.message, e.fieldErrors);
    }
  } catch {
    // gövde JSON değil (ör. proxy hatası) — aşağıdaki genel hata
  }
  return new ApiError(res.status, 'HTTP_' + res.status, 'Sunucuya ulaşılamadı. Biraz sonra tekrar dene.');
}

/**
 * Refresh token dönüşümlüdür: her kullanımda yenisi verilir, eskisi geçersizleşir.
 * Aynı anda düşen iki 401 ayrı ayrı yenilemeye kalkarsa ikincisi geçersiz tokenla
 * gider ve kullanıcı sebepsiz yere oturumdan atılır. Bu yüzden süren yenileme paylaşılır.
 */
let inflight: Promise<boolean> | null = null;

function refreshOnce(): Promise<boolean> {
  inflight ??= doRefresh().finally(() => {
    inflight = null;
  });
  return inflight;
}

async function doRefresh(): Promise<boolean> {
  const session = authStore.get();
  if (!session) return false;
  const res = await fetch(BASE + '/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ refreshToken: session.refreshToken }),
  });
  if (!res.ok) {
    authStore.signOut();
    return false;
  }
  authStore.signIn(tokenResponseSchema.parse(await res.json()));
  return true;
}
