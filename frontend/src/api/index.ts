/**
 * 前端统一接口层 —— 基于深度学习的野生动物图像批量识别系统
 *
 * 全部接口走 /api 前缀（baseURL 已包含），调用时只写业务路径。
 *
 *   认证    POST   /api/auth/login   GET /api/auth/verify   POST /api/auth/logout
 *   图像    POST   /api/images/upload-batch      批量上传（识别任务的数据入口）
 *           POST   /api/images/upload            单张上传
 *           GET    /api/images                   图像列表（按任务 / 状态筛选分页）
 *           GET    /api/images/{id}              图像详情
 *           GET    /api/images/{id}/raw          原图
 *           GET    /api/images/{id}/thumbnail    缩略图（后端暂无缩略图列时回退原图）
 *           DELETE /api/images/{id}              删除图像
 *           POST   /api/images/batch-delete      批量删除
 *   任务    POST   /api/tasks                    创建批量识别任务
 *           GET    /api/tasks                    任务列表，可按状态筛选
 *           GET    /api/tasks/today              今日任务
 *           GET    /api/tasks/{id}               任务详情
 *           GET    /api/tasks/{id}/progress      任务进度（轮询 / WebSocket 同构）
 *           GET    /api/tasks/{id}/results       任务下全部识别结果（跨图像）
 *           POST   /api/tasks/{id}/cancel        取消任务
 *           POST   /api/tasks/{id}/retry         重试任务
 *           DELETE /api/tasks/{id}               删除任务
 *   结果    GET    /api/results                  结果分页查询
 *           GET    /api/results/{imageId}        某张图像上的全部检出
 *           GET    /api/results/classes          类别名列表（筛选用）
 *           GET    /api/results/export           导出 CSV
 *           DELETE /api/results/{id}             删除单条结果
 *   复核    GET    /api/reviews/pending          待复核队列
 *           POST   /api/reviews/{id}             提交单条复核
 *           POST   /api/reviews/batch            批量复核
 *           GET    /api/reviews/records          复核记录
 *           GET    /api/reviews/stats            复核率 / 修正率 / 误检率
 *   模型    GET    /api/models                   模型列表，可按状态筛选
 *           GET    /api/models/active            当前启用的模型
 *           GET    /api/models/{id}              模型详情
 *           GET    /api/models/{id}/metrics      评估指标（mAP / P / R / F1 / 产出）
 *           POST   /api/models                   登记模型版本
 *           PUT    /api/models/{id}              修改模型
 *           POST   /api/models/{id}/activate     设为启用模型
 *           POST   /api/models/{id}/deactivate   取消启用
 *           DELETE /api/models/{id}              删除模型
 *   统计    GET    /api/statistics/overview              总量概览
 *           GET    /api/statistics/classes               类别（物种）分布
 *           GET    /api/statistics/trends                识别量趋势
 *           GET    /api/statistics/detection-rate        检出率趋势
 *           GET    /api/statistics/protection-distribution  保护等级 / IUCN 构成
 *           GET    /api/statistics/confidence-distribution  置信度分布
 *           GET    /api/statistics/task-status            任务状态统计
 *
 * 字段口径以后端实体为准（RecognitionTask / RecognitionImage / DetectionResult /
 * ModelVersion / ReviewRecord）—— 实体字段即数据库列，是全链路唯一的权威来源。
 */

import axios from 'axios'

// ── 基础配置 ─────────────────────────────────────────────────────────────────

const TOKEN_KEY = 'wildlife_admin_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function removeToken() {
  localStorage.removeItem(TOKEN_KEY)
}

const apiClient = axios.create({
  baseURL: '/api',
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' }
})

// 每次请求自动带上 token
apiClient.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers['X-Auth-Token'] = token
  return config
})

export { apiClient }

// ── 类型：枚举 ───────────────────────────────────────────────────────────────

/** 任务状态：排队中 / 识别中 / 已完成 / 失败 / 已取消 */
export type TaskStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELED'

/** 图像状态：等待识别 / 识别中 / 识别成功 / 识别失败 */
export type ImageStatus = 'WAITING' | 'PROCESSING' | 'SUCCESS' | 'FAILED'

/** 结果复核状态：待复核 / 已确认 / 已修正 / 已剔除 */
export type ReviewStatus = 'PENDING' | 'CONFIRMED' | 'CORRECTED' | 'REJECTED'

