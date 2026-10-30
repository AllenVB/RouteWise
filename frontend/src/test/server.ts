import { setupServer } from 'msw/node';

export const server = setupServer();

/** Backend'in ortak hata gövdesi (ApiError). */
export function apiError(code: string, message: string, fieldErrors: { field: string; message: string }[] = []) {
  return { code, message, fieldErrors, traceId: 'test', timestamp: '2026-10-30T10:00:00Z' };
}
