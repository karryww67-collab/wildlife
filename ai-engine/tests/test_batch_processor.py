"""BatchProcessor（批量图像推理编排）单元测试。

用假检测器替代 WildlifeDetector，用记录型回调替代 ResultCallback，
不触碰 Redis，也不加载真实权重；读图用的是临时目录里真实写出的 PNG，
不可读的图像直接给不存在的文件，从而自然触发读图失败分支。
"""
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from app.services.batch_processor import (
    BatchProcessor,
    BatchSummary,
    ImageOutcome,
)
from app.services.wildlife_detector import Detection


# ── 假对象 ──────────────────────────────────────────────────────────────────


class FakeDetector:
    """只实现 BatchProcessor 用到的 detect_batch。"""

    def __init__(self, detections=None):
        self.calls = []
        self._detections = (
            [Detection(classId=0, className="野猪", confidence=0.91, bbox=[10, 20, 110, 220])]
            if detections is None
            else list(detections)
        )

    def detect_batch(self, images, version=None, **kwargs):
        self.calls.append({"imageCount": len(images), "version": version})
        return [list(self._detections) for _ in images]


class RecordingCallback:
    """记录每一次回写，便于断言消息内容。"""

    def __init__(self, fail_image_result=False, fail_image_failed=False):
        self.events = []
        self.fail_image_result = fail_image_result
        self.fail_image_failed = fail_image_failed

    def send_task_started(self, task_id):
        self.events.append(("task_started", task_id))

    def send_image_result(self, task_id, image_id, detections):
        self.events.append(("image_result", task_id, image_id, len(detections)))
        if self.fail_image_result:
            raise RuntimeError("结果队列不可用")

    def send_image_failed(self, task_id, image_id, error):
        self.events.append(("image_failed", task_id, image_id, error))
        if self.fail_image_failed:
            raise RuntimeError("结果队列不可用")

    def kinds(self):
        return [event[0] for event in self.events]


def write_image(path, width=32, height=24):
    path.parent.mkdir(parents=True, exist_ok=True)
    ok, buffer = cv2.imencode(".png", np.zeros((height, width, 3), dtype=np.uint8))
    assert ok
    path.write_bytes(buffer.tobytes())
    return path


def prepare(tmp_path, base_names):
    """
    落盘一批真实图片并返回任务消息形态的入参。

    写文件与构造 filePath 用同一个助手，避免两处文件名不一致
    导致"图片其实没读到、断言却按成功来写"的假测试。
    """
    payload = []
    for index, base_name in enumerate(base_names, start=1):
        file_path = write_image(tmp_path / f"{base_name}.png")
        payload.append({"imageId": index, "filePath": str(file_path)})
    return payload


# ── 主流程 ──────────────────────────────────────────────────────────────────


def test_process_reports_task_started_and_every_image(tmp_path):
    payload = prepare(tmp_path, ["a", "b", "c"])
    detector = FakeDetector()
    callback = RecordingCallback()
    processor = BatchProcessor(detector=detector, callback=callback, batch_size=2)

    summary = processor.process(10001, payload)

    assert summary.taskId == 10001
    assert summary.totalCount == 3
    assert summary.successCount == 3
    assert summary.failedCount == 0
    assert callback.kinds() == ["task_started", "image_result", "image_result", "image_result"]
    assert callback.events[0] == ("task_started", 10001)
    assert [event[2] for event in callback.events[1:]] == [1, 2, 3]
    assert [call["imageCount"] for call in detector.calls] == [2, 1]


def test_process_splits_into_chunks_of_batch_size(tmp_path):
    payload = prepare(tmp_path, [f"img{i}" for i in range(5)])
    detector = FakeDetector()
    processor = BatchProcessor(detector=detector, batch_size=2)

    summary = processor.process(7, payload)

    assert [call["imageCount"] for call in detector.calls] == [2, 2, 1]
    assert summary.totalCount == 5
    assert summary.successCount == 5


def test_process_marks_unreadable_image_as_failed_without_stopping_batch(tmp_path):
    payload = [
        {"imageId": 1, "filePath": str(write_image(tmp_path / "ok1.png"))},
        {"imageId": 2, "filePath": str(tmp_path / "missing.png")},
        {"imageId": 3, "filePath": str(write_image(tmp_path / "ok3.png"))},
    ]
    detector = FakeDetector()
    callback = RecordingCallback()

    summary = BatchProcessor(
        detector=detector, callback=callback, batch_size=8
    ).process(9, payload, collect_results=True)

    assert summary.totalCount == 3
    assert summary.successCount == 2
    assert summary.failedCount == 1
    # 读图失败的图像不参与本次 batch 推理
    assert [call["imageCount"] for call in detector.calls] == [2]

    by_id = {outcome.imageId: outcome for outcome in summary.results}
    assert by_id[2].status == "FAILED"
    assert "图像文件不存在" in by_id[2].errorMessage
    assert by_id[1].status == "SUCCESS"
    assert by_id[3].status == "SUCCESS"

    failed_events = [event for event in callback.events if event[0] == "image_failed"]
    assert len(failed_events) == 1
    assert failed_events[0][2] == 2


def test_process_still_succeeds_when_callback_raises(tmp_path):
    callback = RecordingCallback(fail_image_result=True)

    summary = BatchProcessor(
        detector=FakeDetector(), callback=callback, batch_size=4
    ).process(11, prepare(tmp_path, ["a"]))

    assert summary.successCount == 1
    assert summary.failedCount == 0


