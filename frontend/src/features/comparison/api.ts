import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { api } from '../../lib/apiClient';
import { excludedShipmentSchema } from '../../types/api';

const positionSchema = z.tuple([z.number(), z.number()]);   // [boylam, enlem]

// --- Seviye 1: yol bulma -------------------------------------------------------

export const pathAlgorithmSchema = z.enum(['DIJKSTRA', 'ASTAR', 'BFS', 'DFS']);
export type PathAlgorithm = z.infer<typeof pathAlgorithmSchema>;

const endSchema = z.object({
  nodeId: z.number(),
  lat: z.number(),
  lon: z.number(),
  snapDistanceM: z.number().nullable().optional(),
});

export const pathComparisonSchema = z.object({
  id: z.number(),
  level: z.literal('PATH'),
  createdAt: z.string(),
  from: endSchema,
  to: endSchema,
  weight: z.enum(['DISTANCE', 'DURATION']),
  repeats: z.number(),
  results: z.array(z.object({
    algorithm: pathAlgorithmSchema,
    found: z.boolean(),
    lengthM: z.number(),
    durationS: z.number(),
    edgeCount: z.number(),
    nodesVisited: z.number(),
    computationTimeMs: z.number(),
    deviationPct: z.number().nullable(),
    samePathAsReference: z.boolean(),
    path: z.array(z.number()),
    coordinates: z.array(positionSchema),
  })),
});
export type PathComparison = z.infer<typeof pathComparisonSchema>;

export interface PathCompareBody {
  from: { lat: number; lon: number };
  to: { lat: number; lon: number };
  weight: 'DISTANCE' | 'DURATION';
  repeats: number;
}

export function useComparePath() {
  return useMutation({
    mutationFn: (body: PathCompareBody) =>
      api('/compare/path', { method: 'POST', body, schema: pathComparisonSchema }),
  });
}

// --- Seviye 2: tur sırası ------------------------------------------------------

export const tourAlgorithmSchema = z.enum(['NAIVE', 'GREEDY_NN', 'SAVINGS', 'ROUTEWISE', 'DFS_EXHAUSTIVE']);
export type TourAlgorithm = z.infer<typeof tourAlgorithmSchema>;

const solutionSchema = z.object({
  totalDistanceM: z.number(),
  totalDurationS: z.number(),
  totalCostTry: z.number().nullable(),
  priorityPenalty: z.number().nullable(),
  objective: z.number(),
});

export const tourComparisonSchema = z.object({
  id: z.number(),
  level: z.literal('TOUR'),
  createdAt: z.string(),
  planId: z.number(),
  vehicleId: z.number(),
  stopCount: z.number(),
  repeats: z.number(),
  seeds: z.array(z.number()),
  referenceAlgorithm: tourAlgorithmSchema.nullable(),
  referenceIsOptimal: z.boolean(),
  exhaustiveSkipped: z.string().nullable(),
  results: z.array(z.object({
    algorithm: tourAlgorithmSchema,
    feasible: z.boolean(),
    error: z.string().nullable(),
    solution: solutionSchema.nullable(),
    computationTimeMs: z.number(),
    gapPct: z.number().nullable(),
    randomSeed: z.number().nullable(),
    objectiveMin: z.number().nullable(),
    objectiveMax: z.number().nullable(),
    explored: z.number().nullable(),
  })),
  unassigned: z.array(z.object({ stopId: z.number(), reason: z.string() })),
  excluded: z.array(excludedShipmentSchema),
});
export type TourComparison = z.infer<typeof tourComparisonSchema>;

export interface TourCompareBody {
  vehicleId?: number;
  shipmentIds?: number[];
  repeats: number;
}

export function useCompareTour(planId: number | undefined) {
  return useMutation({
    mutationFn: (body: TourCompareBody) =>
      api(`/plans/${planId}/compare/tour`, { method: 'POST', body, schema: tourComparisonSchema }),
  });
}

const vehicleSchema = z.object({
  id: z.number(),
  plate: z.string(),
  capacityKg: z.number(),
});
export type Vehicle = z.infer<typeof vehicleSchema>;

export function useVehicles() {
  return useQuery({
    queryKey: ['vehicles'],
    queryFn: () => api('/vehicles', { schema: z.array(vehicleSchema) }),
    staleTime: 5 * 60_000,
  });
}

// --- Deney: N sentetik senaryo + anlamlılık testi ---------------------------------

export const experimentSchema = z.object({
  id: z.number(),
  status: z.enum(['QUEUED', 'RUNNING', 'DONE', 'FAILED']),
  scenarioCount: z.number(),
  stopCount: z.number(),
  radiusM: z.number(),
  repeats: z.number(),
  baseSeed: z.number(),
  vehicleId: z.number().nullable(),
  completed: z.number(),
  failed: z.number(),
  lastError: z.string().nullable(),
  createdAt: z.string(),
  startedAt: z.string().nullable(),
  finishedAt: z.string().nullable(),
  summary: z.object({
    usableScenarios: z.number(),
    alpha: z.number(),
    algorithms: z.array(z.object({
      algorithm: tourAlgorithmSchema,
      scenarios: z.number(),
      medianGapPct: z.number(),
      meanGapPct: z.number(),
      maxGapPct: z.number(),
      optimalCount: z.number(),
      medianTimeMs: z.number(),
    })),
    tests: z.array(z.object({
      candidate: tourAlgorithmSchema,
      reference: tourAlgorithmSchema,
      n: z.number(),
      candidateBetter: z.number(),
      ties: z.number(),
      candidateWorse: z.number(),
      meanDiffPct: z.number(),
      wilcoxonP: z.number(),
      tP: z.number(),
      significant: z.boolean(),
      note: z.string().nullable(),
    })),
    claim: z.string(),
  }).nullable(),
  scenarios: z.array(z.object({ comparisonId: z.number(), seed: z.number() })).nullable(),
});
export type Experiment = z.infer<typeof experimentSchema>;

const isActive = (e: Experiment | undefined) => e?.status === 'QUEUED' || e?.status === 'RUNNING';

/** Çalışan deney varken ilerleme sık sorulur; bitince sorgu durur. */
export const EXPERIMENT_POLL_MS = 3_000;

export function useExperiments() {
  return useQuery({
    queryKey: ['experiments'],
    queryFn: () => api('/compare/experiments', { schema: z.array(experimentSchema) }),
    refetchInterval: (q) => (q.state.data?.some((e) => isActive(e)) ? EXPERIMENT_POLL_MS : false),
  });
}

export interface ExperimentBody {
  scenarios: number;
  stopCount: number;
  radiusM: number;
  repeats: number;
  seed?: number;
}

export function useStartExperiment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ExperimentBody) =>
      api('/compare/experiments', { method: 'POST', body, schema: experimentSchema }),
    onSettled: () => qc.invalidateQueries({ queryKey: ['experiments'] }),
  });
}
