-- ============================================================================
-- 野生动物图像批量识别系统 —— MySQL 初始化脚本
--
-- 由 docker-compose 挂载到容器的 /docker-entrypoint-initdb.d/init.sql，
-- 在 mysql_data 数据卷为空（首次启动）时自动执行。
--
-- 表清单（6 张）：
--   users              用户（全库根表）
--   model_version      识别模型版本
--   recognition_task   批量识别任务
--   recognition_image  识别图像（批量识别的基本单位）
--   detection_result   目标检测结果（一张图像可有多个目标）
--   review_record      人工审核记录
--
-- 依赖关系：
--   users ──→ recognition_task ──→ recognition_image ──→ detection_result ──→ review_record
--   model_version ──→ recognition_task
--   model_version ──→ detection_result
--   users ──→ review_record（审核人）
--
-- 表名与列名与后端实体严格一一对应（entity/ 下的 @TableName / @TableField），
-- 实体字段即数据库列，是全链路唯一权威口径。
--
-- ⚠️ 状态取值口径必须与「后端 / AI 引擎 / 前端」三端保持一致；本文件已按代码
--    实际取值对齐，改动前务必先 grep 三端再改，否则会出现「库里查不到」的静默失效：
--      users.status               ACTIVE / DISABLED
--      model_version.status       ENABLED / DISABLED      （不是 ACTIVE / INACTIVE）
--      recognition_task.status    PENDING / PROCESSING / COMPLETED / FAILED / CANCELED
--      recognition_image.status   WAITING / PROCESSING / SUCCESS / FAILED
--      detection_result.review_status  PENDING / CONFIRMED / CORRECTED / REJECTED
--      detection_result.x1..y2    检测框坐标为整数像素（INT），不是 FLOAT
--      model_version.model_path   AI 引擎容器内绝对路径（/models/<版本目录>/best.pt）
-- ============================================================================

CREATE DATABASE IF NOT EXISTS wildlife_db
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE wildlife_db;

-- ⚠️ 必须显式声明连接字符集。
--    下面 model_version 的种子数据里含中文类别名，而本文件是由 mysql 客户端
--    以「客户端默认字符集」执行的：客户端charset 若不是 utf8mb4，中文会在
--    导入阶段就被写坏，且不报任何错 —— 表现为前端「人工审核」下拉框里全是乱码。
--    MySQL 8 客户端默认已是 utf8mb4，这里显式钉死以免依赖环境默认值。
SET NAMES utf8mb4;


-- ============================================================================
-- 0. 清理历史表
--
-- 早期版本遗留的表在此统一删除，保证脚本可重复执行且不残留旧结构。
-- 本系统只保留下面定义的用户、模型版本、识别任务、识别图像、
-- 检测结果与复核记录六张表。
--
-- users 表不删，用户数据不丢。
--
-- ⚠️ 通篇 CREATE TABLE IF NOT EXISTS 对「已存在」的表不会修改结构，因此要让
--    本文件的建表语句真正生效，必须先 docker compose down -v 清空 mysql_data
--    卷再重新 up —— 否则脚本会静默跳过建表，看起来「改了个寂寞」。
-- ============================================================================
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS review_record;
DROP TABLE IF EXISTS review_records;
DROP TABLE IF EXISTS detection_result;
DROP TABLE IF EXISTS detection_results;
DROP TABLE IF EXISTS recognition_image;
DROP TABLE IF EXISTS recognition_images;
DROP TABLE IF EXISTS recognition_task;
DROP TABLE IF EXISTS recognition_tasks;
DROP TABLE IF EXISTS model_version;
DROP TABLE IF EXISTS model_versions;

SET FOREIGN_KEY_CHECKS = 1;


-- =========================================================
-- 1. 用户表
-- =========================================================

CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =========================================================
-- 2. 模型版本表
-- =========================================================

CREATE TABLE IF NOT EXISTS model_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    model_name VARCHAR(100) NOT NULL COMMENT '模型名称',
    version VARCHAR(50) NOT NULL COMMENT '模型版本',

    model_path VARCHAR(500) NOT NULL COMMENT '模型文件路径（AI 引擎容器内绝对路径）',

    class_config TEXT COMMENT '类别配置JSON',

    precision_value DECIMAL(8,4) DEFAULT NULL COMMENT 'Precision',
    recall_value DECIMAL(8,4) DEFAULT NULL COMMENT 'Recall',
    map50 DECIMAL(8,4) DEFAULT NULL COMMENT 'mAP@0.5',
    map5095 DECIMAL(8,4) DEFAULT NULL COMMENT 'mAP@0.5:0.95',

    status VARCHAR(20) NOT NULL DEFAULT 'DISABLED'
        COMMENT 'ENABLED-启用中 / DISABLED-停用',

    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_model_version (version),
    KEY idx_status (status)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='野生动物识别模型版本表';