/** 复核动作：确认无误 / 修正物种 / 剔除误检 */
export type ReviewAction = 'CONFIRM' | 'CORRECT' | 'REJECT'

/** 模型状态：启用中 / 已停用（同一时间只允许一个启用） */
export type ModelStatus = 'ENABLED' | 'DISABLED'

/** 趋势粒度：按小时（活动节律）/ 按天 */
export type TrendGranularity = 'hour' | 'day'

// ── 类型：实体 ───────────────────────────────────────────────────────────────

/** 识别任务 —— 一个任务 = 一批图像的一次批量识别过程 */
export interface RecognitionTask {
  id: number
  taskName: string | null
  userId: number | null
  modelId: number | null
  totalCount: number
  processedCount: number
  successCount: number
  failedCount: number
  /** 0 ~ 100 */
  progress: number
  status: TaskStatus
  createTime: string | null
  startTime: string | null
  finishTime: string | null
}

/** 任务进度 —— 轮询接口与 WebSocket 推送共用同一结构 */
export interface TaskProgress {
  taskId: number
  status: TaskStatus
  totalCount: number
  processedCount: number
  successCount: number
  failedCount: number
  progress: number
}

/** 识别结果 —— 某张图像上检出的一个目标（物种 + 置信度 + 边界框） */
export interface DetectionResult {
  id: number
  imageId: number
  modelId: number | null
  classId: number | null
  /**
   * 类别名不是可空字段：detection_result.class_name 是 VARCHAR(100) NOT NULL，
   * 且 AI 引擎永远会给名字（认不出时回落到 `class_{id}`）。
   * 声明成 `string | null` 会让 DetectionResult 无法赋给 DetectionImage 的
   * DetectionBox（后者的 className 是 string），vue-tsc 直接编译不过。
   */
  className: string
  /** 0 ~ 1 */
  confidence: number
  x1: number
  y1: number
  x2: number
  y2: number
  reviewStatus: ReviewStatus
  /**
   * 所属图像的**原图物理文件**是否还在（后端现算，不落库）。
   *
   * false = 磁盘上的原图已丢失（库里还留着识别结果，即"悬空引用"）。
   * 这种结果在复核弹窗里取图会 404，看不到图就没有判据 —— 后端会拒绝提交，
   * 前端也应禁用「复核」按钮。
   *
   * 目前只有 `GET /api/reviews/pending` 会填这个字段，其它接口返回 null / undefined，
   * 所以判断可用性时要用 `=== false` 而不是 `!row.imageAvailable`。
   */
  imageAvailable?: boolean | null
  createTime: string | null
}

/** 模型版本 */
export interface ModelVersion {
  id: number
  modelName: string
  version: string | null
  modelPath: string
  classConfig: string | null
  precisionValue: number | null
  recallValue: number | null
  map50: number | null
  map5095: number | null
  status: ModelStatus
  createTime: string | null
}

/** 识别图像 —— 批量识别的基本单位，一个任务下挂一批图像 */
export interface RecognitionImage {
  id: number
  /** 尚未归属任务时为 null（上传后等待被某个任务领走） */
  taskId: number | null
  fileName: string
  filePath: string
  fileSize: number | null
  status: ImageStatus
  errorMessage: string | null
  createTime: string | null
}

/** 只描述"一个框"的最小结构，DetectionImage 只依赖这几个字段。 */
export interface DetectionBox {
  id?: number
  classId?: number | null
  className: string
  confidence: number
  x1: number
  y1: number
  x2: number
  y2: number
}

// ── 类型：响应包装 ───────────────────────────────────────────────────────────

/** 分页响应 —— 后端统一返回 total / page / size / pages / list */
export interface PageResult<T> {
  total: number
  page: number
  size: number
  pages: number
  list: T[]
}

/** 单个类别的检出统计 */
export interface ClassStat {
  name: string
  value: number
  /** 占比，百分数（0 ~ 100） */
  ratio: number
  protectionLevel: string
}

/** GET /api/statistics/classes */
export interface ClassDistribution {
  total: number
  speciesCount: number
  list: ClassStat[]
}

/** 趋势上的一个点 */
export interface TrendPoint {
  time: string
  value: number
}

/** GET /api/statistics/trends */
export interface TrendResult {
  granularity: TrendGranularity
  range: string
  list: TrendPoint[]
}

