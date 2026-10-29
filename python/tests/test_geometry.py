"""`/internal/route-geometry` uç testleri — küçük, koordinatlı graf ile."""

from __future__ import annotations

import numpy as np
import pytest
from fastapi.testclient import TestClient

import app.main as main
from app.graph.loader import load_graph

client = TestClient(main.app)

#  1 --100m-- 2 --100m-- 3     ve   1 ----250m---- 3      4 --100m-- 5 (kopuk)
KENARLAR = [  # (gid, kaynak, hedef, uzunluk_m, sure_s)
    (1, 1, 2, 100.0, 10.0),
    (2, 2, 3, 100.0, 10.0),
    (3, 1, 3, 250.0, 25.0),
    (4, 4, 5, 100.0, 10.0),
]
KOORDINAT = {1: (27.0, 40.0), 2: (27.001, 40.0), 3: (27.002, 40.0),
             4: (28.0, 41.0), 5: (28.001, 41.0)}


@pytest.fixture(autouse=True)
def graf(tmp_path, monkeypatch):
    dugumler = sorted(KOORDINAT)
    path = tmp_path / "g.npz"
    np.savez_compressed(
        path,
        edge_gid=np.array([k[0] for k in KENARLAR], dtype=np.int64),
        edge_source=np.array([k[1] for k in KENARLAR], dtype=np.int64),
        edge_target=np.array([k[2] for k in KENARLAR], dtype=np.int64),
        edge_length_m=np.array([k[3] for k in KENARLAR]),
        edge_cost=np.array([k[3] for k in KENARLAR]),
        edge_reverse_cost=np.array([k[3] for k in KENARLAR]),
        edge_cost_s=np.array([k[4] for k in KENARLAR]),
        edge_reverse_cost_s=np.array([k[4] for k in KENARLAR]),
        node_id=np.array(dugumler, dtype=np.int64),
        node_lon=np.array([KOORDINAT[n][0] for n in dugumler]),
        node_lat=np.array([KOORDINAT[n][1] for n in dugumler]),
    )
    monkeypatch.setattr(main, "_graph", load_graph(path))


def post(dugumler):
    return client.post("/internal/route-geometry", json={"nodeIds": dugumler})


def test_bacak_en_kisa_yolu_izler():
    """1 -> 3 doğrudan kenar 250 m; çizim 2 üzerinden giden 200 m'lik yolu izlemeli."""
    r = post([1, 3, 1])
    assert r.status_code == 200, r.text
    b = r.json()
    assert len(b["legs"]) == 2
    git = b["legs"][0]
    assert (git["fromNodeId"], git["toNodeId"]) == (1, 3)
    assert git["lengthM"] == 200.0
    assert git["coordinates"] == [[27.0, 40.0], [27.001, 40.0], [27.002, 40.0]]
    assert b["totalLengthM"] == 400.0


def test_ayni_dugum_tek_noktali_bacak():
    leg = post([2, 2]).json()["legs"][0]
    assert leg["lengthM"] == 0.0
    assert leg["coordinates"] == [[27.001, 40.0]]


def test_kopuk_parcaya_yol_yoksa_422():
    r = post([1, 4])
    assert r.status_code == 422
    assert "yol yok" in r.json()["detail"]


def test_bilinmeyen_dugum_422():
    assert post([1, 999]).status_code == 422


def test_tek_dugum_422():
    assert post([1]).status_code == 422
