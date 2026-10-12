import type { ApiEnvelope } from '../types'
import { clearToken, readTokenHeader, readTokenValue } from './token'

export const SUCCESS_CODE = '0'

const CODE_UNAUTHORIZED = 'A0401'

/** 后端统一响应里的业务错误；message 取自服务端，可直接展示给用户。 */
export class ApiError extends Error {
  readonly code: string

  readonly httpStatus: number

  constructor(code: string, message: string, httpStatus: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
  }
}

export type Query = Record<string, string | number | boolean | null | undefined>

let unauthorizedHandler: ((error: ApiError) => void) | null = null

/**
 * 会话失效只认业务码 A0401：登录失败也是 HTTP 401，但业务码是 A0402，
 * 若按状态码判定会在登录页上把用户"踢下线"并循环跳转。
 */
export function setUnauthorizedHandler(handler: (error: ApiError) => void): void {
  unauthorizedHandler = handler
}

function buildUrl(path: string, query?: Query): string {
  const params = new URLSearchParams()
  Object.entries(query ?? {}).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') {
      return
    }
    params.set(key, String(value))
  })
  const qs = params.toString()
  return qs.length > 0 ? `${path}?${qs}` : path
}

function headers(): Record<string, string> {
  const token = readTokenValue()
  return token ? { [readTokenHeader()]: token } : {}
}

function failSession(error: ApiError): never {
  clearToken()
  unauthorizedHandler?.(error)
  throw error
}

/**
 * 解析统一响应包装。`ResponseWrapAdvice` 只对 JSON 返回值生效，导出与模板下载走字节流，
 * 所以这里要同时容纳"业务错误仍是 R"与"成功是裸内容"两种形态。
 */
async function readEnvelope<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  let envelope: ApiEnvelope<T> | null = null
  try {
    envelope = text.length > 0 ? (JSON.parse(text) as ApiEnvelope<T>) : null
  } catch {
    envelope = null
  }
  if (envelope !== null && typeof envelope.code === 'string') {
    if (envelope.code === SUCCESS_CODE) {
      return envelope.data as T
    }
    const error = new ApiError(envelope.code, envelope.message || '请求失败', response.status)
    if (envelope.code === CODE_UNAUTHORIZED) {
      return failSession(error)
    }
    throw error
  }
  if (!response.ok) {
    const error = new ApiError('HTTP', `请求失败（HTTP ${response.status}）`, response.status)
    if (response.status === 401) {
      return failSession(error)
    }
    throw error
  }
  return (text.length > 0 ? JSON.parse(text) : undefined) as T
}

async function send<T>(path: string, init: RequestInit, query?: Query): Promise<T> {
  const response = await fetch(buildUrl(path, query), {
    ...init,
    headers: { ...headers(), ...(init.headers ?? {}) }
  })
  return readEnvelope<T>(response)
}

export function get<T>(path: string, query?: Query): Promise<T> {
  return send<T>(path, { method: 'GET' }, query)
}

export function postJson<T>(path: string, body?: unknown): Promise<T> {
  return send<T>(path, {
    method: 'POST',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
}

export function putJson<T>(path: string, body?: unknown): Promise<T> {
  return send<T>(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {})
  })
}

export function patchJson<T>(path: string, body?: unknown): Promise<T> {
  return send<T>(path, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {})
  })
}

export function remove<T>(path: string): Promise<T> {
  return send<T>(path, { method: 'DELETE' })
}

/** multipart 上传不能手动设置 Content-Type，边界由浏览器生成。 */
export function postMultipart<T>(path: string, form: FormData): Promise<T> {
  return send<T>(path, { method: 'POST', body: form })
}

export interface DownloadResult {
  blob: Blob
  filename: string
}

function parseFilename(disposition: string | null, fallback: string): string {
  if (!disposition) {
    return fallback
  }
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (encoded?.[1]) {
    return decodeURIComponent(encoded[1])
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain?.[1] ?? fallback
}

/**
 * 二进制下载。会话令牌在请求头里，`<a href>` 带不上，只能用 fetch 取 Blob 再落地成链接。
 *
 * <p>失败时服务端回的是 JSON 业务错误（比如导出任务还没跑完），这里要把它还原成 ApiError，
 * 否则用户拿到一个打不开的"文件"。
 */
export async function download(path: string, query?: Query, fallbackName = 'download.bin'): Promise<DownloadResult> {
  const response = await fetch(buildUrl(path, query), { method: 'GET', headers: headers() })
  const contentType = response.headers.get('Content-Type') ?? ''
  if (contentType.includes('application/json')) {
    await readEnvelope<never>(response)
  }
  if (!response.ok) {
    throw new ApiError('HTTP', `下载失败（HTTP ${response.status}）`, response.status)
  }
  return {
    blob: await response.blob(),
    filename: parseFilename(response.headers.get('Content-Disposition'), fallbackName)
  }
}

export function saveBlob(result: DownloadResult): void {
  const url = URL.createObjectURL(result.blob)
  const link = document.createElement('a')
  link.href = url
  link.download = result.filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
