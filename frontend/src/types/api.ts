import { z } from 'zod';

/**
 * API sözleşmesinin istemci tarafı — docs/api-contract.md ve backend DTO'larıyla birebir.
 *
 * Yanıtlar zod ile ayrıştırılır: backend bir alanı değiştirirse hata ekranda
 * "undefined" olarak değil, hangi alanın uymadığını söyleyen bir istisna olarak görünür.
 */

export const roleSchema = z.enum(['COMPANY', 'COURIER', 'RECIPIENT']);
export type Role = z.infer<typeof roleSchema>;

export const verificationStatusSchema = z.enum(['TASLAK', 'DOGRULAMA_BEKLIYOR', 'DOGRULANDI', 'REDDEDILDI']);
export type VerificationStatus = z.infer<typeof verificationStatusSchema>;

export const shipmentStatusSchema = z.enum(['BEKLEMEDE', 'PLANLANDI', 'YOLDA', 'TESLIM_EDILDI', 'ERTELENDI']);
export type ShipmentStatus = z.infer<typeof shipmentStatusSchema>;

export const apiErrorSchema = z.object({
  code: z.string(),
  message: z.string(),
  fieldErrors: z.array(z.object({ field: z.string(), message: z.string() })).default([]),
  traceId: z.string().optional(),
});
export type ApiErrorBody = z.infer<typeof apiErrorSchema>;

export const tokenResponseSchema = z.object({
  accessToken: z.string(),
  refreshToken: z.string(),
  expiresInSeconds: z.number(),
  role: roleSchema,
  displayName: z.string(),
});
export type TokenResponse = z.infer<typeof tokenResponseSchema>;

export function pageSchema<T extends z.ZodTypeAny>(item: T) {
  return z.object({
    content: z.array(item),
    page: z.number(),
    size: z.number(),
    totalElements: z.number(),
    totalPages: z.number(),
  });
}
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// --- kargo ---

export const shipmentSchema = z.object({
  id: z.number(),
  orderNumber: z.string(),
  recipient: z.object({ id: z.number(), fullName: z.string().nullable() }),
  address: z.object({
    id: z.number(),
    text: z.string().nullable(),
    verificationStatus: verificationStatusSchema.nullable(),
  }),
  weightKg: z.number(),
  priority: z.number(),
  serviceTimeMin: z.number(),
  status: shipmentStatusSchema,
  postponedTo: z.string().nullable(),
  createdAt: z.string(),
});
export type Shipment = z.infer<typeof shipmentSchema>;

export const recipientSummarySchema = z.object({
  id: z.number(),
  fullName: z.string(),
  email: z.string(),
  phone: z.string().nullable(),
  defaultAddressId: z.number().nullable(),
});
export type RecipientSummary = z.infer<typeof recipientSummarySchema>;

export const recipientAddressSchema = z.object({
  id: z.number(),
  addressText: z.string(),
  district: z.string().nullable(),
  neighbourhood: z.string().nullable(),
  lat: z.number(),
  lon: z.number(),
  verificationStatus: verificationStatusSchema,
  usableForRouting: z.boolean(),
  isDefault: z.boolean(),
});
export type RecipientAddress = z.infer<typeof recipientAddressSchema>;

// --- adres doğrulama ---

export const addressSchema = z.object({
  id: z.number(),
  recipientId: z.number(),
  recipientName: z.string().nullable(),
  addressText: z.string(),
  district: z.string().nullable(),
  neighbourhood: z.string().nullable(),
  lat: z.number(),
  lon: z.number(),
  snappedNodeId: z.number().nullable(),
  snapDistanceM: z.number().nullable(),
  verificationStatus: verificationStatusSchema,
  rejectionReason: z.string().nullable(),
  createdAt: z.string(),
});
export type Address = z.infer<typeof addressSchema>;

export const validationResultSchema = z.object({
  withinServiceArea: z.boolean(),
  snappedNodeId: z.number().nullable(),
  snapDistanceM: z.number().nullable(),
  reverseGeocoded: z.string().nullable(),
  distanceToNearestM: z.number().nullable(),
  outlier: z.boolean(),
  acceptable: z.boolean(),
  warnings: z.array(z.string()),
  blockers: z.array(z.string()),
});
export type ValidationResult = z.infer<typeof validationResultSchema>;

