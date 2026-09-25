"""
批量图像处理器

把一批图像分块送入 WildlifeDetector 做 batch inference，
并逐张把识别结果回写结果队列（ResultCallback）。

处理链路：
    task_consumer 消费到任务
        → BatchProcessor.process(taskId, images)
        → 按 batch_size 分块
        → 分块内逐张读图（读失败的单独记失败，不拖累同块其他图像）
        → detector.detect_batch(整块)  一次推理
        → 逐张 callback.send_image_result / send_image_failed

也可以不传 callback，直接把逐张结果收集在返回值里（供同步接口使用）。
"""
import itertools
import logging
import os
import time
from dataclasses import asdict, dataclass, field
from typing import Any, Dict, Iterator, List, Optional

from app.services.result_callback import ResultCallback
from app.services.wildlife_detector import (
    WildlifeDetector,
    get_wildlife_detector,
    read_image,
)

logger = logging.getLogger(__name__)

DEFAULT_BATCH_SIZE = 8


@dataclass
class ImageOutcome:
    """单张图像的处理结果。"""

    imageId: Optional[int] = None
    filePath: str = ""
    status: str = "SUCCESS"
    detectionCount: int = 0
    detections: List[Dict[str, Any]] = field(default_factory=list)
    errorMessage: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class BatchSummary:
    """一次批量处理的汇总。"""

    taskId: int
    totalCount: int = 0
    successCount: int = 0
    failedCount: int = 0
    elapsedMs: int = 0
    results: List[ImageOutcome] = field(default_factory=list)

    @property
    def avgMsPerImage(self) -> float:
        return round(self.elapsedMs / self.totalCount, 2) if self.totalCount else 0.0

    def to_dict(self, with_results: bool = True) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "taskId": self.taskId,
            "totalCount": self.totalCount,
            "successCount": self.successCount,
            "failedCount": self.failedCount,
            "elapsedMs": self.elapsedMs,
            "avgMsPerImage": self.avgMsPerImage,
        }
        if with_results:
            payload["results"] = [outcome.to_dict() for outcome in self.results]
        return payload


