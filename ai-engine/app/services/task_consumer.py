"""
批量图像识别任务消费者

Redis:
    wildlife:tasks
        ↓
    TaskConsumer
        ↓
    BatchProcessor
        ↓
    WildlifeDetector.detect_batch()
        ↓
    wildlife:results

任务消息格式：

{
    "taskId": 10001,
    "modelId": 2,
    "modelVersion": "wildlife-v1.1",
    "modelPath": "/models/wildlife-v1.1/best.pt",
    "images": [
        {
            "imageId": 2048,
            "filePath": "/shared-data/originals/20260921/a.jpg"
        },
        {
            "imageId": 2049,
            "filePath": "/shared-data/originals/20260921/b.jpg"
        }
    ]
}

说明：

1. TaskConsumer 只负责：
   - 从 Redis 获取任务
   - 解析任务
   - 把任务交给 BatchProcessor

2. BatchProcessor 负责：
   - 按 BATCH_SIZE 分块
   - 读取图片
   - 调用 WildlifeDetector.detect_batch()
   - 逐张回写结果

3. WildlifeDetector 负责：
   - 加载 YOLO 模型
   - batch inference
   - 返回检测结果

4. 模型版本：
   后端创建任务时已经传入 modelVersion。
   这里把 modelVersion 传给 BatchProcessor，
   最终由 WildlifeDetector 根据版本加载对应 best.pt。

5. modelPath 主要用于日志和排查。
   真正选择模型优先使用 modelVersion。
"""

import json
import logging
import os
import threading
import time
from typing import Any, Dict, List, Optional

import redis

from app.services.batch_processor import BatchProcessor
from app.services.model_manager import get_model_manager
from app.services.result_callback import ResultCallback


logger = logging.getLogger(__name__)

DEFAULT_TASK_QUEUE = "wildlife:tasks"


