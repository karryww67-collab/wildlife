import json
from unittest.mock import MagicMock

import pytest

from app.services import task_consumer as task_consumer_module
from app.services.task_consumer import TaskConsumer


# ============================================================
# 辅助函数
# ============================================================

def create_consumer(monkeypatch):
    """
    创建 TaskConsumer，但不真正连接 Redis。
    """

    fake_redis = MagicMock()

    monkeypatch.setattr(
        task_consumer_module.redis,
        "Redis",
        lambda **kwargs: fake_redis
    )

    consumer = TaskConsumer()

    return consumer, fake_redis


def create_batch_processor():
    """
    创建假的 BatchProcessor。

    这里不加载真正 YOLO 模型，
    只验证 TaskConsumer 是否正确调用 BatchProcessor。
    """

    processor = MagicMock()

    processor.batch_size = 8

    summary = MagicMock()

    summary.totalCount = 3
    summary.successCount = 3
    summary.failedCount = 0
    summary.elapsedMs = 100
    summary.avgMsPerImage = 33.33

    processor.process.return_value = summary

    return processor, summary


# ============================================================
# 1. TaskConsumer 初始化
# ============================================================

def test_consumer_initialization(monkeypatch):

    consumer, fake_redis = create_consumer(
        monkeypatch
    )

    assert consumer.running is False

    assert consumer.task_queue == "wildlife:tasks"

    assert consumer.worker_count >= 1


# ============================================================
# 2. 正常解析图片
# ============================================================

def test_parse_images():

    task_data = {
        "taskId": 100,
        "images": [
            {
                "imageId": 1,
                "filePath": "/shared-data/a.jpg"
            },
            {
                "imageId": 2,
                "filePath": "/shared-data/b.jpg"
            },
            {
                "imageId": 3,
                "filePath": "/shared-data/c.jpg"
            }
        ]
    }

    images = TaskConsumer._parse_images(
        task_data
    )

    assert len(images) == 3

    assert images[0]["imageId"] == 1

    assert images[0]["filePath"] == "/shared-data/a.jpg"


# ============================================================
# 3. 过滤非法图片
# ============================================================

def test_parse_images_ignore_invalid():

    task_data = {
        "taskId": 100,
        "images": [
            {
                "imageId": 1,
                "filePath": "/shared-data/a.jpg"
            },
            {
                "imageId": None,
                "filePath": "/shared-data/b.jpg"
            },
            {
                "imageId": 3,
                "filePath": ""
            },
            {
                "imageId": 4,
                "filePath": "/shared-data/d.jpg"
            }
        ]
    }

    images = TaskConsumer._parse_images(
        task_data
    )

    assert len(images) == 2

    assert images[0]["imageId"] == 1

    assert images[1]["imageId"] == 4


# ============================================================
# 4. 单张任务兼容
# ============================================================

def test_parse_single_image():

    task_data = {
        "taskId": 100,
        "imageId": 10,
        "filePath": "/shared-data/a.jpg"
    }

    images = TaskConsumer._parse_images(
        task_data
    )

    assert len(images) == 1

    assert images[0]["imageId"] == 10

    assert images[0]["filePath"] == "/shared-data/a.jpg"


# ============================================================
# 5. 没有图片
# ============================================================

def test_parse_images_empty():

    task_data = {
        "taskId": 100,
        "images": []
    }

    images = TaskConsumer._parse_images(
        task_data
    )

    assert images == []


# ============================================================
# 6. 正确解析 modelVersion
# ============================================================

def test_resolve_model_version():

    task_data = {
        "taskId": 100,
        "modelVersion": "wildlife-v1.1"
    }

    version = (
        TaskConsumer._resolve_model_version(
            task_data
        )
    )

    assert version == "wildlife-v1.1"


# ============================================================
# 7. modelVersion 不存在
# ============================================================

def test_resolve_model_version_empty():

    task_data = {
        "taskId": 100
    }

    version = (
        TaskConsumer._resolve_model_version(
            task_data
        )
    )

    assert version is None


# ============================================================
# 8. 正常处理一个 3 图片任务
# ============================================================

def test_process_message_success(
    monkeypatch
):

    consumer, _ = create_consumer(
        monkeypatch
    )

    processor, summary = (
        create_batch_processor()
    )

    callback = MagicMock()

    task_data = {
        "taskId": 10001,
        "modelId": 2,
        "modelVersion": "wildlife-v1.1",
        "modelPath": "/models/wildlife-v1.1/best.pt",

        "images": [
            {
                "imageId": 1,
                "filePath": "/shared-data/a.jpg"
            },
            {
                "imageId": 2,
                "filePath": "/shared-data/b.jpg"
            },
            {
                "imageId": 3,
                "filePath": "/shared-data/c.jpg"
            }
        ]
    }

    consumer._process_message(
        task_data,
        processor,
        callback
    )

    processor.process.assert_called_once()

    call_kwargs = (
        processor.process.call_args.kwargs
    )

    assert call_kwargs["task_id"] == 10001

    assert len(
        call_kwargs["images"]
    ) == 3

    assert (
        call_kwargs["version"]
        == "wildlife-v1.1"
    )

    assert (
        call_kwargs["collect_results"]
        is False
    )


