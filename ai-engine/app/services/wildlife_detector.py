"""
野生动物图像检测器

专门加载野生动物识别权重 `best.pt`，对图像做 YOLO 推理，
返回 bbox / class / confidence 三类信息。

权重路径由 ModelManager 按版本解析（wildlife-v1.0 / v1.1 / v2.0）；
调用时可传 version 临时指定某个版本推理，不影响当前启用版本。

**权重文件必须以二进制方式拷贝。** 权重被文本模式读写过时体积只少几十字节，
文件仍像正常 .pt，但 torch 一定加载不了；因此加载前先做一次结构体检，
失败时抛 `ModelWeightError` 并给出可执行的修复提示，而不是让 torch 抛底层异常。
`verify()` 则进一步比对「权重真正认识的类别」与「版本声明的类别表」是否一致。

本模块只做单张/小批的推理，不做任务编排（归 batch_processor）。
"""
import logging
import os
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Union

import cv2
import numpy as np

from app.services.model_manager import ModelManager, get_model_manager

logger = logging.getLogger(__name__)

# 支持的图像扩展名
SUPPORTED_IMAGE_EXTENSIONS = {
    ".jpg",
    ".jpeg",
    ".png",
    ".bmp",
    ".webp",
    ".tif",
    ".tiff",
}

_detector_cache: Dict[str, "WildlifeDetector"] = {}


class ModelWeightError(RuntimeError):
    """权重不可用（缺失 / 结构损坏 / 不是 ultralytics 权重）。"""


def _weight_issues(model_manager: Any, weight_path: str) -> List[str]:
    """
    对权重做结构体检，返回问题清单。

    优先用管理器自带的实现，管理器没有该方法时回落到 ModelManager 的静态实现，
    这样轻量的测试替身也不会因为缺方法而炸掉。
    """
    inspector = getattr(model_manager, "inspect_weight", None)
    if inspector is None:
        inspector = ModelManager.inspect_weight
    try:
        return list(inspector(weight_path))
    except Exception as exc:  # noqa: BLE001 - 体检本身出错不能拖垮推理
        logger.warning("权重体检出错，跳过该检查（%s: %s）", type(exc).__name__, exc)
        return []



def get_wildlife_detector(version: Optional[str] = None) -> "WildlifeDetector":
    """按版本获取共享检测器实例（HTTP 同步接口使用，权重只加载一次）。"""
    manager = get_model_manager()
    key = version or manager.get_active_version()
    if key not in _detector_cache:
        _detector_cache[key] = WildlifeDetector(version=version)
    return _detector_cache[key]


def read_image(image_path: str) -> np.ndarray:
    """
    读取图像为 OpenCV BGR 数组。

    使用 np.fromfile + cv2.imdecode 而非 cv2.imread，
    否则 Windows 下中文/空格路径会读取失败。
    """
    if not image_path:
        raise ValueError("图像路径为空")
    if not os.path.exists(image_path):
        raise FileNotFoundError(f"图像文件不存在: {image_path}")
    if not os.path.isfile(image_path):
        raise ValueError(f"图像路径不是文件: {image_path}")

    buffer = np.fromfile(image_path, dtype=np.uint8)
    image = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError(f"图像解码失败，可能不是有效图像: {image_path}")
    return image