/** 检出率趋势上的一个点 */
export interface DetectionRatePoint {
  time: string
  /** 识别成功的图像数（分母） */
  successImages: number
  /** 有至少一个检出结果的图像数（分子） */
  detectedImages: number
  /** 识别成功但一个目标都没检出的图像数（空拍） */
  undetectedImages: number
  /** 检出率百分比，0-100，后端已保留两位小数 */
  detectionRate: number
}

/** GET /api/statistics/detection-rate —— 检出率趋势 */
export interface DetectionRateResult {
  granularity: TrendGranularity
  range: string
  successImages: number
  detectedImages: number
  undetectedImages: number
  detectionRate: number
  list: DetectionRatePoint[]
}

/** POST /api/images/upload-batch —— 逐张返回成功与失败，单张失败不影响其余 */
export interface BatchUploadResult {
  total: number
  successCount: number
  failedCount: number
  images: RecognitionImage[]
  failures: Array<{ fileName: string; reason: string }>
}

/** 统计类接口通用的 name/value 项 */
export interface NameValue {
  name: string
  value: number
}

/** GET /api/statistics/overview */
export interface StatisticsOverview {
  imageCount: number
  taskCount: number
  resultCount: number
  speciesCount: number
  protectedSpeciesCount: number
  pendingReviewCount: number
  avgConfidence: number
  /** 识别成功的图像数（检出率分母）。FAILED 的图像不计入 */
  successImageCount: number
  /** 有至少一个检出结果的图像数（检出率分子） */
  detectedImageCount: number
  /** 识别成功但一个目标都没检出的图像数（空拍） */
  undetectedImageCount: number
  /** 检出率百分比，0-100 */
  detectionRate: number
}

/** GET /api/models/{id}/metrics */
export interface ModelMetrics {
  modelId?: number
  modelName?: string
  version?: string
  precision?: number | null
  recall?: number | null
  map50?: number | null
  map5095?: number | null
  f1?: number | null
  detectedCount?: number
  classCount?: number
  avgConfidence?: number
  classDistribution?: Array<{ className: string; count: number }>
}

/** GET /api/reviews/stats —— 比率为百分数（0 ~ 100） */
export interface ReviewStats {
  total: number
  reviewed: number
  pending: number
  /**
   * 待复核里"原图已丢失、根本没法复核"的条数。
   * pending 仍是全量口径，前端据此说明差额（否则会出现"统计 2798、列表 2000"对不上账）。
   */
  pendingMissing: number
  confirmed: number
  corrected: number
  rejected: number
  reviewRate: number
  correctRate: number
  rejectRate: number
  topCorrectedClasses: Array<{ className: string; correctedCount: number }>
}

/** 复核记录 —— 每次人工复核留一条，作为模型迭代依据 */
export interface ReviewRecord {
  id: number
  resultId: number
  reviewerId: number | null
  originalClass: string | null
  correctedClass: string | null
  originalConfidence: number | null
  reviewStatus: ReviewStatus
  remark: string | null
  reviewTime: string | null
}

/** GET /api/statistics/task-status */
export interface TaskStatusStatistics {
  total: number
  list: NameValue[]
  totalImages: number
  processedImages: number
  /** 整体识别进度，百分数（0 ~ 100） */
  progress: number
}

// ── 类型：查询与请求体 ───────────────────────────────────────────────────────

export interface TaskQuery {
  status?: TaskStatus
  /** 从 1 开始 */
  page?: number
  size?: number
}

export interface ImageQuery {
  taskId?: number
  status?: ImageStatus
  /** 从 1 开始 */
  page?: number
  size?: number
}

export interface ResultQuery {
  taskId?: number
  imageId?: number
  className?: string
  /** 最低置信度，0 ~ 1 */
  minConfidence?: number
  reviewStatus?: ReviewStatus
  /** 检出时间下界，ISO 日期时间（如 2026-09-24T00:00），闭区间 */
  startTime?: string
  /** 检出时间上界，ISO 日期时间 */
  endTime?: string
  /** 从 1 开始 */
  page?: number
  size?: number
}

export interface PendingReviewQuery {
  taskId?: number
  /** 置信度上限，只取低于该值的结果；不传则返回全部待复核 */
  maxConfidence?: number
  /** true = 把"原图已丢失"的项从队列里排除（后端在 SQL 里过滤，分页仍正确） */
  hideMissing?: boolean
  page?: number
  size?: number
}

export interface ClassStatsQuery {
  taskId?: number
  /** 时间范围：all / today / 7d / 30d / 90d / 1y */
  range?: string
  /** 取前 N 个类别，默认 10 */
  top?: number
}

