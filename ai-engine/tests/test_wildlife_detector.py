"""WildlifeDetector（best.pt 单张/批量推理）单元测试。

权重通过假的 ModelManager 解析，再预置进检测器的权重缓存，
因此不会真的 import ultralytics，也不会去碰 models/ 下的真实权重。
"""
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from app.services.wildlife_detector import (
    Detection,
    WildlifeDetector,
    decode_image,
    get_wildlife_detector,
    is_supported_image,
    read_image,
)


# ── 假对象 ──────────────────────────────────────────────────────────────────


class FakeBoxes:
    def __init__(self, xyxy, conf, cls):
        self.xyxy = None if xyxy is None else np.array(xyxy, dtype=float)
        self.conf = np.array(conf, dtype=float)
        self.cls = np.array(cls, dtype=float)

    def __len__(self):
        return len(self.cls)


class FakeResult:
    def __init__(self, boxes, orig_shape=None):
        self.boxes = boxes
        self.orig_shape = orig_shape


class FakeModel:
    """按输入图像张数返回对应数量的结果，并在结果里带上原图尺寸。"""

    def __init__(self, names=None, detections=None):
        self.names = {0: "野猪", 1: "小麂"} if names is None else names
        self.calls = []
        self._detections = detections

    def _boxes(self):
        if self._detections is not None:
            return self._detections
        return FakeBoxes(
            xyxy=[[10.4, 20.6, 110.6, 220.4], [300.0, 40.0, 380.0, 120.0]],
            conf=[0.9312, 0.7456],
            cls=[0, 1],
        )

    def predict(self, source=None, **kwargs):
        self.calls.append({"source": source, **kwargs})
        sources = source if isinstance(source, list) else [source]
        return [
            FakeResult(self._boxes(), orig_shape=tuple(item.shape[:2]))
            for item in sources
        ]


class OrderMismatchingModel(FakeModel):
    """批量推理时故意返回对不上的原图尺寸，触发降级为逐张推理。"""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.batch_calls = 0
        self.single_calls = 0

    def predict(self, source=None, **kwargs):
        if isinstance(source, list):
            self.batch_calls += 1
            self.calls.append({"source": source, **kwargs})
            return [FakeResult(self._boxes(), orig_shape=(1, 1)) for _ in source]
        self.single_calls += 1
        return super().predict(source=source, **kwargs)


class ExplodingBatchModel(FakeModel):
    """批量推理直接抛异常，触发降级为逐张推理。"""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.single_calls = 0

    def predict(self, source=None, **kwargs):
        if isinstance(source, list):
            raise RuntimeError("batch 推理炸了")
        self.single_calls += 1
        return super().predict(source=source, **kwargs)


class FakeModelManager:
    """只实现 WildlifeDetector 用到的那几个方法。"""

    def __init__(
        self,
        weight_path,
        active="wildlife-v1.0",
        versions=None,
        class_names=None,
    ):
        self.weight_path = str(weight_path)
        self._active = active
        self._versions = (
            ["wildlife-v1.0", "wildlife-v1.1", "wildlife-v2.0"]
            if versions is None
            else list(versions)
        )
        self._class_names = ["野猪", "小麂"] if class_names is None else list(class_names)
        self.resolve_calls = []
        self.class_names_calls = []

    def get_active_version(self):
        return self._active

    def resolve_path(self, version=None, allow_fallback=True):
        self.resolve_calls.append(version or self._active)
        return self.weight_path

    def get_spec(self, version):
        if version not in self._versions:
            raise KeyError(f"未知模型版本: {version}")
        return SimpleNamespace(version=version, available=True, model_path=self.weight_path)

    def class_names(self, version=None):
        self.class_names_calls.append(version)
        return list(self._class_names)


class BrokenWeightManager(FakeModelManager):
    """权重解析就失败，用于验证 class_names 回退 classes.txt。"""

    def resolve_path(self, version=None, allow_fallback=True):
        raise FileNotFoundError("best.pt 还没放进去")


# ── 辅助 ────────────────────────────────────────────────────────────────────


