const TOKEN_KEY = 'mfi.token.value'
const HEADER_KEY = 'mfi.token.header'

const DEFAULT_HEADER = 'mfi-token'

/**
 * 会话令牌的本地存取。
 *
 * <p>独立成模块是为了打断「store → api → store」的循环引用：请求层只认这里的令牌，
 * 登录/引导逻辑负责写入。走请求头而不是 Cookie，与后端 sa-token 的 `token-name: mfi-token`
 * 及验收脚本一致，也不依赖 Cookie 的 SameSite 取值。
 */
export function readTokenValue(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function readTokenHeader(): string {
  return localStorage.getItem(HEADER_KEY) || DEFAULT_HEADER
}

export function saveToken(headerName: string, tokenValue: string): void {
  localStorage.setItem(HEADER_KEY, headerName || DEFAULT_HEADER)
  localStorage.setItem(TOKEN_KEY, tokenValue)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export function hasToken(): boolean {
  return readTokenValue() !== ''
}