// --- planlama ---

export const planSchema = z.object({
  id: z.number(),
  date: z.string(),
  status: z.enum(['TASLAK', 'SEVK_EDILDI', 'TAMAMLANDI']),
  createdAt: z.string(),
});
export type Plan = z.infer<typeof planSchema>;

export const algorithmSchema = z.enum(['NAIVE', 'GREEDY_NN', 'SAVINGS', 'ROUTEWISE', 'DFS_EXHAUSTIVE']);
export type Algorithm = z.infer<typeof algorithmSchema>;

export const excludedShipmentSchema = z.object({
  shipmentId: z.number(),
  orderNumber: z.string().nullable(),
  reason: z.enum(['ADDRESS_NOT_VERIFIED', 'NO_ROAD_NODE', 'UNREACHABLE']),
});
export type ExcludedShipment = z.infer<typeof excludedShipmentSchema>;

export const optimizeResponseSchema = z.object({
  runId: z.number(),
  algorithm: algorithmSchema,
  isApplied: z.boolean(),
  totalDistanceM: z.number(),
  totalDurationS: z.number(),
  vehicleCount: z.number(),
  computationTimeMs: z.number(),
  /** Gerçek para: yakıt + sürücü + araç sabit gideri. */
  totalCostTry: z.number().nullable(),
  totalFuelL: z.number().nullable(),
  /** Amaçtaki gecikme cezası — para değildir, maliyete eklenmez. */
  priorityPenalty: z.number().nullable(),
  randomSeed: z.number().nullable(),
  baseline: z.object({
    runId: z.number(),
    algorithm: algorithmSchema,
    totalDistanceM: z.number(),
    totalDurationS: z.number(),
    totalCostTry: z.number().nullable(),
  }),
  /** TL maliyet üzerinden, naive baseline'a göre. */
  savingsPct: z.number().nullable(),
  distanceSavingsPct: z.number().nullable(),
  routes: z.array(z.object({ id: z.number(), vehicleId: z.number(), stopCount: z.number() })),
  excluded: z.array(excludedShipmentSchema),
});
export type OptimizeResponse = z.infer<typeof optimizeResponseSchema>;

const positionSchema = z.tuple([z.number(), z.number()]);   // [boylam, enlem]

export const runDetailSchema = z.object({
  run: z.object({
    runId: z.number(),
    algorithm: algorithmSchema,
    isApplied: z.boolean(),
    totalDistanceM: z.number(),
    totalDurationS: z.number(),
    totalCostTry: z.number().nullable(),
    totalFuelL: z.number().nullable(),
    randomSeed: z.number().nullable(),
  }),
  depot: z.object({ name: z.string(), lat: z.number(), lon: z.number() }),
  routes: z.array(z.object({
    id: z.number(),
    vehicleId: z.number(),
    plate: z.string().nullable(),
    totalDistanceM: z.number(),
    totalDurationS: z.number(),
    totalLoadKg: z.number(),
    totalFuelL: z.number().nullable(),
    totalCostTry: z.number().nullable(),
    stops: z.array(z.object({
      id: z.number(),
      sequenceNo: z.number(),
      shipmentId: z.number(),
      orderNumber: z.string().nullable(),
      addressText: z.string().nullable(),
      lat: z.number().nullable(),
      lon: z.number().nullable(),
      distanceFromPrevM: z.number(),
      durationFromPrevS: z.number(),
    })),
    geometry: z.object({ type: z.literal('LineString'), coordinates: z.array(positionSchema) }).nullable(),
  })),
  geometryAvailable: z.boolean(),
});
export type RunDetail = z.infer<typeof runDetailSchema>;

export const dispatchResultSchema = z.object({
  planId: z.number(),
  status: z.enum(['TASLAK', 'SEVK_EDILDI', 'TAMAMLANDI']),
  runId: z.number(),
  routes: z.array(z.object({
    routeId: z.number(),
    vehicleId: z.number(),
    courierId: z.number(),
    courierName: z.string().nullable(),
    stopCount: z.number(),
  })),
});
export type DispatchResult = z.infer<typeof dispatchResultSchema>;