export interface TrendQuery {
  granularity?: TrendGranularity
  range?: string
}

/** POST /api/tasks 请求体 —— imageIds 来自图像上传接口返回的 ID */
export interface CreateTaskPayload {
  taskName?: string
  imageIds: number[]
  /** 省略时使用当前启用的模型 */
  modelId?: number
}

/** POST /api/reviews/{id} 请求体 */
export interface ReviewPayload {
  action: ReviewAction
  /** action=CORRECT 时必填：修正后的物种名 */
  correctedClass?: string
  remark?: string
}

/** POST /api/models 请求体 */
export interface ModelPayload {
  modelName: string
  version?: string
  modelPath: string
  classConfig?: string
  precisionValue?: number
  recallValue?: number
  map50?: number
  map5095?: number
  status?: ModelStatus
}

// ── 类型：认证 ───────────────────────────────────────────────────────────────

export interface LoginResult {
  token: string
  username: string
  /** ADMIN 管理员 / USER 普通用户 */
  role: string
}

export interface SessionInfo {
  username: string
  role: string
  valid: boolean
}

// ── 认证 ─────────────────────────────────────────────────────────────────────

/** POST /api/auth/login —— 登录，返回 token 与角色。系统不开放注册。 */
export function login(username: string, password: string) {
  return apiClient.post<LoginResult>('/auth/login', { username, password })
}

/** GET /api/auth/verify —— 校验 token 是否有效，顺带取回当前角色。 */
export function verifyToken() {
  return apiClient.get<SessionInfo>('/auth/verify')
}

/** POST /api/auth/logout —— 登出，服务端销毁 token。 */
export function logout() {
  return apiClient.post<{ message: string }>('/auth/logout')
}

// ── 任务 ─────────────────────────────────────────────────────────────────────

/** POST /api/tasks —— 创建批量识别任务。 */
export function createTask(payload: CreateTaskPayload) {
  return apiClient.post<RecognitionTask>('/tasks', payload)
}

/** GET /api/tasks —— 任务列表，可按状态筛选；分页由后端 LIMIT/OFFSET 执行。 */
export function listTasks(params?: TaskQuery) {
  return apiClient.get<PageResult<RecognitionTask>>('/tasks', { params })
}

/** GET /api/tasks/{id} —— 任务详情。 */
export function getTask(id: number) {
  return apiClient.get<RecognitionTask>(`/tasks/${id}`)
}

/** GET /api/tasks/{id}/progress —— 任务进度，供前端轮询。 */
export function getTaskProgress(id: number) {
  return apiClient.get<TaskProgress>(`/tasks/${id}/progress`)
}

/** DELETE /api/tasks/{id} —— 删除任务（同时清理其识别结果）。 */
export function deleteTask(id: number) {
  return apiClient.delete<{ message: string }>(`/tasks/${id}`)
}

// ── 结果 ─────────────────────────────────────────────────────────────────────

/** GET /api/results —— 结果分页查询，支持任务、图像、物种、置信度、复核状态筛选。 */
export function listResults(params?: ResultQuery) {
  return apiClient.get<PageResult<DetectionResult>>('/results', { params })
}

/** GET /api/results/{imageId} —— 某张图像上的全部检出，用于叠框展示。 */
export function getImageResults(imageId: number) {
  return apiClient.get<DetectionResult[]>(`/results/${imageId}`)
}

// ── 复核 ─────────────────────────────────────────────────────────────────────

/** GET /api/reviews/pending —— 待复核队列，后端按置信度升序（最不确定的排前面）。 */
export function listPendingReviews(params?: PendingReviewQuery) {
  return apiClient.get<PageResult<DetectionResult>>('/reviews/pending', { params })
}

/** POST /api/reviews/{id} —— 提交单条复核，返回更新后的识别结果。 */
export function submitReview(id: number, payload: ReviewPayload) {
  return apiClient.post<DetectionResult>(`/reviews/${id}`, payload)
}

// ── 模型 ─────────────────────────────────────────────────────────────────────

/** GET /api/models —— 模型列表，可按启用状态筛选。 */
export function listModels(params?: { status?: ModelStatus }) {
  return apiClient.get<ModelVersion[]>('/models', { params })
}

/** POST /api/models —— 登记模型版本。 */
export function createModel(payload: ModelPayload) {
  return apiClient.post<ModelVersion>('/models', payload)
}

