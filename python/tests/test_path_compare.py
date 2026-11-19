"""
Seviye 1 — A*, BFS, DFS ve karşılaştırma ucu.

Küçük, koordinatlı graflarla: kenar uzunlukları uçları arasındaki kuş uçuşu
mesafeden kısa değildir (gerçek yol ağında olduğu gibi), A*'ın sezgiseli bu
yüzden kabul edilebilirdir.
"""

from __future__ import annotations

import math
import random

import numpy as np
import pytest
from fastapi.testclient import TestClient

import app.main as main
from app.graph.loader import load_graph
from app.pathfinding import astar, bfs, dfs, dijkstra
from app.pathfinding.base import Weight
from app.pathfinding.compare import compare_paths

client = TestClient(main.app)

LAT = 40.0
STEP = 0.001                       # boylam adımı; 40° enlemde ~85 m
STEP_M = 2 * 6_371_008.8 * math.asin(math.cos(math.radians(LAT)) * math.sin(math.radians(STEP) / 2))


def build(tmp_path, edges, coords, name="g.npz"):
    """edges: (gid, kaynak, hedef, uzunluk_m, süre_s), iki yönlü."""
    nodes = sorted(coords)
    path = tmp_path / name
    np.savez_compressed(
        path,
        edge_gid=np.array([e[0] for e in edges], dtype=np.int64),
        edge_source=np.array([e[1] for e in edges], dtype=np.int64),
        edge_target=np.array([e[2] for e in edges], dtype=np.int64),
        edge_length_m=np.array([e[3] for e in edges], dtype=float),
        edge_cost=np.array([e[3] for e in edges], dtype=float),
        edge_reverse_cost=np.array([e[3] for e in edges], dtype=float),
        edge_cost_s=np.array([e[4] for e in edges], dtype=float),
        edge_reverse_cost_s=np.array([e[4] for e in edges], dtype=float),
        node_id=np.array(nodes, dtype=np.int64),
        node_lon=np.array([coords[n][0] for n in nodes]),
        node_lat=np.array([coords[n][1] for n in nodes]),
    )
    return load_graph(path)


def line_graph(tmp_path):
    """
    Batıda 5 düğümlük çıkmaz kol, doğuda hedefe giden 5 düğümlük yol; kaynak 0, hedef 15.

        5 - 4 - 3 - 2 - 1 - [0] - 11 - 12 - 13 - 14 - [15]
    """
    coords = {0: (27.0, LAT)}
    edges = []
    gid = 1
    prev = 0
    for k, node in enumerate([1, 2, 3, 4, 5], start=1):
        coords[node] = (27.0 - k * STEP, LAT)
        edges.append((gid, prev, node, 90.0, 9.0)); gid += 1
        prev = node
    prev = 0
    for k, node in enumerate([11, 12, 13, 14, 15], start=1):
        coords[node] = (27.0 + k * STEP, LAT)
        edges.append((gid, prev, node, 90.0, 9.0)); gid += 1
        prev = node
    return build(tmp_path, edges, coords)


def shortcut_graph(tmp_path):
    """
    1 --100-- 2 --100-- 3 --100-- 4   ve   1 ------- 900 m ------- 4

    En kısa yol 1-2-3-4 (300 m, 3 kenar); en az kenarlı yol 1-4 (900 m, 1 kenar).
    """
    coords = {1: (27.0, LAT), 2: (27.001, LAT), 3: (27.002, LAT), 4: (27.003, LAT)}
    edges = [(1, 1, 2, 100.0, 10.0), (2, 2, 3, 100.0, 10.0), (3, 3, 4, 100.0, 10.0),
             (4, 1, 4, 900.0, 60.0)]
    return build(tmp_path, edges, coords)


