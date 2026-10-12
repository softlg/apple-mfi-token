/**
 * 后端回的是 `LocalDateTime` 的 ISO 串（无时区、秒为 0 时会被省略，如 `2026-10-12T11:26`），
 * 这里只做字符串补齐，不经过 `new Date()`，避免浏览器把无时区串按本地/UTC 解释而差八小时。
 */
export function fmtDateTime(value?: string | null): string {
  if (!value) {
    return '-'
  }
  const [datePart, timePart = ''] = value.split('T')
  const time = timePart.split('.')[0] ?? ''
  const segments = time.split(':')
  while (segments.length < 3) {
    segments.push('00')
  }
  return `${datePart} ${segments.slice(0, 3).join(':')}`
}

export function fmtDate(value?: string | null): string {
  if (!value) {
    return '-'
  }
  return value.split('T')[0] ?? '-'
}

export function fmtNumber(value?: number | null): string {
  if (value === undefined || value === null) {
    return '-'
  }
  return value.toLocaleString('zh-CN')
}

/** 从 ApiError / 网络异常里取一句能直接展示的话。 */
export function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return '操作失败，请稍后重试'
}