class BatchProcessor:
    """批量图像识别编排器。"""

    def __init__(
        self,
        detector: Optional[WildlifeDetector] = None,
        callback: Optional[ResultCallback] = None,
        batch_size: Optional[int] = None,
    ):
        self.detector = detector or get_wildlife_detector()
        self.callback = callback
        self.batch_size = batch_size or int(os.getenv("BATCH_SIZE", str(DEFAULT_BATCH_SIZE)))
        if self.batch_size < 1:
            self.batch_size = 1

    # ── 主流程 ──────────────────────────────────────────────────────────────

    def process(
        self,
        task_id: int,
        images: List[Any],
        version: Optional[str] = None,
        collect_results: bool = False,
    ) -> BatchSummary:
        """
        处理一批图像。

        images 中每项为 `{imageId, filePath}`，也接受直接给路径字符串。
        collect_results=True 时把逐张结果收集进 summary.results
        （大批量任务不要打开，避免把 10 万条结果全部留在内存里）。
        """
        items = [self._normalize(item) for item in images]
        summary = BatchSummary(taskId=task_id, totalCount=len(items))

        started = time.perf_counter()
        self._notify_task_started(task_id)

        for chunk_index, chunk in enumerate(self._chunks(items, self.batch_size), start=1):
            outcomes = self._process_chunk(task_id, chunk, version)
            for outcome in outcomes:
                if outcome.status == "SUCCESS":
                    summary.successCount += 1
                else:
                    summary.failedCount += 1
                if collect_results:
                    summary.results.append(outcome)

            logger.debug(
                "Task %d chunk %d/%d done: %d success, %d failed",
                task_id,
                chunk_index,
                (len(items) + self.batch_size - 1) // self.batch_size,
                sum(1 for outcome in outcomes if outcome.status == "SUCCESS"),
                sum(1 for outcome in outcomes if outcome.status != "SUCCESS"),
            )

        summary.elapsedMs = int((time.perf_counter() - started) * 1000)
        logger.info(
            "Task %d batch done: %d/%d success, %d failed, %d ms (avg %.1f ms/image)",
            task_id,
            summary.successCount,
            summary.totalCount,
            summary.failedCount,
            summary.elapsedMs,
            summary.avgMsPerImage,
        )
        return summary

    def _process_chunk(
        self,
        task_id: int,
        chunk: List[Dict[str, Any]],
        version: Optional[str],
    ) -> List[ImageOutcome]:
        """处理一个分块：先读图，再整块推理，最后逐张回写。"""
        outcomes: List[ImageOutcome] = []
        prepared: List[Dict[str, Any]] = []

        for item in chunk:
            file_path = item["filePath"]
            try:
                prepared.append({"item": item, "image": read_image(file_path)})
            except Exception as exc:  # noqa: BLE001 - 读图失败只算这一张失败
                outcomes.append(self._fail(task_id, item, exc))

        if not prepared:
            return outcomes

        detections_per_image = self.detector.detect_batch(
            [entry["image"] for entry in prepared], version=version
        )

        for entry, detections in zip(prepared, detections_per_image):
            outcomes.append(self._succeed(task_id, entry["item"], detections))

        return outcomes

    # ── 结果回写 ────────────────────────────────────────────────────────────

    def _succeed(
        self,
        task_id: int,
        item: Dict[str, Any],
        detections: List[Any],
    ) -> ImageOutcome:
        payload = [detection.to_dict() for detection in detections]
        if self.callback is not None:
            try:
                self.callback.send_image_result(task_id, item["imageId"], payload)
            except Exception as exc:  # noqa: BLE001 - 回写失败不能中断整批
                logger.error(
                    "Failed to report result of image %s: %s", item["imageId"], exc
                )
        return ImageOutcome(
            imageId=item["imageId"],
            filePath=item["filePath"],
            status="SUCCESS",
            detectionCount=len(payload),
            detections=payload,
        )

    def _fail(
        self,
        task_id: int,
        item: Dict[str, Any],
        exc: Exception,
    ) -> ImageOutcome:
        logger.warning("Task %d image %s failed: %s", task_id, item["imageId"], exc)
        if self.callback is not None:
            try:
                self.callback.send_image_failed(task_id, item["imageId"], str(exc))
            except Exception as send_error:  # noqa: BLE001
                logger.error(
                    "Failed to report image %s failure: %s", item["imageId"], send_error
                )
        return ImageOutcome(
            imageId=item["imageId"],
            filePath=item["filePath"],
            status="FAILED",
            errorMessage=str(exc),
        )

    def _notify_task_started(self, task_id: int) -> None:
        if self.callback is None:
            return
        try:
            self.callback.send_task_started(task_id)
        except Exception as exc:  # noqa: BLE001
            logger.error("Failed to report task %s start: %s", task_id, exc)

    # ── 工具 ────────────────────────────────────────────────────────────────

    @staticmethod
    def _normalize(item: Any) -> Dict[str, Any]:
        """统一成 {imageId, filePath}；也接受直接给路径字符串。"""
        if isinstance(item, str):
            return {"imageId": None, "filePath": item}
        if isinstance(item, dict):
            file_path = item.get("filePath") or item.get("imagePath") or ""
            return {"imageId": item.get("imageId"), "filePath": file_path}
        # pydantic 模型等对象：按属性读取
        return {
            "imageId": getattr(item, "imageId", None),
            "filePath": getattr(item, "filePath", "") or "",
        }

    @staticmethod
    def _chunks(items: List[Any], size: int) -> Iterator[List[Any]]:
        iterator = iter(items)
        while True:
            chunk = list(itertools.islice(iterator, size))
            if not chunk:
                return
            yield chunk