/** PUT /api/models/{id} —— 修改模型，只需传要改的字段。 */
export function updateModel(id: number, payload: Partial<ModelPayload>) {
  return apiClient.put<ModelVersion>(`/models/${id}`, payload)
}

/** DELETE /api/models/{id} —— 删除模型。 */
export function deleteModel(id: number) {
  return apiClient.delete<{ message: string }>(`/models/${id}`)
}

// ── 统计 ─────────────────────────────────────────────────────────────────────

/** GET /api/statistics/classes —— 类别（物种）分布与保护等级。 */
export function getClassStatistics(params?: ClassStatsQuery) {
  return apiClient.get<ClassDistribution>('/statistics/classes', { params })
}

/** GET /api/statistics/trends —— 识别量趋势，按小时可看动物活动节律。 */
export function getTrendStatistics(params?: TrendQuery) {
  return apiClient.get<TrendResult>('/statistics/trends', { params })
}

/** GET /api/statistics/detection-rate —— 检出率趋势：有检出的图像 / 识别成功的图像。 */
export function getDetectionRateStatistics(params?: TrendQuery) {
  return apiClient.get<DetectionRateResult>('/statistics/detection-rate', { params })
}

/** GET /api/statistics/overview —— 图像 / 任务 / 结果 / 物种总量概览。 */
export function getOverview(range?: string) {
  return apiClient.get<StatisticsOverview>('/statistics/overview', { params: { range } })
}

/** GET /api/statistics/protection-distribution —— 保护等级与 IUCN 等级构成。 */
export function getProtectionDistribution(range?: string) {
  return apiClient.get<{ byProtectionLevel: NameValue[]; byIucn: NameValue[] }>(
    '/statistics/protection-distribution',
    { params: { range } }
  )
}

/** GET /api/statistics/confidence-distribution —— 置信度区间分布。 */
export function getConfidenceDistribution(taskId?: number) {
  return apiClient.get<{ total: number; list: Array<{ range: string; value: number; ratio: number }> }>(
    '/statistics/confidence-distribution',
    { params: { taskId } }
  )
}

/** GET /api/statistics/task-status —— 任务状态分布与整体识别进度。 */
export function getTaskStatusStatistics() {
  return apiClient.get<TaskStatusStatistics>('/statistics/task-status')
}

// ── 图像 ─────────────────────────────────────────────────────────────────────
//
// 批量识别是"先传图、再建任务"的两步流程：这里拿到 imageId 列表后，
// 交给 createTask({ imageIds }) 才会真正排队识别。

/** POST /api/images/upload-batch —— 批量上传，单张失败不影响其余。 */
export function uploadImagesBatch(files: File[], onProgress?: (percent: number) => void) {
  const form = new FormData()
  files.forEach((file) => form.append('files', file))
  return apiClient.post<BatchUploadResult>('/images/upload-batch', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 600000,
    onUploadProgress: (e) => {
      if (e.total && onProgress) onProgress(Math.round((e.loaded * 100) / e.total))
    }
  })
}

/** POST /api/images/upload —— 单张上传。 */
export function uploadImage(file: File) {
  const form = new FormData()
  form.append('file', file)
  return apiClient.post<RecognitionImage>('/images/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 300000
  })
}

/** GET /api/images —— 图像列表，按任务 / 状态筛选分页。 */
export function listImages(params?: ImageQuery) {
  return apiClient.get<PageResult<RecognitionImage>>('/images', { params })
}

/** GET /api/images/{id} —— 图像详情。 */
export function getImage(id: number) {
  return apiClient.get<RecognitionImage>(`/images/${id}`)
}

/** DELETE /api/images/{id} —— 删除图像（磁盘文件一并清理）。 */
export function deleteImage(id: number) {
  return apiClient.delete<{ message: string }>(`/images/${id}`)
}

/** POST /api/images/batch-delete —— 批量删除。 */
export function deleteImagesBatch(imageIds: number[]) {
  return apiClient.post<{ deleted: number }>('/images/batch-delete', { imageIds })
}

/** 原图地址，可直接放进 <img src> 或 window.open。 */
export function imageRawUrl(id: number): string {
  return `/api/images/${id}/raw`
}

/** 缩略图地址；后端当前无缩略图列时回退原图。 */
export function imageThumbnailUrl(id: number): string {
  return `/api/images/${id}/thumbnail`
}

