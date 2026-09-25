import os
import logging
from dotenv import load_dotenv
from contextlib import asynccontextmanager
from fastapi import FastAPI
from app.api.health import router as health_router
from app.api.model import router as model_router
from app.services.model_manager import get_model_manager
from app.services.task_consumer import TaskConsumer

# 加载 .env 环境变量
load_dotenv(os.path.join(os.path.dirname(__file__), "..", ".env"))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)

task_consumer = TaskConsumer()


def _log_model_self_check() -> None:
    """
    启动即做一次权重自检，把结果打进启动日志。

    在此之前，权重缺失只在第一次推理时由 `ModelManager.resolve_path()` 打
    一行 `logger.warning` —— 服务照常启动、页面照常显示"当前模型 wildlife-v1.0"，
    而实际加载的可能是备用权重（例如 COCO 预训练权重，连鹿/虎/猴都没有）。
    问题要等到翻日志、或者发现检出结果全是 dog/person 时才会暴露。

    自检本身**不抛异常**，也**不阻止启动** —— 权重没就位时服务仍应能起来并
    接受任务，但这件事必须在启动日志里一眼可见。
    """
    logger = logging.getLogger("app.main")
    try:
        report = get_model_manager().validate()
    except Exception as exc:  # noqa: BLE001 - 自检失败不能拦住服务启动
        logger.error("模型自检执行失败: %s", exc)
        return

    if report["ok"]:
        logger.info(
            "模型自检通过：版本=%s 权重=%s (%d bytes, md5 %s)",
            report["version"],
            report["weightPath"],
            report["weightSizeBytes"],
            report["weightMd5"][:12],
        )
        return

    logger.warning("=" * 78)
    logger.warning("模型自检未通过：版本 %s", report["version"])
    for issue in report["issues"]:
        logger.warning("  - %s", issue)
    if report["usingFallback"]:
        logger.warning("  期望权重      : %s", report["expectedWeightPath"])
        logger.warning("  实际生效权重  : %s", report["weightPath"])
        logger.warning(
            "  该版本声明类别: %s",
            ", ".join(report["declaredClasses"]) or "(无)",
        )
        logger.warning("  => 识别结果与页面显示的模型版本**不对应**，请在对应版本目录放入 best.pt")
    logger.warning("  完整报告: GET /ai/model/status")
    logger.warning("=" * 78)


def _env_int(name: str, default: int) -> int:
    raw = (os.getenv(name) or "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        logging.getLogger("app.main").warning(
            "%s 不是整数（%r），按默认值 %d 处理", name, raw, default
        )
        return default


def _log_inference_settings() -> None:
    """
    启动时打印推理的生效参数，并对 CPU 部署的两个常见坑给出提醒。

    这两个坑**都不会报错**，只会让系统"莫名其妙变慢"，很难定位：

    - **批太大**：`_process_chunk` 先把整块图解码进内存，再一次 `predict`，
      峰值内存随批大小线性增长；CPU 上还可能把机器拖进 swap。
    - **消费线程 > 1**：每个线程持有**独立模型实例**（各自一份权重副本），
      CPU 上不会提速 —— 核心已被单次批推理用满 —— 只会成倍占内存并相互抢核。

    本函数只观察和提醒，不修改任何配置：把取舍留给使用者，但把后果说清楚。
    """
    logger = logging.getLogger("app.main")
    try:
        from app.services.wildlife_detector import get_wildlife_detector

        summary = get_wildlife_detector().runtime_summary()
    except Exception as exc:  # noqa: BLE001 - 拿不到配置不该拦住启动
        logger.warning("读取推理配置失败: %s", exc)
        return

    batch_size = _env_int("BATCH_SIZE", 8)
    workers = _env_int("RECOGNITION_WORKERS", 1)

    logger.info(
        "推理配置：设备=%s (cuda=%s, torch 线程=%s) imgsz=%s batch=%s workers=%s "
        "conf=%s iou=%s max_det=%s",
        summary["device"],
        summary["cudaAvailable"],
        summary["torchThreads"],
        summary["imageSize"],
        batch_size,
        workers,
        summary["confThreshold"],
        summary["iouThreshold"],
        summary["maxDetections"],
    )

    if summary["cudaAvailable"]:
        return

    # 以下提醒只在「无可用 GPU」时有意义
    if workers > 1:
        logger.warning(
            "无可用 GPU，但 RECOGNITION_WORKERS=%d：每个消费线程各持一份模型副本，"
            "CPU 上不会提速，只会成倍占用内存并相互抢核。建议设为 1。",
            workers,
        )
    if batch_size > 16:
        logger.warning(
            "无可用 GPU，但 BATCH_SIZE=%d：批内图像会先整块解码进内存再一次推理，"
            "峰值内存随批线性增长。CPU 上建议 4~8，内存吃紧就调小。",
            batch_size,
        )
    if summary["imageSize"] > 640:
        logger.warning(
            "无可用 GPU，且 YOLO_IMAGE_SIZE=%d：推理耗时大致随图像面积增长，"
            "从 640 提到 1280 约为 4 倍。CPU 上建议保持 640。",
            summary["imageSize"],
        )
    if summary["cpuThreadsLimit"] is None:
        logger.info(
            "提示：未设置 YOLO_CPU_THREADS，torch 将使用全部 %s 个 CPU 线程。"
            "若本机同时运行 MySQL/Redis/后端，可设该变量限制推理占用的核心数。",
            summary["torchThreads"],
        )


@asynccontextmanager
async def lifespan(app: FastAPI):
    _log_model_self_check()
    _log_inference_settings()
    task_consumer.start()
    yield
    task_consumer.stop()


app = FastAPI(title="Wildlife Recognition AI Engine", version="0.1.0", lifespan=lifespan)

app.include_router(health_router, prefix="/ai")
app.include_router(model_router, prefix="/ai")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host="0.0.0.0", port=8001, reload=True)