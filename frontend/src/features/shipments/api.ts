import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { api } from '../../lib/apiClient';
import {
  pageSchema,
  recipientAddressSchema,
  recipientSummarySchema,
  shipmentSchema,
  type ShipmentStatus,
} from '../../types/api';

export interface ShipmentFilter {
  status?: ShipmentStatus;
  q?: string;
  page: number;
}

export function useShipments(f: ShipmentFilter) {
  const params = new URLSearchParams({ page: String(f.page), size: '25', sort: 'createdAt,desc' });
  if (f.status) params.set('status', f.status);
  if (f.q?.trim()) params.set('q', f.q.trim());
  return useQuery({
    queryKey: ['shipments', f],
    queryFn: () => api(`/shipments?${params}`, { schema: pageSchema(shipmentSchema) }),
    placeholderData: keepPreviousData,
  });
}

export function useDeleteShipment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api(`/shipments/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['shipments'] }),
  });
}

export function useRecipientSearch(q: string) {
  return useQuery({
    queryKey: ['recipients', q],
    queryFn: () =>
      api(`/recipients?${new URLSearchParams({ q, size: '10' })}`, { schema: pageSchema(recipientSummarySchema) }),
    placeholderData: keepPreviousData,
  });
}

export function useRecipientAddresses(recipientId: number | null) {
  return useQuery({
    queryKey: ['recipients', recipientId, 'addresses'],
    queryFn: () => api(`/recipients/${recipientId}/addresses`, { schema: z.array(recipientAddressSchema) }),
    enabled: recipientId !== null,
  });
}

export interface CreateShipmentBody {
  recipientId: number;
  addressId: number;
  weightKg: number;
  priority: number;
  serviceTimeMin: number;
}

export function useCreateShipment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateShipmentBody) =>
      api('/shipments', { method: 'POST', body, schema: shipmentSchema }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['shipments'] }),
  });
}