/**
 * GET /api/images/{id}/raw 或 /api/images/{id}/thumbnail —— 以二进制（Blob）方式读图。
 *
 * 为什么不能直接用上面的 URL 字符串：`<img src>` / `window.open` 由浏览器自己发请求，
 * 带不上 `X-Auth-Token`（请求拦截器只管走 axios 的调用），后端鉴权一律返回 401。
 * 走这里：axios（自动带 token）→ Blob → `URL.createObjectURL(blob)` → 交给 `<img>`，
 * 鉴权链路与其余接口完全一致，后端无需为图片开鉴权口子。
 *
 * @param imageId 图像 ID
 * @param kind    'raw' 原图 | 'thumbnail' 缩略图（后端暂无缩略图列时回退原图）
 * @returns       图片二进制；调用方用完后须自行 `URL.revokeObjectURL()` 释放
 */
export async function getImageBlob(
  imageId: number,
  kind: 'raw' | 'thumbnail' = 'raw'
): Promise<Blob> {
  const res = await apiClient.get<Blob>(`/images/${imageId}/${kind}`, {
    responseType: 'blob'
  })
  return res.data
}

// ── 任务（取消 / 重试 / 汇总结果）─────────────────────────────────────────────

/** GET /api/tasks/today —— 今日任务。 */
export function listTodayTasks() {
  return apiClient.get<RecognitionTask[]>('/tasks/today')
}

/** GET /api/tasks/{id}/results —— 任务下全部识别结果（跨图像汇总）。 */
export function getTaskResults(id: number) {
  return apiClient.get<DetectionResult[]>(`/tasks/${id}/results`)
}

/** POST /api/tasks/{id}/cancel —— 取消排队中或进行中的任务。 */
export function cancelTask(id: number) {
  return apiClient.post<RecognitionTask>(`/tasks/${id}/cancel`)
}

/** POST /api/tasks/{id}/retry —— 重试失败或已取消的任务。 */
export function retryTask(id: number) {
  return apiClient.post<RecognitionTask>(`/tasks/${id}/retry`)
}

// ── 结果（删除 / 导出 / 类别清单）────────────────────────────────────────────

/** GET /api/results/classes —— 类别名列表，usedOnly=true 时只返回实际检出过的。 */
export function listResultClasses(usedOnly = true) {
  return apiClient.get<string[]>('/results/classes', { params: { usedOnly } })
}

/** DELETE /api/results/{id} —— 删除单条识别结果。 */
export function deleteResult(id: number) {
  return apiClient.delete<{ message: string }>(`/results/${id}`)
}

/** CSV 导出的查询条件 —— 与后端 /api/results/export 的参数名保持一致。 */
export interface ResultExportQuery {
  taskId?: number
  className?: string
  /** 最低置信度，0 ~ 1 */
  minConfidence?: number
  reviewStatus?: ReviewStatus
  /** 检出时间下界，ISO 日期时间（如 2026-09-24T00:00），闭区间 */
  startTime?: string
  /** 检出时间上界，ISO 日期时间 */
  endTime?: string
}

/** CSV 导出下载地址，可直接 window.open（不带鉴权头）。 */
export function resultExportUrl(params: ResultExportQuery = {}): string {
  const qs = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') qs.append(key, String(value))
  })
  const query = qs.toString()
  return `/api/results/export${query ? `?${query}` : ''}`
}