def decode_image(content: bytes) -> np.ndarray:
    """把图像字节流解码为 OpenCV BGR 数组（接口直传用，不落盘）。"""
    if not content:
        raise ValueError("图像字节流为空")
    image = cv2.imdecode(np.frombuffer(content, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("图像解码失败，可能不是有效图像")
    return image


def is_supported_image(file_name: str) -> bool:
    _, extension = os.path.splitext(file_name or "")
    return extension.lower() in SUPPORTED_IMAGE_EXTENSIONS


@dataclass
class Detection:
    """单个检测框。bbox 为像素坐标 [x1, y1, x2, y2]。"""

    classId: int
    className: str
    confidence: float
    bbox: List[int] = field(default_factory=list)

    @property
    def x1(self) -> int:
        return self.bbox[0] if len(self.bbox) > 0 else 0

    @property
    def y1(self) -> int:
        return self.bbox[1] if len(self.bbox) > 1 else 0

    @property
    def x2(self) -> int:
        return self.bbox[2] if len(self.bbox) > 2 else 0

    @property
    def y2(self) -> int:
        return self.bbox[3] if len(self.bbox) > 3 else 0

    @property
    def width(self) -> int:
        return max(0, self.x2 - self.x1)

    @property
    def height(self) -> int:
        return max(0, self.y2 - self.y1)

    def to_dict(self) -> Dict[str, Any]:
        """
        转成回写后端的结构。

        后端 `RedisSubscriberService.parseDetections` 同时支持
        `bbox:[x1,y1,x2,y2]` 与扁平的 `x1/y1/x2/y2` 两种写法，这里用前者。
        """
        return {
            "classId": self.classId,
            "className": self.className,
            "confidence": self.confidence,
            "bbox": list(self.bbox),
        }


class WildlifeDetector:
    """单张图像野生动物检测器，按版本加载 best.pt。"""

    def __init__(
        self,
        version: Optional[str] = None,
        model_manager: Optional[ModelManager] = None,
    ):
        self.model_manager = model_manager or get_model_manager()
        self.version = version or self.model_manager.get_active_version()
        self.conf_threshold = float(os.getenv("YOLO_CONF_THRESHOLD", "0.25"))
        self.iou_threshold = float(os.getenv("YOLO_IOU_THRESHOLD", "0.5"))
        self.image_size = int(os.getenv("YOLO_IMAGE_SIZE", "640"))
        self.max_detections = int(os.getenv("YOLO_MAX_DETECTIONS", "300"))
        self.device = os.getenv("YOLO_DEVICE", "").strip() or None
        self.cpu_threads = self._apply_cpu_threads(os.getenv("YOLO_CPU_THREADS", ""))
        self.target_classes = self._parse_target_classes(
            os.getenv("YOLO_TARGET_CLASSES", "")
        )
        self._models: Dict[str, Any] = {}

    # ── 运行参数 ────────────────────────────────────────────────────────────

    @staticmethod
    def _apply_cpu_threads(raw_value: str) -> Optional[int]:
        """
        按 `YOLO_CPU_THREADS` 限制 torch 使用的 CPU 线程数。

        **为什么需要这个开关**：torch 默认会用满机器上所有核心。AI 引擎与
        MySQL / Redis / 后端 / 前端跑在同一台机器上，推理把 CPU 打满会让其它
        容器一起变慢 —— 表现出来是"一识别整个页面就卡"，而日志里没有任何
        错误可查，很容易被误判成前端或数据库的问题。

        不设该变量时**保持 torch 默认（不限制）**：单看推理速度，多线程通常
        比限制核心更快，所以这里不替使用者做取舍，只把开关和后果讲清楚。
        """
        value = (raw_value or "").strip()
        if not value:
            return None
        try:
            threads = int(value)
        except ValueError:
            logger.warning("YOLO_CPU_THREADS 不是整数（%r），按未设置处理", raw_value)
            return None
        if threads < 1:
            logger.warning("YOLO_CPU_THREADS 必须 >= 1（当前 %r），按未设置处理", value)
            return None
        try:
            import torch
        except ImportError:
            logger.warning("未安装 torch，YOLO_CPU_THREADS 不生效")
            return None
        try:
            torch.set_num_threads(threads)
        except Exception as exc:  # noqa: BLE001 - 限制线程失败不该拦住推理
            logger.warning("设置 torch CPU 线程数失败: %s", exc)
            return None
        logger.info("已限制 torch CPU 线程数为 %d", threads)
        return threads

    def runtime_summary(self) -> Dict[str, Any]:
        """
        当前推理的生效参数，供启动日志与排查使用。

        `torch` 采用延迟导入：本模块在没有 torch 的环境里也应能被 import
        （权重体检、单元测试都只用到路径解析部分）。
        """
        cuda_available = False
        torch_threads: Optional[int] = None
        try:
            import torch

            cuda_available = bool(torch.cuda.is_available())
            torch_threads = int(torch.get_num_threads())
        except Exception:  # noqa: BLE001 - 拿不到就如实留空
            pass

        return {
            "version": self.version,
            "device": self.device or "auto",
            "cudaAvailable": cuda_available,
            "torchThreads": torch_threads,
            "cpuThreadsLimit": self.cpu_threads,
            "imageSize": self.image_size,
            "confThreshold": self.conf_threshold,
            "iouThreshold": self.iou_threshold,
            "maxDetections": self.max_detections,
            "targetClasses": self.target_classes,
        }

    # ── 模型 ────────────────────────────────────────────────────────────────

    @staticmethod
    def _parse_target_classes(raw_value: str) -> Optional[List[int]]:
        if not raw_value or not raw_value.strip():
            return None
        return [int(value.strip()) for value in raw_value.split(",") if value.strip()]

    def _load_model(self, version: Optional[str] = None):
        version = version or self.version
        weight_path = self.model_manager.resolve_path(version)
        if weight_path not in self._models:
            issues = _weight_issues(self.model_manager, weight_path)
            if issues:
                raise ModelWeightError(
                    f"权重不可用，无法加载版本 {version}（{weight_path}）：\n  - "
                    + "\n  - ".join(issues)
                )

            try:
                from ultralytics import YOLO

                logger.info(
                    "Loading wildlife detector weights: %s (version=%s)",
                    weight_path,
                    version,
                )
                self._models[weight_path] = YOLO(weight_path)
            except ModelWeightError:
                raise
            except Exception as exc:  # noqa: BLE001 - 统一收敛成可读的加载失败
                raise ModelWeightError(
                    f"加载权重失败（版本 {version}，文件 {weight_path}）："
                    f"{type(exc).__name__}: {exc}。"
                    f"请确认该文件是 ultralytics 导出的 best.pt，"
                    f"且整个拷贝过程都是二进制方式"
                ) from exc
            logger.info("Wildlife detector ready: %s", weight_path)
        return self._models[weight_path]

    def warmup(self, version: Optional[str] = None) -> None:
        """
        预加载权重并做一次自检，避免首张图像承担模型加载耗时。

        自检发现的问题只记日志、不抛异常：服务仍应起来，
        但日志里必须留下「权重与版本声明对不上」这类线索，
        否则会变成「系统在跑、结果全错」的静默故障。
        """
        self._load_model(version)
        report = self.verify(version)
        if report["issues"]:
            logger.error(
                "模型自检发现问题（版本 %s）：\n  - %s",
                report["version"],
                "\n  - ".join(report["issues"]),
            )
        else:
            logger.info(
                "模型自检通过：%s（%d 个类别，权重 %s）",
                report["version"],
                report["declaredClassCount"],
                report["weightPath"],
            )

    def verify(self, version: Optional[str] = None) -> Dict[str, Any]:
        """
        体检某个版本（默认本人当前绑定版本）的权重，返回结构化报告。

        检查三件事：权重能否解析与加载、权重真正认识的类别**数量**与**名称**
        是否与版本声明的类别表（classes.txt / meta.json）一致。
        本方法不抛异常，供启动自检与接口诊断使用。
        """
        target = version or self.version
        report: Dict[str, Any] = {
            "version": target,
            "weightPath": "",
            "weightAvailable": False,
            "weightIssues": [],
            "weightClassCount": 0,
            "weightClasses": [],
            "declaredClassCount": 0,
            "declaredClasses": [],
            "classCountMatches": None,
            "classNamesMatch": None,
            "issues": [],
            "ok": False,
        }

        try:
            weight_path = self.model_manager.resolve_path(target)
        except Exception as exc:  # noqa: BLE001 - 缺失/未知版本都收敛成报告
            report["issues"].append(f"解析权重路径失败：{exc}")
            return report
        report["weightPath"] = weight_path

        weight_issues = _weight_issues(self.model_manager, weight_path)
        report["weightIssues"] = weight_issues
        report["issues"].extend(weight_issues)

        # 版本声明的类别表：后端统计 / 复核界面都按它索引。
        # 先读它，这样即使权重加载失败，报告里也能看到"本应是什么类别"。
        declared: List[str] = []
        try:
            reader = getattr(self.model_manager, "class_names", None)
            if reader is not None:
                declared = [str(item) for item in (reader(target) or [])]
        except Exception as exc:  # noqa: BLE001
            report["issues"].append(f"读取版本类别声明失败：{exc}")
        report["declaredClasses"] = declared
        report["declaredClassCount"] = len(declared)

        # 权重真正认识的类别：以权重自带的 names 为准
        try:
            model = self._load_model(target)
            names = getattr(model, "names", None)
            if isinstance(names, dict):
                items = [str(names[key]) for key in sorted(names.keys())]
            elif isinstance(names, (list, tuple)):
                items = [str(item) for item in names]
            else:
                items = []
            report["weightClasses"] = items
            report["weightClassCount"] = len(items)
            report["weightAvailable"] = bool(items)
            if not items:
                report["issues"].append("权重里没有读到类别名（names 为空）")
        except Exception as exc:  # noqa: BLE001
            report["issues"].append(f"加载权重失败：{exc}")
            return report

        if not declared:
            report["issues"].append(
                f"版本 {target} 没有声明类别清单，无法校验权重是否对得上版本"
            )
        else:
            report["classCountMatches"] = report["weightClassCount"] == len(declared)
            if not report["classCountMatches"]:
                report["issues"].append(
                    f"权重类别数（{report['weightClassCount']}）"
                    f"与版本声明（{len(declared)}）不一致，"
                    f"识别出的 classId 无法对应后端类别表"
                )
            else:
                diffs = [
                    f"{index}: 权重={weight_name} 声明={declared_name}"
                    for index, (weight_name, declared_name) in enumerate(
                        zip(report["weightClasses"], declared)
                    )
                    if weight_name != declared_name
                ]
                report["classNamesMatch"] = not diffs
                if diffs:
                    report["issues"].append(
                        "权重类别名与版本声明不一致，前几处：" + "；".join(diffs[:5])
                    )

            overlap = set(report["weightClasses"]) & set(declared)
            if not overlap:
                report["issues"].append(
                    "权重类别名与版本声明零重合，该权重很可能不是本版本的微调权重，"
                    "请确认 models/ 下放的是训练导出的 best.pt"
                )

        report["ok"] = not report["issues"]
        return report

    def switch(self, version: str) -> None:
        """切换本检测器使用的版本（不改变全局启用版本）。"""
        self.model_manager.get_spec(version)
        self.version = version
        logger.info("Detector switched to version %s", version)

    @staticmethod
    def _class_name(names: Any, class_id: int) -> str:
        if isinstance(names, dict):
            return str(names.get(class_id, f"class_{class_id}"))
        if isinstance(names, (list, tuple)) and 0 <= class_id < len(names):
            return str(names[class_id])
        return f"class_{class_id}"

    def class_names(self, version: Optional[str] = None) -> List[Dict[str, Any]]:
        """
        返回类别清单：优先用 weights 自带的 names，
        若权重缺失则回退到版本目录下的 classes.txt。
        """
        try:
            model = self._load_model(version)
            names = model.names
            if isinstance(names, dict):
                return [
                    {"classId": int(key), "className": str(value)}
                    for key, value in sorted(names.items())
                ]
            return [
                {"classId": index, "className": str(value)}
                for index, value in enumerate(names or [])
            ]
        except Exception as exc:  # noqa: BLE001 - 权重缺失时用类别文件兜底
            logger.warning("读取权重类别失败，回退 classes.txt: %s", exc)
            return [
                {"classId": index, "className": name}
                for index, name in enumerate(
                    self.model_manager.class_names(version or self.version)
                )
            ]

    # ── 推理 ────────────────────────────────────────────────────────────────

    def _build_predict_kwargs(
        self,
        conf: Optional[float],
        iou: Optional[float],
    ) -> Dict[str, Any]:
        kwargs: Dict[str, Any] = {
            "conf": self.conf_threshold if conf is None else float(conf),
            "iou": self.iou_threshold if iou is None else float(iou),
            "imgsz": self.image_size,
            "max_det": self.max_detections,
            "verbose": False,
        }
        if self.device:
            kwargs["device"] = self.device
        if self.target_classes is not None:
            kwargs["classes"] = self.target_classes
        return kwargs

    @staticmethod
    def _to_detections(result, names: Any) -> List[Detection]:
        boxes = getattr(result, "boxes", None)
        if boxes is None or boxes.xyxy is None:
            return []

        detections: List[Detection] = []
        for index in range(len(boxes)):
            x1, y1, x2, y2 = (float(value) for value in boxes.xyxy[index])
            class_id = int(boxes.cls[index])
            detections.append(
                Detection(
                    classId=class_id,
                    className=WildlifeDetector._class_name(names, class_id),
                    confidence=round(float(boxes.conf[index]), 4),
                    bbox=[
                        int(round(x1)),
                        int(round(y1)),
                        int(round(x2)),
                        int(round(y2)),
                    ],
                )
            )
        return detections

    def detect_array(
        self,
        image: np.ndarray,
        conf: Optional[float] = None,
        iou: Optional[float] = None,
        version: Optional[str] = None,
    ) -> List[Detection]:
        """识别一张内存中的图像（OpenCV BGR 数组）。"""
        if image is None or getattr(image, "size", 0) == 0:
            raise ValueError("图像内容为空")

        model = self._load_model(version)
        started = time.perf_counter()
        results = model.predict(source=image, **self._build_predict_kwargs(conf, iou))
        elapsed_ms = int((time.perf_counter() - started) * 1000)

        detections = self._to_detections(results[0], model.names) if results else []
        logger.debug(
            "Detected %d object(s) in %d ms (version=%s)",
            len(detections),
            elapsed_ms,
            version or self.version,
        )
        return detections

    def detect_batch(
        self,
        images: List[np.ndarray],
        conf: Optional[float] = None,
        iou: Optional[float] = None,
        version: Optional[str] = None,
    ) -> List[List[Detection]]:
        """
        批量推理：一次 predict 调用吃掉整批图像，比逐张调用吞吐更高。

        返回列表与入参顺序一一对应。ultralytics 在极少数情况下可能打乱
        结果顺序，这里用原图尺寸做一次顺序校验，校验不通过则降级为逐张推理，
        保证「结果 ↔ 图像」的对应关系绝对可靠。
        """
        if not images:
            return []

        model = self._load_model(version)
        kwargs = self._build_predict_kwargs(conf, iou)

        try:
            started = time.perf_counter()
            results = model.predict(source=list(images), **kwargs)
            elapsed_ms = int((time.perf_counter() - started) * 1000)

            if len(results) != len(images) or not self._order_matches(results, images):
                logger.warning(
                    "Batch inference result order mismatch (%d results / %d images), "
                    "falling back to per-image inference",
                    len(results),
                    len(images),
                )
                return [self.detect_array(image, conf=conf, iou=iou, version=version) for image in images]

            logger.debug("Batch inference done: %d images in %d ms", len(images), elapsed_ms)
            return [self._to_detections(result, model.names) for result in results]
        except Exception as exc:
            logger.warning("Batch inference failed, falling back to per-image: %s", exc)
            return [self.detect_array(image, conf=conf, iou=iou, version=version) for image in images]

    @staticmethod
    def _order_matches(results: List[Any], images: List[np.ndarray]) -> bool:
        if len(results) != len(images):
            return False
        for result, image in zip(results, images):
            orig_shape = getattr(result, "orig_shape", None)
            if orig_shape is None:
                continue
            height, width = image.shape[:2]
            if tuple(orig_shape) != (height, width):
                return False
        return True

    def detect(
        self,
        source: Union[str, bytes, np.ndarray],
        conf: Optional[float] = None,
        iou: Optional[float] = None,
        version: Optional[str] = None,
    ) -> List[Detection]:
        """统一入口：source 可为图像路径、字节流或内存数组。"""
        if isinstance(source, np.ndarray):
            return self.detect_array(source, conf=conf, iou=iou, version=version)
        if isinstance(source, bytes):
            return self.detect_array(
                decode_image(source), conf=conf, iou=iou, version=version
            )
        if isinstance(source, str):
            return self.detect_array(
                read_image(source), conf=conf, iou=iou, version=version
            )
        raise TypeError(f"不支持的图像输入类型: {type(source)}")