def make_detector(tmp_path, model=None, manager=None, version=None):
    manager = manager or FakeModelManager(
        tmp_path / "models" / "wildlife-v1.0" / "best.pt"
    )
    detector = WildlifeDetector(version=version, model_manager=manager)
    if model is not None:
        detector._models[manager.weight_path] = model
    return detector, manager


def write_image(path, width=64, height=48):
    image = np.zeros((height, width, 3), dtype=np.uint8)
    path.parent.mkdir(parents=True, exist_ok=True)
    ok, buffer = cv2.imencode(".png", image)
    assert ok
    path.write_bytes(buffer.tobytes())
    return path


# ── Detection 数据类 ────────────────────────────────────────────────────────


def test_detection_exposes_bbox_accessors():
    detection = Detection(classId=3, className="野猪", confidence=0.93, bbox=[120, 80, 460, 390])

    assert (detection.x1, detection.y1, detection.x2, detection.y2) == (120, 80, 460, 390)
    assert detection.width == 340
    assert detection.height == 310


def test_detection_accessors_are_zero_when_bbox_missing():
    detection = Detection(classId=0, className="未知", confidence=0.5)

    assert (detection.x1, detection.y1, detection.x2, detection.y2) == (0, 0, 0, 0)
    assert detection.width == 0
    assert detection.height == 0


def test_detection_to_dict_uses_bbox_array():
    """后端 parseDetections 支持 bbox 数组写法，这里固定用该写法。"""
    detection = Detection(classId=7, className="小麂", confidence=0.8123, bbox=[1, 2, 3, 4])

    assert detection.to_dict() == {
        "classId": 7,
        "className": "小麂",
        "confidence": 0.8123,
        "bbox": [1, 2, 3, 4],
    }


# ── 版本绑定 ────────────────────────────────────────────────────────────────


def test_detector_defaults_to_active_version(tmp_path):
    _, manager = make_detector(tmp_path, model=FakeModel())
    manager._active = "wildlife-v2.0"

    detector, _ = make_detector(tmp_path, model=FakeModel(), manager=manager)

    assert detector.version == "wildlife-v2.0"


def test_switch_changes_version_and_rejects_unknown(tmp_path):
    detector, _ = make_detector(tmp_path, model=FakeModel())

    detector.switch("wildlife-v2.0")
    assert detector.version == "wildlife-v2.0"

    with pytest.raises(KeyError):
        detector.switch("wildlife-v9.9")


def test_explicit_version_overrides_active_one_for_inference(tmp_path):
    model = FakeModel()
    detector, manager = make_detector(tmp_path, model=model, version="wildlife-v1.0")

    detector.detect_array(np.zeros((8, 8, 3), dtype=np.uint8), version="wildlife-v2.0")

    assert manager.resolve_calls == ["wildlife-v2.0"]
    assert detector.version == "wildlife-v1.0"


# ── 类别名 ──────────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "names,class_id,expected",
    [
        ({0: "野猪", 1: "小麂"}, 1, "小麂"),
        (["野猪", "小麂"], 0, "野猪"),
        ({0: "野猪"}, 5, "class_5"),
        (None, 2, "class_2"),
    ],
)
def test_class_name_fallbacks(names, class_id, expected):
    assert WildlifeDetector._class_name(names, class_id) == expected


@pytest.mark.parametrize("raw,expected", [("", None), ("  ", None), ("0,3,7", [0, 3, 7])])
def test_parse_target_classes(raw, expected):
    assert WildlifeDetector._parse_target_classes(raw) == expected


def test_class_names_prefers_weight_names(tmp_path):
    detector, manager = make_detector(
        tmp_path, model=FakeModel(names={0: "野猪", 1: "小麂", 2: "白鹇"})
    )

    assert detector.class_names() == [
        {"classId": 0, "className": "野猪"},
        {"classId": 1, "className": "小麂"},
        {"classId": 2, "className": "白鹇"},
    ]
    assert manager.class_names_calls == []


def test_class_names_supports_list_shaped_names(tmp_path):
    detector, _ = make_detector(tmp_path, model=FakeModel(names=["大熊猫", "雪豹"]))

    assert detector.class_names() == [
        {"classId": 0, "className": "大熊猫"},
        {"classId": 1, "className": "雪豹"},
    ]