/** 下载识别结果 CSV（走 axios 以带上鉴权头）。 */
export async function exportResultsCsv(params: ResultExportQuery = {}) {
  const res = await apiClient.get('/results/export', { params, responseType: 'blob' })
  const url = URL.createObjectURL(res.data as Blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'recognition_results.csv'
  anchor.click()
  URL.revokeObjectURL(url)
}

// ── 复核（批量 / 记录 / 统计）────────────────────────────────────────────────

/** POST /api/reviews/batch 请求体 —— 同一批共用同一个动作与修正类别。 */
export interface BatchReviewPayload {
  resultIds: number[]
  action: ReviewAction
  /** action=CORRECT 时必填：修正后的物种名 */
  correctedClass?: string
  remark?: string
}

/** POST /api/reviews/batch —— 批量复核，同一动作、同一修正类别。 */
export function submitBatchReview(payload: BatchReviewPayload) {
  // skippedMissing：因原图已丢失被后端跳过的条数（批量选择混进脏数据时不会让整批失败）
  return apiClient.post<{ reviewed: number; skippedMissing?: number }>('/reviews/batch', payload)
}

/** GET /api/reviews/records —— 复核记录列表。 */
export function listReviewRecords(params: { taskId?: number; page?: number; size?: number } = {}) {
  return apiClient.get<ReviewRecord[]>('/reviews/records', { params })
}

/** GET /api/reviews/stats —— 复核率 / 修正率 / 误检率，以及最易认错的物种。 */
export function getReviewStats(taskId?: number) {
  return apiClient.get<ReviewStats>('/reviews/stats', { params: { taskId } })
}

// ── 模型（详情 / 指标 / 启停）────────────────────────────────────────────────

/** 没有启用模型时 /api/models/active 的响应 —— 后端返回 {"model":{},"message":"尚未启用任何模型"}。 */
export interface NoActiveModel {
  model: Record<string, never>
  message: string
}

/** GET /api/models/active —— 当前启用的模型。 */
export function getActiveModel() {
  return apiClient.get<ModelVersion | NoActiveModel>('/models/active')
}

/** GET /api/models/{id} —— 模型详情。 */
export function getModel(id: number) {
  return apiClient.get<ModelVersion>(`/models/${id}`)
}

/** GET /api/models/{id}/metrics —— 评估指标 + 该模型的实际产出情况。 */
export function getModelMetrics(id: number) {
  return apiClient.get<ModelMetrics>(`/models/${id}/metrics`)
}

/** POST /api/models/{id}/activate —— 设为启用模型（同时停用其他版本）。 */
export function activateModel(id: number) {
  return apiClient.post<{ id: number; status: ModelStatus }>(`/models/${id}/activate`)
}

/** POST /api/models/{id}/deactivate —— 取消启用。 */
export function deactivateModel(id: number) {
  return apiClient.post<{ id: number; status: ModelStatus }>(`/models/${id}/deactivate`)
}

// ── 展示辅助：标签 / 配色 / 解析 ─────────────────────────────────────────────
//
// 放在接口层而非各自组件里，是为了让"状态怎么显示、同一个物种用什么颜色"
// 全站只有一处定义 —— 后端加一个状态只需改这里。

/** 任务状态 → 中文标签。 */
export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  PENDING: '排队中',
  PROCESSING: '识别中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELED: '已取消'
}

/** 图像状态 → 中文标签。 */
export const IMAGE_STATUS_LABEL: Record<ImageStatus, string> = {
  WAITING: '待识别',
  PROCESSING: '识别中',
  SUCCESS: '已完成',
  FAILED: '失败'
}

/** 复核状态 → 中文标签。 */
export const REVIEW_STATUS_LABEL: Record<ReviewStatus, string> = {
  PENDING: '待复核',
  CONFIRMED: '已确认',
  CORRECTED: '已修正',
  REJECTED: '已剔除'
}

/**
 * 固定的物种框色板。
 * 按 classId 取模取色，保证同一物种在同一张图 / 不同图之间配色一致。
 */
export const DETECTION_PALETTE = [
  '#00e5ff',
  '#7c4dff',
  '#4caf50',
  '#ff9800',
  '#e91e63',
  '#00bcd4',
  '#ffc107',
  '#8bc34a',
  '#f44336',
  '#3f51b5',
  '#009688',
  '#ff5722'
]

/** 取某个类别对应的框颜色。 */
export function colorOfClass(classId?: number | null, className?: string): string {
  if (classId != null && Number.isFinite(classId)) {
    return DETECTION_PALETTE[Math.abs(classId) % DETECTION_PALETTE.length]
  }
  if (className) {
    let hash = 0
    for (let i = 0; i < className.length; i++) {
      hash = (hash * 31 + className.charCodeAt(i)) % 100000
    }
    return DETECTION_PALETTE[hash % DETECTION_PALETTE.length]
  }
  return DETECTION_PALETTE[0]
}

/** 置信度 → 颜色（低置信度用警示色，便于复核时一眼看出）。 */
export function colorOfConfidence(confidence: number): string {
  if (confidence >= 0.85) return '#4caf50'
  if (confidence >= 0.6) return '#ffc107'
  return '#f44336'
}

/**
 * 解析模型的 classConfig 得到可修正的物种类别清单。
 * 兼容三种写法：JSON 数组、逗号分隔、换行分隔。
 */