# ============================================================
# 9. 10 张图片应该交给 BatchProcessor
# ============================================================

def test_process_message_ten_images(
    monkeypatch
):

    consumer, _ = create_consumer(
        monkeypatch
    )

    processor, summary = (
        create_batch_processor()
    )

    callback = MagicMock()

    images = []

    for i in range(10):

        images.append(
            {
                "imageId": i + 1,
                "filePath":
                    f"/shared-data/{i + 1}.jpg"
            }
        )

    task_data = {
        "taskId": 20001,
        "modelVersion": "wildlife-v1.0",
        "images": images
    }

    consumer._process_message(
        task_data,
        processor,
        callback
    )

    processor.process.assert_called_once()

    call_kwargs = (
        processor.process.call_args.kwargs
    )

    assert (
        len(call_kwargs["images"])
        == 10
    )

    assert (
        call_kwargs["version"]
        == "wildlife-v1.0"
    )


# ============================================================
# 10. taskId 缺失
# ============================================================

def test_process_message_missing_task_id(
    monkeypatch
):

    consumer, _ = create_consumer(
        monkeypatch
    )

    processor, _ = (
        create_batch_processor()
    )

    callback = MagicMock()

    task_data = {
        "modelVersion": "wildlife-v1.0",
        "images": [
            {
                "imageId": 1,
                "filePath": "/shared-data/a.jpg"
            }
        ]
    }

    consumer._process_message(
        task_data,
        processor,
        callback
    )

    processor.process.assert_not_called()


# ============================================================
# 11. taskId 非法
# ============================================================

def test_process_message_invalid_task_id(
    monkeypatch
):

    consumer, _ = create_consumer(
        monkeypatch
    )

    processor, _ = (
        create_batch_processor()
    )

    callback = MagicMock()

    task_data = {
        "taskId": "abc",
        "images": [
            {
                "imageId": 1,
                "filePath": "/shared-data/a.jpg"
            }
        ]
    }

    consumer._process_message(
        task_data,
        processor,
        callback
    )

    processor.process.assert_not_called()


# ============================================================
# 12. 没有图片
# ============================================================

def test_process_message_no_images(
    monkeypatch
):

    consumer, _ = create_consumer(
        monkeypatch
    )

    processor, _ = (
        create_batch_processor()
    )

    callback = MagicMock()

    task_data = {
        "taskId": 100,
        "images": []
    }

    consumer._process_message(
        task_data,
        processor,
        callback
    )

    processor.process.assert_not_called()


# ============================================================
# 13. BatchProcessor 出错
# ============================================================

def test_process_task_failure(
    monkeypatch
):

    consumer, _ = create_consumer(
        monkeypatch
    )

    processor = MagicMock()

    processor.batch_size = 8

    processor.process.side_effect = (
        RuntimeError(
            "YOLO inference failed"
        )
    )

    callback = MagicMock()

    images = [
        {
            "imageId": 1,
            "filePath": "/shared-data/a.jpg"
        }
    ]

    consumer._process_task(
        task_id=999,
        images=images,
        batch_processor=processor,
        callback=callback,
        model_version="wildlife-v1.0"
    )

    processor.process.assert_called_once()

    callback.send_error.assert_called_once()

    args = (
        callback.send_error.call_args.args
    )

    assert args[0] == 999

    assert (
        "YOLO inference failed"
        in args[1]
    )


# ============================================================
# 14. 正常完成任务
# ============================================================

def test_process_task_success(
    monkeypatch
):

    consumer, _ = create_consumer(
        monkeypatch
    )

    processor, summary = (
        create_batch_processor()
    )

    callback = MagicMock()

    images = [
        {
            "imageId": 1,
            "filePath": "/shared-data/a.jpg"
        },
        {
            "imageId": 2,
            "filePath": "/shared-data/b.jpg"
        },
        {
            "imageId": 3,
            "filePath": "/shared-data/c.jpg"
        }
    ]

    consumer._process_task(
        task_id=888,
        images=images,
        batch_processor=processor,
        callback=callback,
        model_version="wildlife-v1.1"
    )

    processor.process.assert_called_once()

    callback.send_error.assert_not_called()


# ============================================================
# 15. 10 张图片应该保持原始顺序
# ============================================================

def test_parse_images_preserves_order():

    task_data = {
        "taskId": 100,
        "images": [
            {
                "imageId": 10,
                "filePath": "/a.jpg"
            },
            {
                "imageId": 20,
                "filePath": "/b.jpg"
            },
            {
                "imageId": 30,
                "filePath": "/c.jpg"
            }
        ]
    }

    images = TaskConsumer._parse_images(
        task_data
    )

    assert [
        item["imageId"]
        for item in images
    ] == [10, 20, 30]
