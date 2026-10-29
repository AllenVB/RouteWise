"""
`/internal/optimize` uç testleri — backend ile sözleşme.

Graf gerekmez: matris istekle gelir. TestClient bağlam yöneticisiz kurulur,
böylece açılışta graf yükleme (lifespan) çalışmaz.
"""

from __future__ import annotations

import math

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.main import app
from app.schemas.optimize import MatrixIn

client = TestClient(app)

# Düğüm -> x konumu (x100 m). Depo 10; kargolar: 501@13 (x=5), 502@11 (x=1), 503@12 (x=3)
X = {10: 0, 11: 1, 12: 3, 13: 5}


def matris(dugumler=None, *, kopuk=()):
    """Doğru üzerinde mesafe; `kopuk` düğümlerine giden ve onlardan gelen yol yok."""
    ids = sorted(dugumler or X)

    def hucre(a, b, olcek):
        if a != b and (a in kopuk or b in kopuk):
            return None
        return abs(X[a] - X[b]) * olcek

    return {
        "nodeIds": ids,
        "distanceM": [[hucre(a, b, 100.0) for b in ids] for a in ids],
        "durationS": [[hucre(a, b, 10.0) for b in ids] for a in ids],
    }


def istek(**degisen):
    govde = {
        "algorithm": "GREEDY_NN",
        "depotNodeId": 10,
        "stops": [
            {"id": 501, "nodeId": 13, "demandKg": 2.0},
            {"id": 502, "nodeId": 11, "demandKg": 3.0},
            {"id": 503, "nodeId": 12, "demandKg": 4.0},
        ],
        "vehicles": [{"id": 1, "capacityKg": 100.0}],
        "matrix": matris(),
    }
    govde.update(degisen)
    return govde


def post(govde):
    return client.post("/internal/optimize", json=govde)


# --- başarılı yol ---

def test_nn_sirasi_ve_baseline_birlikte_doner():
    r = post(istek())
    assert r.status_code == 200, r.text
    b = r.json()

    sonuc = b["result"]
    assert sonuc["algorithm"] == "GREEDY_NN"
    duraklar = sonuc["routes"][0]["stops"]
    assert [d["stopId"] for d in duraklar] == [502, 503, 501]
    assert [d["sequenceNo"] for d in duraklar] == [1, 2, 3]
    assert [d["distanceFromPrevM"] for d in duraklar] == [100.0, 200.0, 200.0]
    assert sonuc["routes"][0]["returnDistanceM"] == 500.0
    assert sonuc["totalDistanceM"] == 1000.0
    assert sonuc["vehicleCount"] == 1

    assert b["baseline"]["algorithm"] == "NAIVE"
    assert b["baseline"]["totalDistanceM"] == 1400.0     # 500 + 400 + 200 + 300
    assert b["unassigned"] == []


def test_durak_dugum_kimligi_yanitta_doner():
    """Backend geometri isteğini bu düğümlerle kurar."""
    duraklar = post(istek()).json()["result"]["routes"][0]["stops"]
    assert [d["nodeId"] for d in duraklar] == [11, 12, 13]


def test_toplam_sure_servis_suresini_icerir():
    stops = [{**s, "serviceTimeS": 60.0} for s in istek()["stops"]]
    rota = post(istek(stops=stops)).json()["result"]["routes"][0]
    assert rota["travelTimeS"] == 100.0
    assert rota["serviceTimeS"] == 180.0
    assert rota["totalDurationS"] == 280.0
    assert rota["totalLoadKg"] == 9.0


def test_naive_istenirse_gelis_sirasi():
    duraklar = post(istek(algorithm="NAIVE")).json()["result"]["routes"][0]["stops"]
    assert [d["stopId"] for d in duraklar] == [501, 502, 503]


def test_ulasilamayan_durak_rapor_edilir_rotaya_girmez():
    b = post(istek(matrix=matris(kopuk={13}))).json()
    assert b["unassigned"] == [{"stopId": 501, "reason": "UNREACHABLE"}]
    for cozum in (b["result"], b["baseline"]):
        assert 501 not in [d["stopId"] for d in cozum["routes"][0]["stops"]]


# --- iş kuralı: 409 ---

def test_kapasite_asilirsa_409():
    r = post(istek(vehicles=[{"id": 1, "capacityKg": 5.0}]))
    assert r.status_code == 409
    assert r.json()["detail"]["code"] == "NO_FEASIBLE_SOLUTION"
    assert "kapasite" in r.json()["detail"]["message"]


def test_hicbir_duraga_yol_yoksa_409():
    r = post(istek(matrix=matris(kopuk={11, 12, 13})))
    assert r.status_code == 409
    assert r.json()["detail"]["code"] == "NO_FEASIBLE_SOLUTION"


# --- biçimsiz girdi: 422 ---

@pytest.mark.parametrize("degisen", [
    pytest.param({"depotNodeId": 99}, id="depo-matriste-yok"),
    pytest.param({"stops": [{"id": 1, "nodeId": 77, "demandKg": 1.0}]}, id="durak-matriste-yok"),
    pytest.param({"stops": [{"id": 1, "nodeId": 11, "demandKg": 1.0},
                            {"id": 1, "nodeId": 12, "demandKg": 1.0}]}, id="tekrar-eden-kargo"),
    pytest.param({"stops": [{"id": 1, "nodeId": 11, "demandKg": 0}]}, id="sifir-yuk"),
    pytest.param({"stops": []}, id="bos-durak-listesi"),
    pytest.param({"vehicles": [{"id": 1, "capacityKg": 50.0},
                               {"id": 2, "capacityKg": 50.0}]}, id="iki-arac-faz2-disi"),
    pytest.param({"algorithm": "ROUTEWISE"}, id="henuz-yazilmamis-algoritma"),
])
def test_bicimsiz_girdi_422(degisen):
    assert post(istek(**degisen)).status_code == 422


def test_kare_olmayan_matris_422():
    m = matris()
    m["distanceM"] = m["distanceM"][:-1]
    assert post(istek(matrix=m)).status_code == 422


def test_negatif_mesafe_422():
    m = matris()
    m["distanceM"][0][1] = -5.0
    assert post(istek(matrix=m)).status_code == 422


def test_nan_mesafe_422():
    """
    Jackson NaN'ı varsayılan olarak "NaN" metni olarak yazar; pydantic bunu
    float('nan')'a çevirir. NaN karşılaştırmaları hep False döndüğü için en
    yakın komşu seçimini sessizce bozardı.
    """
    m = matris()
    m["distanceM"][0][1] = "NaN"
    assert post(istek(matrix=m)).status_code == 422


@pytest.mark.parametrize("deger", [math.nan, math.inf])
def test_matris_dogrulayicisi_nan_ve_sonsuzu_reddeder(deger):
    """Doğrudan model düzeyinde: hangi yoldan gelirse gelsin algoritmaya ulaşmaz."""
    m = matris()
    m["durationS"][1][2] = deger
    with pytest.raises(ValidationError, match="sonlu"):
        MatrixIn.model_validate(m)