def grid_graph(tmp_path, n=12, seed=7):
    """n×n ızgara, kenar uzunluğu kuş uçuşunun 1–2 katı, rastgele hız."""
    rng = random.Random(seed)
    coords = {r * n + c: (27.0 + c * STEP, LAT + r * STEP * 0.77) for r in range(n) for c in range(n)}
    edges = []
    gid = 1
    for r in range(n):
        for c in range(n):
            u = r * n + c
            for v in ([u + 1] if c + 1 < n else []) + ([u + n] if r + 1 < n else []):
                (lo1, la1), (lo2, la2) = coords[u], coords[v]
                chord = haversine(la1, lo1, la2, lo2)
                length = chord * rng.uniform(1.0, 2.0)
                edges.append((gid, u, v, length, length / rng.uniform(5.0, 20.0)))
                gid += 1
    return build(tmp_path, edges, coords, "grid.npz")


def haversine(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6_371_008.8 * math.asin(math.sqrt(a))


def assert_valid_path(graph, r, s, t):
    """Yol kaynakta başlar, hedefte biter ve her adımı gerçek bir kenardır."""
    assert r.path[0] == s and r.path[-1] == t
    total = 0.0
    for a, b in zip(r.path, r.path[1:]):
        nb, ln, _ = graph.neighbors_of(graph.index_of(a))
        hits = [float(x) for x, y in zip(ln, nb) if int(y) == graph.index_of(b)]
        assert hits, f"kenar yok: {a} -> {b}"
        total += min(hits)
    assert r.length_m == pytest.approx(total)


class TestAStar:
    def test_dijkstra_ile_ayni_yolu_daha_az_dugum_gezerek_bulur(self, tmp_path):
        g = line_graph(tmp_path)
        d = dijkstra.shortest_path(g, 0, 15)
        a = astar.shortest_path(g, 0, 15)

        assert a.path == d.path == [0, 11, 12, 13, 14, 15]
        assert a.length_m == pytest.approx(d.length_m) == pytest.approx(450.0)
        assert a.nodes_visited == 6          # yalnızca doğu kolu
        assert d.nodes_visited > a.nodes_visited

    @pytest.mark.parametrize("weight", [Weight.DISTANCE, Weight.DURATION])
    def test_rastgele_izgarada_her_cift_icin_optimum(self, tmp_path, weight):
        g = grid_graph(tmp_path)
        rng = random.Random(3)
        nodes = [int(x) for x in g.node_ids]
        for _ in range(40):
            s, t = rng.sample(nodes, 2)
            d = dijkstra.shortest_path(g, s, t, weight)
            a = astar.shortest_path(g, s, t, weight)
            key = "length_m" if weight is Weight.DISTANCE else "duration_s"
            assert getattr(a, key) == pytest.approx(getattr(d, key))
            assert a.nodes_visited <= d.nodes_visited
            assert_valid_path(g, a, s, t)

    def test_kaynak_hedef_ayniysa_bos_yol(self, tmp_path):
        r = astar.shortest_path(line_graph(tmp_path), 12, 12)
        assert r.found and r.path == [12] and r.length_m == 0.0


class TestBfs:
    def test_agirligi_yok_sayar_en_az_kenarli_yolu_bulur(self, tmp_path):
        g = shortcut_graph(tmp_path)
        r = bfs.find_path(g, 1, 4)
        assert r.path == [1, 4]
        assert r.length_m == pytest.approx(900.0)
        assert r.duration_s == pytest.approx(60.0)

    def test_izgarada_hicbir_zaman_dijkstradan_fazla_kenar_kullanmaz(self, tmp_path):
        g = grid_graph(tmp_path)
        rng = random.Random(5)
        nodes = [int(x) for x in g.node_ids]
        for _ in range(30):
            s, t = rng.sample(nodes, 2)
            b = bfs.find_path(g, s, t)
            d = dijkstra.shortest_path(g, s, t)
            assert len(b.path) <= len(d.path)
            assert b.length_m >= d.length_m - 1e-6
            assert_valid_path(g, b, s, t)


class TestDfs:
    def test_gecerli_bir_yol_bulur_optimum_garanti_etmez(self, tmp_path):
        g = grid_graph(tmp_path)
        rng = random.Random(11)
        nodes = [int(x) for x in g.node_ids]
        longer = 0
        for _ in range(30):
            s, t = rng.sample(nodes, 2)
            r = dfs.find_path(g, s, t)
            d = dijkstra.shortest_path(g, s, t)
            assert_valid_path(g, r, s, t)
            assert r.length_m >= d.length_m - 1e-6
            longer += r.length_m > d.length_m * 1.01
        assert longer > 0          # en az bir çiftte dolanarak gider

    def test_ayni_girdide_ayni_yol(self, tmp_path):
        g = grid_graph(tmp_path)
        assert dfs.find_path(g, 0, 143).path == dfs.find_path(g, 0, 143).path

    def test_derin_yolda_ozyineleme_siniri_yok(self, tmp_path):
        coords = {i: (27.0 + i * STEP, LAT) for i in range(3000)}
        edges = [(i + 1, i, i + 1, 90.0, 9.0) for i in range(2999)]
        g = build(tmp_path, edges, coords, "uzun.npz")
        r = dfs.find_path(g, 0, 2999)
        assert r.found and len(r.path) == 3000


class TestCompare:
    def test_sapma_dijkstraya_gore(self, tmp_path):
        runs = {r.algorithm: r for r in compare_paths(shortcut_graph(tmp_path), 1, 4, repeats=3)}

        assert list(runs) == ["DIJKSTRA", "ASTAR", "BFS", "DFS"]
        assert runs["DIJKSTRA"].deviation_pct == 0.0
        assert runs["ASTAR"].deviation_pct == pytest.approx(0.0)
        assert runs["ASTAR"].same_path_as_reference
        assert runs["BFS"].deviation_pct == pytest.approx(200.0)    # 900 m / 300 m
        assert not runs["BFS"].same_path_as_reference

    def test_sure_agirliginda_sapma_suresi_uzerinden(self, tmp_path):
        runs = {r.algorithm: r for r in compare_paths(shortcut_graph(tmp_path), 1, 4, Weight.DURATION, 1)}
        assert runs["BFS"].deviation_pct == pytest.approx(100.0)    # 60 s / 30 s

    def test_yol_yoksa_sapma_yok(self, tmp_path):
        coords = {1: (27.0, LAT), 2: (27.001, LAT), 3: (28.0, LAT), 4: (28.001, LAT)}
        g = build(tmp_path, [(1, 1, 2, 100.0, 10.0), (2, 3, 4, 100.0, 10.0)], coords)
        for r in compare_paths(g, 1, 4, repeats=1):
            assert not r.result.found
            assert r.deviation_pct is None


class TestCompareApi:
    @pytest.fixture(autouse=True)
    def graf(self, tmp_path, monkeypatch):
        monkeypatch.setattr(main, "_graph", shortcut_graph(tmp_path))

    def test_dort_algoritma_ayni_girdiyle(self):
        r = client.post("/internal/compare/path", json={"fromNodeId": 1, "toNodeId": 4, "repeats": 2})
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["weight"] == "distance" and body["repeats"] == 2
        by = {x["algorithm"]: x for x in body["results"]}
        assert by["DIJKSTRA"]["path"] == [1, 2, 3, 4]
        assert by["DIJKSTRA"]["edgeCount"] == 3
        assert by["DIJKSTRA"]["coordinates"][0] == [27.0, 40.0]
        assert by["BFS"]["deviationPct"] == 200.0
        assert by["BFS"]["lengthM"] == 900.0
        assert by["ASTAR"]["samePathAsReference"] is True
        assert all(x["computationTimeMs"] >= 0 for x in body["results"])

    def test_bilinmeyen_dugum_422(self):
        assert client.post("/internal/compare/path", json={"fromNodeId": 1, "toNodeId": 99}).status_code == 422

    def test_tekrar_siniri_422(self):
        r = client.post("/internal/compare/path", json={"fromNodeId": 1, "toNodeId": 4, "repeats": 51})
        assert r.status_code == 422

    def test_bilinmeyen_alan_422(self):
        r = client.post("/internal/compare/path", json={"fromNodeId": 1, "toNodeId": 4, "algoritma": "x"})
        assert r.status_code == 422