class TaskConsumer:

    def __init__(self):

        self.redis_client = redis.Redis(
            host=os.getenv("REDIS_HOST", "localhost"),
            port=int(os.getenv("REDIS_PORT", 6379)),
            db=int(os.getenv("REDIS_DATABASE", 9)),
            decode_responses=True,
        )

        self.task_queue = os.getenv(
            "TASK_QUEUE_KEY",
            DEFAULT_TASK_QUEUE
        )

        # Worker 数量
        #
        # 一个 worker 对应一个 Redis 消费线程。
        #
        # CPU 环境建议：
        # RECOGNITION_WORKERS=1
        #
        # GPU 环境后面可以根据显存调整。
        self.worker_count = max(
            1,
            int(
                os.getenv(
                    "RECOGNITION_WORKERS",
                    "1"
                )
            )
        )

        self.running = False

        self._threads: List[threading.Thread] = []

    # ------------------------------------------------------------------
    # 启动 / 停止
    # ------------------------------------------------------------------

    def start(self):

        if self.running:
            return

        self.running = True

        self._threads.clear()

        for worker_index in range(
            self.worker_count
        ):

            thread = threading.Thread(
                target=self._poll_loop,
                args=(worker_index,),
                daemon=True,
                name=f"task-consumer-{worker_index}",
            )

            thread.start()

            self._threads.append(thread)

        logger.info(
            "Task consumer started: "
            "%d worker(s), queue=%s",
            self.worker_count,
            self.task_queue,
        )

    def stop(self):

        self.running = False

        logger.info(
            "Task consumer stopped"
        )

    # ------------------------------------------------------------------
    # Redis 消费循环
    # ------------------------------------------------------------------

    def _poll_loop(
        self,
        worker_index: int
    ):

        callback = ResultCallback()

        # 每个 worker 创建一个 BatchProcessor。
        #
        # BatchProcessor 内部会持有一个 WildlifeDetector。
        #
        # 这样：
        #
        # Redis
        #   ↓
        # worker
        #   ↓
        # BatchProcessor
        #   ↓
        # YOLO
        #
        batch_processor = BatchProcessor(
            callback=callback
        )

        logger.info(
            "Worker %d ready",
            worker_index
        )

        while self.running:

            try:

                message = self.redis_client.blpop(
                    self.task_queue,
                    timeout=5
                )

                if message is None:
                    continue

                _, raw = message

                task_data = json.loads(raw)

                self._process_message(
                    task_data,
                    batch_processor,
                    callback
                )

            except json.JSONDecodeError as exc:

                logger.error(
                    "Invalid task message: %s",
                    exc
                )

            except Exception as exc:

                logger.error(
                    "Error in task consumer: %s",
                    exc,
                    exc_info=True
                )

                if self.running:
                    time.sleep(2)

    # ------------------------------------------------------------------
    # 任务处理
    # ------------------------------------------------------------------

    def _process_message(
        self,
        task_data: Dict[str, Any],
        batch_processor: BatchProcessor,
        callback: ResultCallback,
    ):
        """
        解析 Redis 任务。

        最终真正的识别工作全部交给 BatchProcessor。
        """

        if not isinstance(
            task_data,
            dict
        ):
            logger.error(
                "Invalid task data: %r",
                task_data
            )
            return

        # --------------------------------------------------------------
        # taskId
        # --------------------------------------------------------------

        raw_task_id = task_data.get(
            "taskId"
        )

        if raw_task_id is None:

            logger.error(
                "Task message missing taskId: %s",
                task_data
            )

            return

        try:

            task_id = int(
                raw_task_id
            )

        except (
            TypeError,
            ValueError
        ):

            logger.error(
                "Invalid taskId: %r",
                raw_task_id
            )

            return

        # --------------------------------------------------------------
        # images
        # --------------------------------------------------------------

        images = self._parse_images(
            task_data
        )

        if not images:

            logger.error(
                "Task %s has no usable images",
                task_id
            )

            return

        # --------------------------------------------------------------
        # 模型版本
        # --------------------------------------------------------------

        model_version = self._resolve_model_version(
            task_data
        )

        model_path = self._resolve_model_path(
            task_data,
            model_version
        )

        logger.info(
            "Task %d received: "
            "images=%d, "
            "modelId=%s, "
            "modelVersion=%s, "
            "modelPath=%s",
            task_id,
            len(images),
            task_data.get("modelId"),
            model_version or "-",
            model_path or "-",
        )

        # --------------------------------------------------------------
        # 真正进入 BatchProcessor
        # --------------------------------------------------------------

        self._process_task(
            task_id=task_id,
            images=images,
            batch_processor=batch_processor,
            callback=callback,
            model_version=model_version,
        )

    # ------------------------------------------------------------------
    # 图片解析
    # ------------------------------------------------------------------

    @staticmethod
    def _parse_images(
        task_data: Dict[str, Any]
    ) -> List[Dict[str, Any]]:
        """
        只接受：

        {
            "imageId": 1,
            "filePath": "/xxx/a.jpg"
        }

        imageId 和 filePath 缺一不可。
        """

        images = task_data.get(
            "images"
        )

        if isinstance(
            images,
            list
        ) and images:

            usable = []

            for image in images:

                if not isinstance(
                    image,
                    dict
                ):
                    continue

                image_id = image.get(
                    "imageId"
                )

                file_path = (
                    image.get("filePath")
                    or image.get("imagePath")
                    or ""
                )

                if (
                    image_id is not None
                    and file_path
                ):

                    usable.append(
                        {
                            "imageId": image_id,
                            "filePath": file_path,
                        }
                    )

            dropped = (
                len(images)
                - len(usable)
            )

            if dropped:

                logger.warning(
                    "Ignored %d invalid image "
                    "items: missing imageId "
                    "or filePath",
                    dropped
                )

            return usable

        # --------------------------------------------------------------
        # 兼容单张任务消息
        # --------------------------------------------------------------

        single_path = (
            task_data.get("filePath")
            or task_data.get("imagePath")
            or ""
        )

        image_id = task_data.get(
            "imageId"
        )

        if (
            image_id is not None
            and single_path
        ):

            return [
                {
                    "imageId": image_id,
                    "filePath": single_path,
                }
            ]

        return []

    # ------------------------------------------------------------------
    # 模型版本
    # ------------------------------------------------------------------

    @staticmethod
    def _resolve_model_version(
        task_data: Dict[str, Any]
    ) -> Optional[str]:

        version = task_data.get(
            "modelVersion"
        )

        if isinstance(
            version,
            str
        ):

            version = version.strip()

            if version:
                return version

        return None

    @staticmethod
    def _resolve_model_path(
        task_data: Dict[str, Any],
        model_version: Optional[str]
    ) -> Optional[str]:

        # 后端已经把 modelPath 放进任务消息。
        explicit = task_data.get(
            "modelPath"
        )

        if isinstance(
            explicit,
            str
        ):

            explicit = explicit.strip()

            if explicit:
                return explicit

        # 如果没有 modelPath，
        # 根据 modelVersion 解析。
        if model_version:

            try:

                return get_model_manager().resolve_path(
                    model_version
                )

            except Exception as exc:

                logger.warning(
                    "Cannot resolve model "
                    "version %s: %s",
                    model_version,
                    exc
                )

        return None

    # ------------------------------------------------------------------
    # BatchProcessor
    # ------------------------------------------------------------------

    def _process_task(
        self,
        task_id: int,
        images: List[Dict[str, Any]],
        batch_processor: BatchProcessor,
        callback: ResultCallback,
        model_version: Optional[str] = None,
    ):
        """
        真正的批量识别入口。

        注意：

        这里不再：

            for image:
                YOLO 单张识别

        而是：

            BatchProcessor.process()
                ↓
            BATCH_SIZE
                ↓
            detect_batch()
        """

        try:

            summary = batch_processor.process(
                task_id=task_id,
                images=images,
                version=model_version,
                collect_results=False,
            )

            logger.info(
                "Task %d finished: "
                "total=%d, "
                "success=%d, "
                "failed=%d, "
                "elapsed=%dms, "
                "avg=%.2fms/image",
                task_id,
                summary.totalCount,
                summary.successCount,
                summary.failedCount,
                summary.elapsedMs,
                summary.avgMsPerImage,
            )

        except Exception as exc:

            logger.error(
                "Task %d batch processing failed: %s",
                task_id,
                exc,
                exc_info=True,
            )

            self._notify_task_failed(
                callback,
                task_id,
                exc
            )

    # ------------------------------------------------------------------
    # 任务级失败
    # ------------------------------------------------------------------

    @staticmethod
    def _notify_task_failed(
        callback: ResultCallback,
        task_id: int,
        exc: Exception,
    ):

        try:

            callback.send_error(
                task_id,
                str(exc)
            )

        except Exception as send_error:

            logger.error(
                "Failed to report task %s "
                "failure: %s",
                task_id,
                send_error,
            )