export function parseClassConfig(classConfig?: string | null): string[] {
  if (!classConfig) return []
  const raw = classConfig.trim()
  if (!raw) return []
  if (raw.startsWith('[')) {
    try {
      const arr = JSON.parse(raw)
      if (Array.isArray(arr)) {
        return arr
          .map((item) => (typeof item === 'string' ? item : String(item?.name ?? item?.className ?? '')))
          .filter((s) => s.length > 0)
      }
    } catch {
      /* 落到下面的分隔符解析 */
    }
  }
  return raw
    .split(/[,，\n\r;；]+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

// ── 用户管理 ────────────────────────────────────────────────────────────────
// 原先分散在 api/recognition.ts（已被删除的旧版重复 API 层），统一并入本模块。

export type UserRole = 'ADMIN' | 'REVIEWER' | 'USER'
export type UserStatus = 'ACTIVE' | 'DISABLED'

/**
 * 用户账号（表 users）。
 * 角色：ADMIN 全部权限 / REVIEWER 识别 + 复核 / USER 只读。
 */
export interface UserInfo {
  id: number
  username: string
  role: UserRole
  createdAt?: string
  /** 以下字段由 /api/user 返回，实体当前未落库，可能为空 */
  nickname?: string
  email?: string
  status?: UserStatus
}

/** 角色 → 中文标签。 */
export const USER_ROLE_LABEL: Record<UserRole, string> = {
  ADMIN: '系统管理员',
  REVIEWER: '复核员',
  USER: '只读用户'
}

export function listUsers(params: { role?: string; keyword?: string } = {}) {
  return apiClient.get<UserInfo[]>('/user/list', { params })
}

/** 当前登录用户（用于判断"自己"，避免误删自己的账号）。 */
export function getCurrentUser() {
  return apiClient.get<UserInfo>('/user/me')
}

export function createUser(data: {
  username: string
  password: string
  nickname?: string
  role: UserRole
}) {
  return apiClient.post<UserInfo>('/user', data)
}

export function updateUser(id: number, data: { nickname?: string; role?: UserRole; email?: string }) {
  return apiClient.put<UserInfo>(`/user/${id}`, data)
}

export function updateUserStatus(id: number, status: UserStatus) {
  return apiClient.put<{ id: number; status: UserStatus }>(`/user/${id}/status`, { status })
}

/** 管理员重置他人密码。 */
export function resetUserPassword(id: number, password: string) {
  return apiClient.post<{ message: string }>(`/user/${id}/password`, { password })
}

/** 修改自己的密码，需校验原密码。 */
export function changeOwnPassword(data: { oldPassword: string; newPassword: string }) {
  return apiClient.post<{ message: string }>('/user/password', data)
}

export function deleteUser(id: number) {
  return apiClient.delete<{ message: string }>(`/user/${id}`)
}

// ── AI 引擎模型自检 ──────────────────────────────────────────────────────────
//
// 注意这里走的是 nginx 的 /ai/ 反代（见 frontend/nginx.conf 的 location /ai/），
// **不带 /api 前缀**，所以用 baseURL: '' 覆盖 apiClient 的默认前缀。
//
// 为什么要问引擎而不是问后端：后端 model_version 表记录的是系统**声称**启用哪个
// 版本；权重文件到底在不在、实际加载的是哪一份，只有 AI 引擎知道。两者不一致时
// 页面上会显示"当前模型 wildlife-v1.0"，而跑的是另一份权重。

export interface EngineModelStatus {
  service: string
  modelRoot: string
  /** 引擎进程按 ACTIVE_MODEL_VERSION 认为的启用版本 */
  activeVersion: string
  /** 本次查询问的版本 */
  requestedVersion: string
  /** 为真 = 期望权重缺失，实际加载了备用权重 */
  usingFallback: boolean
  expectedWeightPath: string
  effectiveWeight: {
    path: string
    exists: boolean
    sizeBytes: number
    md5: string
  }
  /** 该版本 classes.txt / meta.json 声明的类别 */
  declaredClasses: string[]
  declaredClassCount: number
  issues: string[]
  ok: boolean
  versions: {
    version: string
    available: boolean
    modelPath: string
    map50: number | null
    map5095: number | null
  }[]
}

/** GET /ai/model/status —— 引擎的权重自检报告（问"实际生效的权重是哪一份"）。 */
export function getEngineModelStatus(version?: string) {
  return apiClient.get<EngineModelStatus>('/ai/model/status', {
    baseURL: '',
    // version 可能来自 ModelVersion.version（可为 null），null 与 undefined 同样按"不指定版本"处理
    params: version ? { version } : undefined
  })
}
