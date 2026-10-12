import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '../api'
import { ApiError, setUnauthorizedHandler } from '../api/http'
import { clearToken, hasToken, saveToken } from '../api/token'
import type { LoginForm, Profile, RegisterForm, TenantBrief } from '../types'
import { errorMessage } from '../utils/format'

const CODE_UNAUTHORIZED = 'A0401'

function isUnauthorized(error: unknown): boolean {
  if (!(error instanceof ApiError)) {
    return false
  }
  return error.code === CODE_UNAUTHORIZED || (error.code === 'HTTP' && error.httpStatus === 401)
}

/**
 * 会话与当前租户。
 *
 * <p>owner 位一律来自 `profile.tenants[].owner`，也就是服务端每次请求回库读出来的结果，
 * 前端不另存一份，否则移除成员、转让 owner 之后页面还会按旧权限渲染出可点的按钮。
 */
export const useSessionStore = defineStore('session', () => {
  const profile = ref<Profile | null>(null)
  const loaded = ref(false)
  const bootstrapError = ref('')
  const switching = ref(false)
  /** 会话在服务端被判失效（A0401）。外壳据此把人带回登录页，而不是留在一页静默的空表格上。 */
  const expired = ref(false)

  const tenants = computed<TenantBrief[]>(() => profile.value?.tenants ?? [])
  const currentTenant = computed(() => tenants.value.find((item) => item.current) ?? null)
  const isOwner = computed(() => currentTenant.value?.owner ?? false)
  const isLoggedIn = computed(() => profile.value !== null)
  const displayName = computed(() => profile.value?.name ?? profile.value?.account ?? '')

  async function bootstrap(force = false): Promise<void> {
    if (loaded.value && !force) {
      return
    }
    bootstrapError.value = ''
    if (!hasToken()) {
      profile.value = null
      loaded.value = true
      return
    }
    try {
      profile.value = await authApi.profile()
    } catch (error) {
      profile.value = null
      if (!isUnauthorized(error)) {
        bootstrapError.value = errorMessage(error)
      }
    } finally {
      loaded.value = true
    }
  }

  async function login(form: LoginForm): Promise<void> {
    const result = await authApi.login(form)
    saveToken(result.tokenName, result.tokenValue)
    expired.value = false
    await bootstrap(true)
  }

  async function register(form: RegisterForm): Promise<void> {
    const result = await authApi.register(form)
    saveToken(result.tokenName, result.tokenValue)
    expired.value = false
    await bootstrap(true)
  }

  async function switchTenant(tenantId: number): Promise<void> {
    switching.value = true
    try {
      await authApi.switchTenant(tenantId)
      await bootstrap(true)
    } finally {
      switching.value = false
    }
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 服务端会话已过期时退出照样该生效
    }
    clearToken()
    profile.value = null
    loaded.value = true
  }

  setUnauthorizedHandler(() => {
    // 过期令牌继续带着只会反复 401，就地丢掉；expired 供外壳把用户带回登录页
    clearToken()
    profile.value = null
    loaded.value = true
    expired.value = true
  })

  return {
    profile,
    loaded,
    bootstrapError,
    switching,
    expired,
    tenants,
    currentTenant,
    isOwner,
    isLoggedIn,
    displayName,
    bootstrap,
    login,
    register,
    switchTenant,
    logout
  }
})
