# -*- coding: utf-8 -*-
"""视觉渠道 OCR 能力（R2-03）

引擎链：PaddleOCR（中文识别优，设计选型）→ pytesseract（轻量备选）→ unavailable（如实上报，渠道降级为 DOWN）
Phase 2 验收口径：基础 OCR 返回文本即可（可接受较低准确率），不作为阻断项。
"""
from __future__ import annotations

import base64
import io
import logging
from importlib.util import find_spec
from shutil import which
import time
from dataclasses import dataclass
from typing import Optional

logger = logging.getLogger("nlp.ocr")


@dataclass
class OcrResult:
    text: str
    confidence: float
    engine: str
    latency_ms: float


class OcrEngine:
    name = "unavailable"

    def available(self) -> bool:
        return False

    def recognize(self, image_bytes: bytes) -> OcrResult:  # pragma: no cover - 抽象
        raise NotImplementedError


class PaddleOcrEngine(OcrEngine):
    name = "paddleocr"

    def __init__(self) -> None:
        self._engine = None

    def available(self) -> bool:
        return find_spec("paddleocr") is not None and find_spec("numpy") is not None and find_spec("PIL") is not None

    def _ensure_engine(self):
        if self._engine is None:
            from paddleocr import PaddleOCR  # type: ignore
            self._engine = PaddleOCR(use_angle_cls=False, lang="ch")
        return self._engine

    def recognize(self, image_bytes: bytes) -> OcrResult:
        import numpy as np  # type: ignore
        from PIL import Image  # type: ignore

        started = time.perf_counter()
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        result = self._ensure_engine().ocr(np.array(image))
        lines, scores = [], []
        for page in result or []:
            for entry in page or []:
                try:
                    lines.append(entry[1][0])
                    scores.append(float(entry[1][1]))
                except (IndexError, TypeError):
                    continue
        confidence = sum(scores) / len(scores) if scores else 0.0
        return OcrResult(text="\n".join(lines).strip(), confidence=confidence,
                         engine=self.name, latency_ms=(time.perf_counter() - started) * 1000)

class TesseractOcrEngine(OcrEngine):
    name = "tesseract"

    def __init__(self) -> None:
        self._module = None

    def available(self) -> bool:
        return find_spec("pytesseract") is not None and find_spec("PIL") is not None and which("tesseract") is not None

    def recognize(self, image_bytes: bytes) -> OcrResult:
        import pytesseract  # type: ignore
        from PIL import Image  # type: ignore

        started = time.perf_counter()
        image = Image.open(io.BytesIO(image_bytes))
        text = pytesseract.image_to_string(image, lang="chi_sim+eng")
        confidence = 0.6 if text.strip() else 0.0
        return OcrResult(text=text.strip(), confidence=confidence, engine=self.name,
                         latency_ms=(time.perf_counter() - started) * 1000)

class UnavailableOcrEngine(OcrEngine):
    name = "unavailable"

    def available(self) -> bool:
        return False

    def recognize(self, image_bytes: bytes) -> OcrResult:
        raise RuntimeError("no OCR engine installed (paddleocr / pytesseract)")


class OcrService:
    """引擎链调度：取第一个可用引擎"""

    def __init__(self) -> None:
        self.engines = [PaddleOcrEngine(), TesseractOcrEngine()]
        self.active: Optional[OcrEngine] = next((e for e in self.engines if e.available()), None)

    def status(self) -> dict:
        return {
            "available": self.active is not None,
            "engine": self.active.name if self.active else UnavailableOcrEngine.name,
            "candidates": [{"engine": e.name, "available": e.available()} for e in self.engines],
        }

    def recognize_base64(self, image_base64: str) -> OcrResult:
        if self.active is None:
            raise RuntimeError("no OCR engine installed (paddleocr / pytesseract)")
        try:
            payload = base64.b64decode(image_base64, validate=True)
        except Exception as exc:  # noqa: BLE001
            raise ValueError(f"invalid base64 image payload: {exc}") from exc
        if not payload:
            raise ValueError("empty image payload")
        if len(payload) > 8 * 1024 * 1024:
            raise ValueError("image exceeds 8MB limit")
        return self.active.recognize(payload)


OCR_SERVICE = OcrService()
