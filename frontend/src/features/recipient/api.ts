import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { api } from '../../lib/apiClient';
import { shipmentStatusSchema, stopStatusSchema } from '../../types/api';

export const myShipmentSchema = z.object({
  id: z.number(),
  orderNumber: z.string(),
  status: shipmentStatusSchema,
  addressText: z.string().nullable(),
  weightKg: z.number(),
  createdAt: z.string(),
  postponedTo: z.string().nullable(),
  /** Sevk edilmiş rotadaki planlanan varış; sevkten önce null. */
  eta: z.string().nullable(),
  stopStatus: stopStatusSchema.nullable(),
  actualArrival: z.string().nullable(),
  failureReason: z.string().nullable(),
});
export type MyShipment = z.infer<typeof myShipmentSchema>;

export const notificationSchema = z.object({
  id: z.number(),
  type: z.enum(['ETA', 'ERTELEME', 'DOGRULAMA', 'ROTA_DEGISTI']),
  shipmentId: z.number().nullable(),
  payload: z.record(z.string(), z.unknown()).nullable(),
  createdAt: z.string(),
  read: z.boolean(),
});
export type AppNotification = z.infer<typeof notificationSchema>;

const inboxSchema = z.object({ items: z.array(notificationSchema), unreadCount: z.number() });

export function useMyShipments() {
  return useQuery({ queryKey: ['me', 'shipments'], queryFn: () => api('/me/shipments', { schema: z.array(myShipmentSchema) }) });
}

export function useMyShipment(id: number) {
  return useQuery({ queryKey: ['me', 'shipments', id], queryFn: () => api(`/me/shipments/${id}`, { schema: myShipmentSchema }) });
}

/** Menüdeki okunmamış sayısı da buradan gelir; dakikada bir tazelenir. */
export function useInbox() {
  return useQuery({
    queryKey: ['me', 'notifications'],
    queryFn: () => api('/me/notifications', { schema: inboxSchema }),
    refetchInterval: 60_000,
  });
}

export function useMarkRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { ids: number[] } | { all: true }) => api('/me/notifications/read', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['me', 'notifications'] }),
  });
}

/** "Başka gün gelsin" (A3). Kargo yoldaysa durak kuryenin rotasından çıkar. */
export function usePostpone(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (date: string) =>
      api(`/me/shipments/${id}/postpone`, { method: 'POST', body: { date }, schema: myShipmentSchema }),
    onSuccess: (shipment) => {
      qc.setQueryData(['me', 'shipments', id], shipment);
      void qc.invalidateQueries({ queryKey: ['me', 'shipments'], exact: true });
    },
  });
}
