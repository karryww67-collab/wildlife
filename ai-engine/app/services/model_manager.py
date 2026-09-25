"""
模型版本管理器

负责野生动物识别权重的多版本登记与切换：
`wildlife-v1.0` / `wildlife-v1.1` / `wildlife-v2.0`

权重目录约定（根目录取环境变量 `MODEL_ROOT`，默认 `ai-engine/models`）：

    models/
    ├── wildlife-v1.0/
    │   ├── best.pt                    权重（必须）
    │   ├── classes.txt                类别名，一行一个（可选）
    │   └── meta.json                  指标元数据（可选）
    ├── wildlife-v1.1/
    │   └── best.pt
    ├── wildlife-v1.0.pt               平铺形式（兼容）
    ├── wildlife-v1.0.classes.txt      平铺形式的类别文件
    ├── wildlife-v1.0.meta.json        平铺形式的元数据
    └── wildlife-v2.0/
        └── best.pt

平铺形式的类别/元数据必须带版本名前缀：多个平铺权重平放在 models/ 下时，
若共用一份 `classes.txt`，各版本的类别表会互相串味。

**权重文件必须以二进制方式拷贝/传输。**
用文本模式（`open(p).read()` 再写出、编辑器另存、CRLF 规范化工具）碰过
`best.pt`，文件只会少掉几十字节，肉眼和 md5 都比不出异常，
但 zip 内所有偏移量会整体错位，torch 从此再也加载不了这个权重。
落地前用 `check_weights.py` 或本模块的 `inspect_weight()` / `validate()` 体检一次。

本模块只负责「有哪些版本、权重在哪、当前启用哪个、权重是否可用」，
不做推理（推理归 wildlife_detector），也不做批量编排（归 batch_processor）。
"""
import hashlib
import json
import logging
import os
import zipfile
from dataclasses import asdict, dataclass
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

WEIGHT_FILE_NAME = "best.pt"
CLASS_FILE_NAME = "classes.txt"
META_FILE_NAME = "meta.json"

# 内置版本清单：目录里还没放权重时也会列出来，便于提示补齐
BUILTIN_VERSIONS = ("wildlife-v1.0", "wildlife-v1.1", "wildlife-v2.0")
DEFAULT_ACTIVE_VERSION = "wildlife-v1.0"

_manager_instance: Optional["ModelManager"] = None


def get_model_manager() -> "ModelManager":
    global _manager_instance
    if _manager_instance is None:
        _manager_instance = ModelManager()
    return _manager_instance


def _default_model_root() -> str:
    # app/services/model_manager.py -> app/services -> app -> ai-engine
    engine_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    return os.path.join(engine_root, "models")