def test_class_names_falls_back_to_classes_file_when_weight_broken(tmp_path):
    manager = BrokenWeightManager(tmp_path / "models" / "wildlife-v1.0" / "best.pt")
    detector = WildlifeDetector(model_manager=manager)

    assert detector.class_names("wildlife-v1.0") == [
        {"classId": 0, "className": "野猪"},
        {"classId": 1, "className": "小麂"},
    ]
    assert manager.class_names_calls == ["wildlife-v1.0"]


# ── predict 参数 ────────────────────────────────────────────────────────────


def test_build_predict_kwargs_defaults(tmp_path):
    detector, _ = make_detector(tmp_path, model=FakeModel())
    detector.conf_threshold = 0.25
    detector.iou_threshold = 0.5
    detector.image_size = 640
    detector.max_detections = 300
    detector.device = None
    detector.target_classes = None

    kwargs = detector._build_predict_kwargs(None, None)

    assert kwargs == {
        "conf": 0.25,
        "iou": 0.5,
        "imgsz": 640,
        "max_det": 300,
        "verbose": False,
    }


def test_build_predict_kwargs_with_overrides_device_and_classes(tmp_path):
    detector, _ = make_detector(tmp_path, model=FakeModel())
    detector.device = "cpu"
    detector.target_classes = [0, 1]

    kwargs = detector._build_predict_kwargs(conf=0.6, iou=0.3)

    assert kwargs["conf"] == 0.6
    assert kwargs["iou"] == 0.3
    assert kwargs["device"] == "cpu"
    assert kwargs["classes"] == [0, 1]


# ── 单张推理 ────────────────────────────────────────────────────────────────


def test_detect_array_returns_detection_objects(tmp_path):
    model = FakeModel()
    detector, _ = make_detector(tmp_path, model=model)

    detections = detector.detect_array(np.zeros((48, 64, 3), dtype=np.uint8))

    assert [item.className for item in detections] == ["野猪", "小麂"]
    assert all(isinstance(item, Detection) for item in detections)
    assert detections[0].bbox == [10, 21, 111, 220]
    assert detections[0].confidence == 0.9312
    assert model.calls[0]["source"].shape == (48, 64, 3)


def test_to_detections_returns_empty_without_boxes(tmp_path):
    detector, _ = make_detector(tmp_path, model=FakeModel())

    assert detector._to_detections(FakeResult(None), {}) == []
    assert detector._to_detections(FakeResult(FakeBoxes(None, [], [])), {}) == []


@pytest.mark.parametrize("empty", [None, np.zeros((0, 0, 3), dtype=np.uint8)])
def test_detect_array_rejects_empty_image(empty, tmp_path):
    detector, _ = make_detector(tmp_path, model=FakeModel())

    with pytest.raises(ValueError, match="图像内容为空"):
        detector.detect_array(empty)


# ── 批量推理 ────────────────────────────────────────────────────────────────


def test_detect_batch_runs_single_predict_for_whole_chunk(tmp_path):
    model = FakeModel()
    detector, _ = make_detector(tmp_path, model=model)
    images = [
        np.zeros((48, 64, 3), dtype=np.uint8),
        np.zeros((32, 32, 3), dtype=np.uint8),
        np.zeros((16, 16, 3), dtype=np.uint8),
    ]

    batch = detector.detect_batch(images)

    assert len(model.calls) == 1, "整批必须一次 predict，不能逐张调用"
    assert isinstance(model.calls[0]["source"], list)
    assert len(model.calls[0]["source"]) == 3
    assert len(batch) == 3
    assert [len(item) for item in batch] == [2, 2, 2]


def test_detect_batch_returns_empty_for_empty_input(tmp_path):
    model = FakeModel()
    detector, _ = make_detector(tmp_path, model=model)

    assert detector.detect_batch([]) == []
    assert model.calls == []


def test_detect_batch_falls_back_when_result_order_mismatches(tmp_path):
    """结果 ↔ 图像 一旦对不上，宁可慢也不能把结果挂到错误的图上。"""
    model = OrderMismatchingModel()
    detector, _ = make_detector(tmp_path, model=model)
    images = [np.zeros((48, 64, 3), dtype=np.uint8), np.zeros((32, 32, 3), dtype=np.uint8)]

    batch = detector.detect_batch(images)

    assert model.batch_calls == 1
    assert model.single_calls == 2
    assert len(batch) == 2
    assert [len(item) for item in batch] == [2, 2]


