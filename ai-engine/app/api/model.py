"""模型权重自检接口。

**这个接口存在的理由，就是"不要撒谎"。**

后端 `model_version` 表记录的是系统**声称**启用哪个版本；而权重文件到底在不在、
推理时实际加载的是哪一份，只有 AI 引擎自己知道。当版本目录里没有 `best.pt`
时，`ModelManager.resolve_path()` 会回退到 `YOLO_MODEL_PATH` 指向的备用权重，
并且只写一行 `logger.warning` —— 日志没人看，于是页面上依旧显示
"当前模型：wildlife-v1.0"，实际跑的却是另一份权重（例如 COCO 预训练权重，
连鹿/虎/猴这些类别都没有）。

`ModelManager.validate()` 早就算出了这份真相（`usingFallback` / `weightMd5` /
`issues`），但在本模块出现之前**没有任何调用方**：能力一直在，只是没有任何
出口。这里把它暴露成 HTTP 接口，让前端可以据此显示明确的告警。

经 nginx 反代访问（见 `frontend/nginx.conf` 的 `location /ai/`）：

    GET http://localhost:9095/ai/model/status
"""
import logging
from typing import Any, Dict, Optional

from fastapi import APIRouter, Query

from app.services.model_manager import get_model_manager

logger = logging.getLogger(__name__)

router = APIRouter()


@router.get("/model/status")
async def model_status(
    version: Optional[str] = Query(
        default=None,
        description="要体检的版本；缺省为引擎当前启用版本",
    )
) -> Dict[str, Any]:
    """
    当前权重的真实状态。

    `usingFallback` 为真即表示：**页面上的版本号与实际生效的权重不是同一个东西**。
    `ok` 为假时 `issues` 里逐条说明原因。
    """
    manager = get_model_manager()
    requested = version or manager.get_active_version()
    report = manager.validate(version)

    return {
        "service": "wildlife-recognition-ai-engine",
        "modelRoot": report["modelRoot"],
        "activeVersion": manager.get_active_version(),
        "requestedVersion": requested,
        # 真相三件套
        "usingFallback": report["usingFallback"],
        "expectedWeightPath": report["expectedWeightPath"],
        "effectiveWeight": {
            "path": report["weightPath"],
            "exists": report["weightExists"],
            "sizeBytes": report["weightSizeBytes"],
            "md5": report["weightMd5"],
        },
        # 该版本声明的类别（来自 classes.txt / meta.json），与实际检出类别可能不一致
        "declaredClasses": report["declaredClasses"],
        "declaredClassCount": report["declaredClassCount"],
        "issues": report["issues"],
        "ok": report["ok"],
        # 磁盘上有哪些版本真的有权重
        "versions": [
            {
                "version": spec["version"],
                "available": spec["available"],
                "modelPath": spec["model_path"],
                "map50": spec["map50"],
                "map5095": spec["map5095"],
                # 指标是否可核验：None/False 表示只是 meta.json 里的记录，
                # 没有权重与训练日志可佐证，不应作为实测性能引用
                "metricsVerified": spec["metrics_verified"],
                "metricsNote": spec["metrics_note"],
            }
            for spec in manager.list_specs()
        ],
    }


@router.get("/model/summary")
async def model_summary() -> Dict[str, Any]:
    """所有版本的登记摘要（发现结果 + 启用版本），用于排查"权重放哪了"。"""
    return get_model_manager().summary()