@dataclass
class ModelSpec:
    """单个模型版本的描述信息，字段与后端 model_version 表对应。"""

    version: str
    model_path: str = ""
    available: bool = False
    model_name: str = "wildlife-detector"
    description: str = ""
    class_config: Optional[str] = None
    precision_value: Optional[float] = None
    recall_value: Optional[float] = None
    map50: Optional[float] = None
    map5095: Optional[float] = None
    # 指标是否由真实评测产生。meta.json 里人手写的数字没有这一项（None），
    # 只有 scripts/evaluate.py 写出的才会是 True —— 用来区分"实测"与"记录"。
    metrics_verified: Optional[bool] = None
    metrics_note: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class ModelManager:
    """模型版本登记表。"""

    def __init__(self, model_root: Optional[str] = None):
        self.model_root = os.path.abspath(
            model_root or os.getenv("MODEL_ROOT") or _default_model_root()
        )
        self.fallback_weight_path = os.getenv(
            "YOLO_MODEL_PATH",
            os.path.join(self.model_root, DEFAULT_ACTIVE_VERSION, WEIGHT_FILE_NAME),
        )
        self._specs: Dict[str, ModelSpec] = {}
        self.active_version = os.getenv(
            "ACTIVE_MODEL_VERSION", DEFAULT_ACTIVE_VERSION
        )
        # 构造时不做磁盘扫描，首次访问时惰性发现
        self._discovered = False

    # ── 发现与查询 ──────────────────────────────────────────────────────────

    def _discover(self) -> None:
        self._discovered = True
        self._specs = {}

        for version in BUILTIN_VERSIONS:
            self._specs[version] = ModelSpec(version=version)

        if not os.path.isdir(self.model_root):
            logger.warning("模型根目录不存在: %s", self.model_root)
            return

        for entry in sorted(os.listdir(self.model_root)):
            entry_path = os.path.join(self.model_root, entry)

            # 目录形式：<model_root>/<version>/
            if os.path.isdir(entry_path):
                version = entry
                weight_path = os.path.join(entry_path, WEIGHT_FILE_NAME)
                has_weight = os.path.exists(weight_path)
                # 既没有权重、也没有类别/元数据文件的目录不算模型版本，
                # 避免 models/ 下随便放个目录就冒出一个"版本"
                if not has_weight and not any(
                    os.path.exists(path) for path in self.side_paths(version)
                ):
                    continue
            # 平铺形式：<model_root>/<version>.pt
            elif entry.lower().endswith(".pt"):
                weight_path = entry_path
                version = os.path.splitext(entry)[0]
                has_weight = True
            else:
                continue

            # 权重缺失的版本**同样登记**：classes.txt / meta.json 往往先于权重就位，
            # 而"这个版本声称能识别哪些类别"恰恰是权重到位前最该知道的信息。
            # 早期实现在这里直接 continue，后果有两个：validate() 会谎报
            # "缺少 classes.txt"（文件其实就摆在那儿），meta.json 的指标也永远读不到；
            # 于是"声明了什么"这件事在权重缺失时反而完全不可见。
            spec = ModelSpec(
                version=version,
                available=has_weight,
                model_path=weight_path if has_weight else "",
            )
            self._apply_side_files(spec, *self.side_paths(version))
            self._specs[version] = spec

        available = [version for version, spec in self._specs.items() if spec.available]
        logger.info(
            "Model discovery done: %d version(s) available under %s (%s)",
            len(available),
            self.model_root,
            ", ".join(available) if available else "none",
        )

    def side_paths(self, version: str) -> Tuple[str, str]:
        """
        返回某版本的 (类别文件候选路径, 元数据候选路径)。

        目录形式与平铺形式的差异只在这里体现一次，
        发现、类别读取、体检都复用本方法，避免三处各写一套路径拼装。
        """
        directory = os.path.join(self.model_root, version)
        if os.path.isdir(directory):
            return (
                os.path.join(directory, CLASS_FILE_NAME),
                os.path.join(directory, META_FILE_NAME),
            )
        return (
            os.path.join(self.model_root, f"{version}.{CLASS_FILE_NAME}"),
            os.path.join(self.model_root, f"{version}.{META_FILE_NAME}"),
        )

    def _apply_side_files(
        self, spec: ModelSpec, class_file: str, meta_file: str
    ) -> None:
        """读取可选的 classes.txt / meta.json 补充元数据。"""
        if os.path.exists(class_file):
            spec.class_config = class_file

        if not os.path.exists(meta_file):
            return

        try:
            with open(meta_file, "r", encoding="utf-8") as handle:
                meta = json.load(handle)
        except Exception as exc:  # noqa: BLE001 - 元数据坏了不影响权重可用
            logger.warning("读取模型元数据失败 %s: %s", meta_file, exc)
            return

        spec.model_name = meta.get("modelName", spec.model_name)
        spec.description = meta.get("description", spec.description)
        spec.precision_value = meta.get("precisionValue", spec.precision_value)
        spec.recall_value = meta.get("recallValue", spec.recall_value)
        spec.map50 = meta.get("map50", spec.map50)
        spec.map5095 = meta.get("map5095", spec.map5095)
        spec.metrics_verified = meta.get("metricsVerified", spec.metrics_verified)
        spec.metrics_note = meta.get("metricsNote", spec.metrics_note)
        if not spec.class_config and meta.get("classConfig"):
            spec.class_config = meta["classConfig"]

    def refresh(self) -> List[str]:
        """重新扫描磁盘，返回可用版本列表。"""
        self._discover()
        return self.list_versions(only_available=True)

    def list_versions(self, only_available: bool = False) -> List[str]:
        self._ensure_discovered()
        versions = [
            version
            for version, spec in self._specs.items()
            if not only_available or spec.available
        ]
        return sorted(versions)

    def list_specs(self) -> List[Dict[str, Any]]:
        self._ensure_discovered()
        return [self._specs[version].to_dict() for version in self.list_versions()]

    def get_spec(self, version: str) -> ModelSpec:
        self._ensure_discovered()
        spec = self._specs.get(version)
        if spec is None:
            raise KeyError(
                f"未知模型版本: {version}，可用版本: {', '.join(self.list_versions()) or '无'}"
            )
        return spec

    # ── 权重路径 ────────────────────────────────────────────────────────────

    def resolve_path(self, version: Optional[str] = None, allow_fallback: bool = True) -> str:
        """
        解析某个版本的权重绝对路径。

        权重缺失时若 allow_fallback 为真，则回退到 `YOLO_MODEL_PATH`
        （默认 `models/wildlife-v1.0/best.pt`），保证权重尚未就位时服务仍可启动自检。
        """
        version = version or self.active_version
        spec = self.get_spec(version)
        if spec.model_path and os.path.exists(spec.model_path):
            return spec.model_path

        if allow_fallback:
            fallback = os.path.abspath(self.fallback_weight_path)
            if os.path.exists(fallback):
                logger.warning(
                    "版本 %s 权重缺失（期望 %s），回退到备用权重 %s",
                    version,
                    spec.model_path or f"{self.model_root}/{version}/{WEIGHT_FILE_NAME}",
                    fallback,
                )
                return fallback

        expected = spec.model_path or os.path.join(
            self.model_root, version, WEIGHT_FILE_NAME
        )
        raise FileNotFoundError(
            f"模型版本 {version} 的权重不存在: {expected}。"
            f"请把 best.pt 放到 {os.path.join(self.model_root, version)}/ 下。"
        )

    # ── 版本切换 ────────────────────────────────────────────────────────────

    def get_active_version(self) -> str:
        return self.active_version

    def set_active_version(self, version: str) -> ModelSpec:
        """
        切换当前启用版本。

        注意：本方法只切换 AI 引擎进程内的启用版本；
        系统层面「哪个模型是启用状态」的权威记录在后端 `model_version` 表，
        由后端在切换模型时同步调用本接口。
        """
        spec = self.get_spec(version)
        if not spec.available:
            logger.warning(
                "切换到版本 %s，但权重文件缺失，实际推理会回退到备用权重", version
            )
        self.active_version = version
        logger.info("Active model version switched to %s", version)
        return spec

    def switch(self, version: str) -> ModelSpec:
        """set_active_version 的别名。"""
        return self.set_active_version(version)

    # ── 类别与摘要 ──────────────────────────────────────────────────────────

    def class_names(self, version: Optional[str] = None) -> List[str]:
        """读取版本的类别名清单（classes.txt 或 meta.json 里的 classes）。"""
        spec = self.get_spec(version or self.active_version)
        names: List[str] = []

        if spec.class_config and os.path.exists(spec.class_config):
            try:
                with open(spec.class_config, "r", encoding="utf-8") as handle:
                    names = [line.strip() for line in handle if line.strip()]
            except Exception as exc:  # noqa: BLE001
                logger.warning("读取类别文件失败 %s: %s", spec.class_config, exc)

        if not names:
            meta_file = self.side_paths(spec.version)[1]
            if os.path.exists(meta_file):
                try:
                    with open(meta_file, "r", encoding="utf-8") as handle:
                        names = json.load(handle).get("classes", []) or []
                except Exception:  # noqa: BLE001 - 类别只是辅助信息
                    names = []

        return names

    # ── 权重体检 ────────────────────────────────────────────────────────────

    @staticmethod
    def file_md5(path: str, chunk_size: int = 1 << 20) -> str:
        """算权重文件摘要，用于发现「多版本其实是同一个文件」。"""
        digest = hashlib.md5()
        try:
            with open(path, "rb") as handle:
                while True:
                    block = handle.read(chunk_size)
                    if not block:
                        break
                    digest.update(block)
        except OSError as exc:
            logger.warning("计算文件摘要失败 %s: %s", path, exc)
            return ""
        return digest.hexdigest()

    @staticmethod
    def inspect_weight(path: str) -> List[str]:
        """
        体检权重文件结构，返回问题清单；空列表表示可以交给 torch 加载。

        只用标准库实现，**不需要装 torch** 也能跑，所以容器构建期、启动自检、
        本地排查都能直接调用。重点拦住一类很难靠肉眼发现的事故：

        权重被文本模式读写过（`open(p).read()` 再写出、编辑器另存、
        CRLF 规范化工具）时，文件只少掉几十字节，体积和 md5 都"看着正常"，
        但 zip 内部所有偏移量已整体错位，torch 从此一定加载失败。
        """
        issues: List[str] = []
        if not path:
            return ["权重路径为空"]
        if not os.path.exists(path):
            return [f"权重文件不存在: {path}"]
        if not os.path.isfile(path):
            return [f"权重路径不是文件: {path}"]

        size = os.path.getsize(path)
        if size == 0:
            return [f"权重文件为空（0 字节）: {path}"]
        if size < 1024:
            return [f"权重文件过小（{size} 字节），不像模型权重: {path}"]

        with open(path, "rb") as handle:
            head = handle.read(4)
        if head[:2] != b"PK":
            return [
                f"权重不是 zip 容器（文件头 {head.hex()}）: {path}；"
                f".pt 权重应为 PK 开头的 zip"
            ]

        try:
            with zipfile.ZipFile(path) as archive:
                members = archive.namelist()
                broken = archive.testzip()
        except zipfile.BadZipFile as exc:
            issues.append(
                f"权重 zip 结构损坏（{exc}）: {path}；"
                f"常见原因是文件被文本模式读写过，导致 zip 内偏移整体错位，"
                f"请重新用二进制方式拷贝一份权重"
            )
            return issues
        except Exception as exc:  # noqa: BLE001
            issues.append(f"读取权重 zip 失败（{type(exc).__name__}: {exc}）: {path}")
            return issues

        if broken:
            issues.append(f"权重成员 {broken} 的 CRC 校验失败，内容已损坏: {path}")
        if not any(name.endswith("data.pkl") for name in members):
            issues.append(f"权重缺少 data.pkl，不是 torch 保存的权重: {path}")
        if not any(name.rsplit("/", 1)[-1].isdigit() for name in members):
            issues.append(f"权重缺少 data/N 张量成员: {path}")
        return issues

    def duplicate_weights(self) -> Dict[str, List[str]]:
        """
        找出内容完全相同（md5 一致）的可用版本。

        返回 `{md5: [版本…]}`，只保留出现两次以上的。
        多个版本指同一个文件，等于「多版本切换」是空的——切换后模型行为不会变，
        但界面上会显示成两个不同的模型，属于必须修掉的假象。
        """
        self._ensure_discovered()
        groups: Dict[str, List[str]] = {}
        for version in self.list_versions(only_available=True):
            spec = self.get_spec(version)
            if not spec.model_path:
                continue
            digest = self.file_md5(spec.model_path)
            if digest:
                groups.setdefault(digest, []).append(version)
        return {digest: items for digest, items in groups.items() if len(items) > 1}

    def validate(self, version: Optional[str] = None) -> Dict[str, Any]:
        """
        对一个版本做完整体检，返回结构化报告；本方法**不抛异常**。

        报告里 `ok` 为真表示：权重存在、结构完整、且没有落到备用权重上。
        """
        target = version or self.active_version
        report: Dict[str, Any] = {
            "version": target,
            "modelRoot": self.model_root,
            "expectedWeightPath": "",
            "weightPath": "",
            "weightExists": False,
            "weightSizeBytes": 0,
            "weightMd5": "",
            "usingFallback": False,
            "declaredClasses": [],
            "declaredClassCount": 0,
            "duplicateWith": [],
            "issues": [],
            "ok": False,
        }

        try:
            spec = self.get_spec(target)
        except KeyError as exc:
            report["issues"].append(str(exc))
            return report

        expected = spec.model_path or os.path.join(
            self.model_root, target, WEIGHT_FILE_NAME
        )
        report["expectedWeightPath"] = expected
        resolved = expected
        if not os.path.exists(expected):
            fallback = os.path.abspath(self.fallback_weight_path)
            if os.path.exists(fallback):
                resolved = fallback
                report["usingFallback"] = True
                report["issues"].append(
                    f"版本 {target} 的权重缺失（期望 {expected}），"
                    f"实际会回退使用备用权重 {fallback}；"
                    f"此时识别结果与版本号不对应"
                )
            else:
                report["issues"].append(f"版本 {target} 的权重不存在: {expected}")
                return report

        report["weightPath"] = resolved
        report["weightExists"] = True
        report["weightSizeBytes"] = os.path.getsize(resolved)
        report["weightMd5"] = self.file_md5(resolved)
        report["issues"].extend(self.inspect_weight(resolved))

        declared = self.class_names(target)
        report["declaredClasses"] = declared
        report["declaredClassCount"] = len(declared)
        if not declared:
            report["issues"].append(
                f"版本 {target} 未声明类别清单，缺少 "
                f"{self.side_paths(target)[0]}（或 meta.json 的 classes）"
            )

        duplicates = self.duplicate_weights()
        for digest, versions in duplicates.items():
            if target in versions:
                others = [item for item in versions if item != target]
                report["duplicateWith"] = others
                report["issues"].append(
                    f"版本 {target} 与 {', '.join(others)} 是同一个权重文件"
                    f"（md5 {digest[:12]}），多版本实际不生效"
                )

        report["ok"] = not report["issues"]
        return report

    def summary(self) -> Dict[str, Any]:
        """诊断用摘要。"""
        return {
            "modelRoot": self.model_root,
            "activeVersion": self.active_version,
            "versions": self.list_specs(),
        }

    def _ensure_discovered(self) -> None:
        if not self._discovered:
            self._discover()
