/**
 * 受保护图片的 Object URL 统一管理。
 *
 * 背景：`<img src>` / `window.open` 由浏览器自发请求，带不上 `X-Auth-Token`，
 * 后端对 `/api/images/{id}/raw` 与 `/api/images/{id}/thumbnail` 一律返回 401。
 * 所以图片必须先经 axios 取成 Blob（见 `getImageBlob`），再转成 Object URL 交给页面。
 *
 * Object URL 是把 Blob 挂在文档上的强引用，不显式释放就会一直占内存；页面里
 * 「切图 / 翻页 / 重新加载」会反复产生新 URL，所以集中在这里登记，由
 * `revokeImageUrl` 统一回收，避免各页面各写一套、也避免漏掉。
 */
import { getImageBlob } from '@/api/index'

/** 已创建但尚未释放的 Object URL。 */
const objectUrls = new Set<string>()

/**
 * 取受保护图片并转成可放进 `<img src>` 的 Object URL。
 *
 * @param imageId 图像 ID
 * @param kind    'thumbnail' 缩略图（默认） | 'raw' 原图
 * @returns       Object URL；页面不再使用（换图、翻页、卸载）时须调 `revokeImageUrl` 释放
 */
export async function loadProtectedImage(
  imageId: number,
  kind: 'raw' | 'thumbnail' = 'thumbnail'
): Promise<string> {
  const blob = await getImageBlob(imageId, kind)
  const url = URL.createObjectURL(blob)
  objectUrls.add(url)
  return url
}

/**
 * 释放由 `loadProtectedImage` 创建的 Object URL。
 *
 * 只回收本模块登记过的地址：传入 undefined / null / 外部普通 URL（如上传预览）
 * 时静默跳过，重复释放同一地址也安全。
 */
export function revokeImageUrl(url?: string | null) {
  if (!url) return
  if (objectUrls.has(url)) {
    URL.revokeObjectURL(url)
    objectUrls.delete(url)
  }
}