def test_detect_batch_falls_back_when_predict_raises(tmp_path):
    model = ExplodingBatchModel()
    detector, _ = make_detector(tmp_path, model=model)
    images = [np.zeros((8, 8, 3), dtype=np.uint8), np.zeros((8, 8, 3), dtype=np.uint8)]

    batch = detector.detect_batch(images)

    assert model.single_calls == 2
    assert [len(item) for item in batch] == [2, 2]


def test_order_matches_tolerates_results_without_orig_shape(tmp_path):
    detector, _ = make_detector(tmp_path, model=FakeModel())
    images = [np.zeros((8, 8, 3), dtype=np.uint8)]

    assert WildlifeDetector._order_matches([FakeResult(None, orig_shape=None)], images) is True
    assert WildlifeDetector._order_matches([FakeResult(None, orig_shape=(9, 9))], images) is False
    assert WildlifeDetector._order_matches([], images) is False


# ── 统一入口 detect ─────────────────────────────────────────────────────────


def test_detect_accepts_ndarray(tmp_path):
    detector, _ = make_detector(tmp_path, model=FakeModel())

    assert len(detector.detect(np.zeros((8, 8, 3), dtype=np.uint8))) == 2


def test_detect_accepts_file_path(tmp_path):
    detector, _ = make_detector(tmp_path, model=FakeModel())
    image_path = write_image(tmp_path / "监测点位_01" / "雪豹.png")

    assert len(detector.detect(str(image_path))) == 2


def test_detect_accepts_bytes(tmp_path):
    detector, _ = make_detector(tmp_path, model=FakeModel())
    ok, buffer = cv2.imencode(".png", np.zeros((8, 8, 3), dtype=np.uint8))
    assert ok

    assert len(detector.detect(buffer.tobytes())) == 2


def test_detect_rejects_unsupported_source_type(tmp_path):
    detector, _ = make_detector(tmp_path, model=FakeModel())

    with pytest.raises(TypeError, match="不支持的图像输入类型"):
        detector.detect(12345)


# ── 模块级图像工具 ──────────────────────────────────────────────────────────


def test_read_image_reads_non_ascii_path(tmp_path):
    """项目与采集目录都含中文，必须走 np.fromfile + imdecode。"""
    image_path = write_image(tmp_path / "监测点位_01" / "野猪 夜间.png", width=30, height=20)

    image = read_image(str(image_path))

    assert image.shape == (20, 30, 3)


def test_read_image_rejects_missing_file(tmp_path):
    with pytest.raises(FileNotFoundError, match="图像文件不存在"):
        read_image(str(tmp_path / "nope.png"))


def test_read_image_rejects_directory(tmp_path):
    with pytest.raises(ValueError, match="不是文件"):
        read_image(str(tmp_path))


def test_read_image_rejects_empty_path():
    with pytest.raises(ValueError, match="图像路径为空"):
        read_image("")


def test_decode_image_roundtrip(tmp_path):
    ok, buffer = cv2.imencode(".jpg", np.zeros((10, 20, 3), dtype=np.uint8))
    assert ok

    assert decode_image(buffer.tobytes()).shape == (10, 20, 3)


@pytest.mark.parametrize("payload", [b"", b"not-an-image"])
def test_decode_image_rejects_bad_payload(payload):
    with pytest.raises(ValueError):
        decode_image(payload)


@pytest.mark.parametrize("name,expected", [("a.JPG", True), ("clip.mp4", False), ("x.txt", False)])
def test_is_supported_image(name, expected):
    assert is_supported_image(name) is expected


# ── 单例缓存 ────────────────────────────────────────────────────────────────


def test_get_wildlife_detector_caches_per_version():
    first = get_wildlife_detector("wildlife-v1.1")
    assert get_wildlife_detector("wildlife-v1.1") is first

    other = get_wildlife_detector("wildlife-v2.0")
    assert other is not first
    assert other.version == "wildlife-v2.0"
