"""
Ortak şema tabanı — JSON'da camelCase, Python'da snake_case.

Bilinmeyen alan **reddedilir** (422). Varsayılan davranış sessizce atmaktır ve
bu bir kez gerçekten pahalıya patladı: otomatik alias `fuel_l_per_100km` için
`fuelLPer100Km` üretirken backend `fuelLPer100km` gönderiyordu; alan atıldı,
varsayılan 0 kullanıldı ve yakıt maliyeti hiç hesaba girmedi — hiçbir hata
vermeden. Sözleşme uyuşmazlığı sessiz bir sıfır değil, açık bir hata olmalı.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")