def test_process_keeps_going_when_failure_report_raises(tmp_path):
    callback = RecordingCallback(fail_image_failed=True)

    summary = BatchProcessor(
        detector=FakeDetector(), callback=callback, batch_size=4
    ).process(12, [{"imageId": 1, "filePath": str(tmp_path / "missing.png")}])

    assert summary.failedCount == 1
    assert summary.successCount == 0


def test_process_works_without_callback(tmp_path):
    summary = BatchProcessor(detector=FakeDetector(), batch_size=4).process(
        13, prepare(tmp_path, ["a"])
    )

    assert summary.successCount == 1
    assert summary.results == []


def test_process_returns_empty_summary_for_no_images():
    summary = BatchProcessor(detector=FakeDetector()).process(14, [])

    assert summary.totalCount == 0
    assert summary.successCount == 0
    assert summary.avgMsPerImage == 0.0


def test_process_counts_zero_detection_image_as_success(tmp_path):
    callback = RecordingCallback()

    summary = BatchProcessor(
        detector=FakeDetector(detections=[]), callback=callback, batch_size=4
    ).process(15, prepare(tmp_path, ["empty_shot"]), collect_results=True)

    assert summary.successCount == 1
    assert summary.failedCount == 0
    assert summary.results[0].detectionCount == 0
    assert ("image_result", 15, 1, 0) in callback.events


def test_results_are_not_collected_by_default(tmp_path):
    processor = BatchProcessor(detector=FakeDetector(), batch_size=4)

    summary = processor.process(16, prepare(tmp_path, ["a"]))

    assert summary.successCount == 1
    assert summary.results == []


def test_collected_outcomes_carry_backend_ready_detections(tmp_path):
    summary = BatchProcessor(detector=FakeDetector(), batch_size=4).process(
        17, prepare(tmp_path, ["a"]), collect_results=True
    )

    assert summary.results[0].status == "SUCCESS"
    assert summary.results[0].detections == [
        {"classId": 0, "className": "野猪", "confidence": 0.91, "bbox": [10, 20, 110, 220]}
    ]


def test_version_is_passed_through_to_detector(tmp_path):
    detector = FakeDetector()

    BatchProcessor(detector=detector, batch_size=4).process(
        18, prepare(tmp_path, ["a"]), version="wildlife-v2.0"
    )

    assert detector.calls[0]["version"] == "wildlife-v2.0"


# ── batch_size 取值 ─────────────────────────────────────────────────────────


def test_batch_size_comes_from_env(monkeypatch):
    monkeypatch.setenv("BATCH_SIZE", "3")

    assert BatchProcessor(detector=FakeDetector()).batch_size == 3


def test_batch_size_env_is_ignored_when_explicit(monkeypatch):
    monkeypatch.setenv("BATCH_SIZE", "3")

    assert BatchProcessor(detector=FakeDetector(), batch_size=6).batch_size == 6


def test_batch_size_is_floored_at_one():
    assert BatchProcessor(detector=FakeDetector(), batch_size=-5).batch_size == 1


# ── 入参归一化 ──────────────────────────────────────────────────────────────


def test_normalize_accepts_path_string():
    assert BatchProcessor._normalize("/data/a.jpg") == {
        "imageId": None,
        "filePath": "/data/a.jpg",
    }


def test_normalize_accepts_dict_with_image_path_alias():
    assert BatchProcessor._normalize({"imageId": 5, "imagePath": "/data/b.jpg"}) == {
        "imageId": 5,
        "filePath": "/data/b.jpg",
    }


def test_normalize_reads_attributes_from_objects():
    item = SimpleNamespace(imageId=8, filePath="/data/c.jpg")

    assert BatchProcessor._normalize(item) == {"imageId": 8, "filePath": "/data/c.jpg"}


def test_normalize_defaults_missing_fields():
    assert BatchProcessor._normalize({"imageId": 1}) == {"imageId": 1, "filePath": ""}


def test_chunks_yields_tail_smaller_than_size():
    assert [len(chunk) for chunk in BatchProcessor._chunks(list(range(7)), 3)] == [3, 3, 1]


def test_chunks_returns_nothing_for_empty_list():
    assert list(BatchProcessor._chunks([], 3)) == []


# ── 数据结构 ────────────────────────────────────────────────────────────────


def test_batch_summary_avg_ms_per_image():
    assert BatchSummary(taskId=1, totalCount=4, elapsedMs=100).avgMsPerImage == 25.0
    assert BatchSummary(taskId=1, totalCount=0, elapsedMs=100).avgMsPerImage == 0.0


def test_batch_summary_to_dict_can_omit_results():
    summary = BatchSummary(taskId=2, totalCount=1, successCount=1, elapsedMs=8)
    summary.results.append(ImageOutcome(imageId=1, filePath="a.jpg"))

    without_results = summary.to_dict(with_results=False)
    with_results = summary.to_dict()

    assert "results" not in without_results
    assert without_results["taskId"] == 2
    assert without_results["avgMsPerImage"] == 8.0
    assert len(with_results["results"]) == 1


def test_image_outcome_to_dict_defaults():
    outcome = ImageOutcome(imageId=3, filePath="/data/a.jpg").to_dict()

    assert outcome == {
        "imageId": 3,
        "filePath": "/data/a.jpg",
        "status": "SUCCESS",
        "detectionCount": 0,
        "detections": [],
        "errorMessage": None,
    }
