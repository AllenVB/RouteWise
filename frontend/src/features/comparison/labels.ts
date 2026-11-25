import type { PathAlgorithm, TourAlgorithm } from './api';

export const pathLabel: Record<PathAlgorithm, string> = {
  DIJKSTRA: 'Dijkstra',
  ASTAR: 'A*',
  BFS: 'BFS',
  DFS: 'DFS',
};

export const tourLabel: Record<TourAlgorithm, string> = {
  NAIVE: 'Geliş sırası',
  GREEDY_NN: 'En yakın komşu',
  SAVINGS: 'Clarke-Wright',
  ROUTEWISE: 'RouteWise',
  DFS_EXHAUSTIVE: 'Kesin arama (DFS)',
};