-- =========================================================
-- 3. 批量识别任务表
-- =========================================================

CREATE TABLE IF NOT EXISTS recognition_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    task_name VARCHAR(200) NOT NULL COMMENT '任务名称',

    user_id BIGINT NOT NULL COMMENT '创建任务的用户',

    model_id BIGINT DEFAULT NULL COMMENT '使用的模型版本',

    total_count INT NOT NULL DEFAULT 0 COMMENT '图片总数',

    processed_count INT NOT NULL DEFAULT 0 COMMENT '已处理图片数',

    success_count INT NOT NULL DEFAULT 0 COMMENT '识别成功数量',

    failed_count INT NOT NULL DEFAULT 0 COMMENT '识别失败数量',

    progress DECIMAL(7,2) NOT NULL DEFAULT 0.00 COMMENT '处理进度百分比',

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/PROCESSING/COMPLETED/FAILED/CANCELED',

    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    start_time TIMESTAMP NULL DEFAULT NULL,

    finish_time TIMESTAMP NULL DEFAULT NULL,

    CONSTRAINT fk_task_user
        FOREIGN KEY (user_id) REFERENCES users(id),

    CONSTRAINT fk_task_model
        FOREIGN KEY (model_id) REFERENCES model_version(id),

    INDEX idx_task_user (user_id),

    INDEX idx_task_status (status),

    INDEX idx_task_create_time (create_time)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='批量野生动物识别任务表';


-- =========================================================
-- 4. 识别图片表
-- =========================================================

