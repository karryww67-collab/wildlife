"""
识别结果回写通道

架构保持不变：AI 引擎把消息 `rpush` 到 Redis 队列尾部，
后端 `RedisSubscriberService` 定时 `lpop` 消费，落 MySQL 并推进任务进度。
队列方向为先进先出，与后端消费方一致。

队列名：`wildlife:results`（原 `ai:results`）

逐张图像结果消息（后端据此落 detection_result、回写图像状态、推进任务计数）：
{
  "taskId": 10001,
  "imageId": 2048,
  "status": "SUCCESS",                      // SUCCESS / FAILED
  "errorMessage": "...",                    // status=FAILED 时才有
  "detections": [                           // status=SUCCESS 时才有
    {"classId": 3, "className": "野猪", "confidence": 0.93,
     "x1": 120, "y1": 80, "x2": 460, "y2": 390}
  ]
}

任务级消息（不带 imageId）：
  {"taskId": 10001, "status": "STARTED"}                     任务开始
  {"taskId": 10001, "status": "FAILED", "errorMessage": "..."} 整批失败

注意：
1. 任务完成（status=COMPLETED）由后端在计数达到 totalCount 时自行判定，
   AI 引擎不需要也不应该发送完成消息。
2. **逐张结果必须带 imageId**。后端靠 imageId 判断这是不是逐张结果：
   缺 imageId 的消息会被当成任务级消息，而任务级分支不认 SUCCESS，
   结果是被静默丢弃、任务进度永远走不到 100%。因此本模块在出口处
   直接把缺 imageId 的回写拦下（记录错误日志并跳过），不让脏消息进队列。
"""
import json
import logging
import os
from typing import Any, Dict, List, Optional

import redis

logger = logging.getLogger(__name__)

DEFAULT_RESULT_QUEUE = "wildlife:results"


class ResultCallback:
    def __init__(self):
        self.redis_client = redis.Redis(
            host=os.getenv("REDIS_HOST", "localhost"),
            port=int(os.getenv("REDIS_PORT", 6379)),
            db=int(os.getenv("REDIS_DATABASE", 9)),
            decode_responses=True,
        )
        self.result_queue = os.getenv("RESULT_QUEUE_KEY", DEFAULT_RESULT_QUEUE)

    # ── 底层推送 ────────────────────────────────────────────────────────────

    def _push(self, payload: Dict[str, Any]) -> None:
        self.redis_client.rpush(self.result_queue, json.dumps(payload, ensure_ascii=False))

    # ── 任务级消息 ──────────────────────────────────────────────────────────

    def send_status(self, task_id: int, status: str) -> None:
        """发送任务级状态（如 STARTED / PROCESSING）。"""
        self._push({"taskId": task_id, "status": status})
        logger.info("Sent task status: taskId=%s, status=%s", task_id, status)

    def send_task_started(self, task_id: int) -> None:
        """通知后端任务已开始处理，后端据此把任务从 PENDING 置为 PROCESSING。"""
        self.send_status(task_id, "STARTED")

    def send_error(self, task_id: int, error: str) -> None:
        """整批处理失败：不带 imageId 的任务级失败消息。"""
        self._push({"taskId": task_id, "status": "FAILED", "errorMessage": error})
        logger.warning("Sent task failure: taskId=%s, error=%s", task_id, error)

    # ── 逐张图像结果 ────────────────────────────────────────────────────────

    @staticmethod
    def _valid_image_id(image_id: Any) -> bool:
        """
        逐张结果必须能定位到一张图像，否则后端无法落库、无法推进进度。

        imageId 缺失（None）时后端会把消息当成任务级消息并丢弃，
        所以这里必须在出口拦下，而不是让它进队列。
        """
        return isinstance(image_id, int) and not isinstance(image_id, bool)

    def send_image_result(
        self,
        task_id: int,
        image_id: int,
        detections: Optional[List[Dict[str, Any]]] = None,
    ) -> bool:
        """
        发送单张图像的识别结果。

        detections 为检测框列表，可为空数组（表示该图未检出目标，
        同样算作这张图处理成功，后端需要据此推进任务计数）。

        imageId 缺失时不下发，返回 False（见模块说明的第 2 条）。
        """
        if not self._valid_image_id(image_id):
            logger.error(
                "忽略缺 imageId 的识别结果: taskId=%s, imageId=%r，"
                "后端无法据此推进任务进度",
                task_id,
                image_id,
            )
            return False

        payload = {
            "taskId": task_id,
            "imageId": image_id,
            "status": "SUCCESS",
            "detections": detections or [],
        }
        self._push(payload)
        logger.info(
            "Sent result: taskId=%s, imageId=%s, detections=%d",
            task_id,
            image_id,
            len(payload["detections"]),
        )
        return True

    def send_image_failed(self, task_id: int, image_id: int, error: str) -> bool:
        """单张图像识别失败：由后端计入 failedCount 并回写图像错误信息。"""
        if not self._valid_image_id(image_id):
            logger.error(
                "忽略缺 imageId 的失败上报: taskId=%s, imageId=%r，"
                "该张图像会一直停在未完成状态",
                task_id,
                image_id,
            )
            return False

        payload = {
            "taskId": task_id,
            "imageId": image_id,
            "status": "FAILED",
            "errorMessage": error,
        }
        self._push(payload)
        logger.warning(
            "Sent image failure: taskId=%s, imageId=%s, error=%s",
            task_id,
            image_id,
            error,
        )
        return True