CREATE TABLE IF NOT EXISTS recognition_image (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    task_id BIGINT NULL COMMENT '所属识别任务，未创建任务时为空',

    file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',

    file_path VARCHAR(500) NOT NULL COMMENT '服务器文件路径',

    file_size BIGINT DEFAULT NULL COMMENT '文件大小',

    status VARCHAR(20) NOT NULL DEFAULT 'WAITING'
        COMMENT 'WAITING/PROCESSING/SUCCESS/FAILED',

    error_message TEXT COMMENT '处理失败原因',

    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_image_task
        FOREIGN KEY (task_id) REFERENCES recognition_task(id)
        ON DELETE CASCADE,

    INDEX idx_image_task (task_id),

    INDEX idx_image_status (status),

    -- 图像列表默认按 create_time DESC, id DESC 排序（ImageService），无此索引会退化为全表排序
    INDEX idx_image_create_time (create_time)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='批量识别图片表';


-- =========================================================
-- 5. 目标检测结果表
-- =========================================================

CREATE TABLE IF NOT EXISTS detection_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    image_id BIGINT NOT NULL COMMENT '图片ID',

    model_id BIGINT DEFAULT NULL COMMENT '使用的模型',

    class_id INT NOT NULL COMMENT '类别ID',

    class_name VARCHAR(100) NOT NULL COMMENT '类别名称',

    confidence DECIMAL(8,6) NOT NULL COMMENT '置信度',

    x1 INT NOT NULL COMMENT '检测框左上角X',

    y1 INT NOT NULL COMMENT '检测框左上角Y',

    x2 INT NOT NULL COMMENT '检测框右下角X',

    y2 INT NOT NULL COMMENT '检测框右下角Y',

    review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/CONFIRMED/CORRECTED/REJECTED',

    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_result_image
        FOREIGN KEY (image_id) REFERENCES recognition_image(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_result_model
        FOREIGN KEY (model_id) REFERENCES model_version(id),

    INDEX idx_result_image (image_id),

    INDEX idx_result_class (class_id),

    INDEX idx_result_confidence (confidence),

    INDEX idx_result_review (review_status),

    -- 结果列表默认按 create_time DESC, id DESC 排序（RecognitionResultService），无此索引会退化为全表排序
    INDEX idx_result_create_time (create_time)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='YOLO目标检测结果表';


-- =========================================================
-- 6. 人工审核记录表
-- =========================================================

CREATE TABLE IF NOT EXISTS review_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    result_id BIGINT NOT NULL COMMENT '检测结果ID',

    reviewer_id BIGINT NOT NULL COMMENT '审核用户ID',

    original_class VARCHAR(100) NOT NULL COMMENT 'AI原始类别',

    corrected_class VARCHAR(100) DEFAULT NULL COMMENT '人工修正类别',

    original_confidence DECIMAL(8,6) NOT NULL COMMENT 'AI原始置信度',

    review_status VARCHAR(20) NOT NULL
        COMMENT 'CONFIRMED/CORRECTED/REJECTED',

    remark VARCHAR(500) DEFAULT NULL COMMENT '审核备注',

    review_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_review_result
        FOREIGN KEY (result_id) REFERENCES detection_result(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_review_user
        FOREIGN KEY (reviewer_id) REFERENCES users(id),

    INDEX idx_review_result (result_id),

    INDEX idx_review_user (reviewer_id),

    INDEX idx_review_time (review_time)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='人工审核记录表';


-- =========================================================
-- 7. 默认管理员
-- =========================================================

-- ⚠️ 密码列存 BCrypt 哈希（60 字符，$2a$/$2b$ 开头），不要写明文。
--    下面这串是 'admin123' 的 BCrypt（10 轮）。首次登录后请立即改密。
--    即便写成明文，登录也仍会成功 —— PasswordService 兼容明文 / MD5 旧格式，
--    并在首次成功登录时自动升级为 BCrypt —— 但升级发生前它会以明文形态留在库里。
INSERT INTO users (username, password, role, status)
VALUES ('admin', '$2a$10$9q1LtXUBsgKo9m7R/bxBXuBWkwHpIHS2WEglaBE7oe8LeHMGjUE3G', 'ADMIN', 'ACTIVE')
ON DUPLICATE KEY UPDATE username = username;


-- =========================================================
-- 8. 初始化野生动物模型
--
-- ⚠️ model_path 必须是 AI 引擎容器内的绝对路径：ai-engine 把
--    ./ai-engine/models 只读挂载到 /models，所以磁盘上的
--    ai-engine/models/wildlife-v1.0/best.pt 在容器里就是
--    /models/wildlife-v1.0/best.pt。
-- ⚠️ class_config 供前端「人工审核」下拉框使用（见 api/index.ts 的
--    parseClassConfig），只支持两种写法：JSON 数组，或逗号/换行分隔的纯文本。
--    写成 {"0":"deer",...} 这类对象会被当成普通字符串按逗号切开，渲染出残片。
-- ⚠️ class_config 必须与 ai-engine/models/wildlife-v1.0/classes.txt **逐字节一致**，
--    否则前端「人工审核」下拉框与识别服务返回的类别名对不上。
--     当前值 = ai-engine/models/wildlife-v1.0/classes.txt 的 19 个中文物种名（逗号后有空格）：
--      md5(class_config) = 9e9efee08d04c20772708eb02522d13d   (UTF-8, 214 bytes)
--    改动时请用同一口径校验：
--      python -c "import json,hashlib;n=[l.strip() for l in open('classes.txt',encoding='utf-8') if l.strip()];print(hashlib.md5(json.dumps(n,ensure_ascii=False,separators=(',',':')).encode()).hexdigest())"
--    ⚠️ 注意 separators —— json.dumps 默认会在逗号后加空格，那样 md5 会变
--       （1ada8ea2cf3d20dfb337622d61ccbc08），虽不影响 JSON 语义，但会与本注释不符。
-- ⚠️ status 用 ENABLED / DISABLED（ModelService / ModelController / 前端
--    ModelStatus 三处都按这两个词判定），首个模型建库时直接置 ENABLED。
-- =========================================================

INSERT INTO model_version
(
    model_name,
    version,
    model_path,
    class_config,
    status
)
VALUES
(
    'Wildlife YOLO',
    'wildlife-v1.0',
    '/models/wildlife-v1.0/best.pt',
        '["野猪", "猕猴", "麂", "水鹿", "鼬獾", "红颊松鼠", "果子狸", "蟹獴", "中华鬣羚", "白鹇", "黄喉貂", "帚尾豪猪", "灰孔雀雉", "红原鸡", "虎", "豹", "豹猫", "猪獾", "赤麂"]',
    'ENABLED'
)
ON DUPLICATE KEY UPDATE
    model_name = model_name;
