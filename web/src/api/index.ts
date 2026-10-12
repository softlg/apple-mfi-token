import type {
  Application,
  ApplicationDetail,
  ApplicationPageQuery,
  ApplicationRetryResult,
  ApplyForm,
  ApplySubmitResult,
  CredentialMeta,
  CredentialVerify,
  ExportTask,
  LoginForm,
  LoginResult,
  Member,
  PageResult,
  PpId,
  PpIdAvailable,
  PpIdCreateForm,
  PpIdImportResult,
  PpIdPageQuery,
  PpIdStats,
  PpIdUpdateForm,
  Profile,
  RegisterForm,
  TenantConfigItem,
  TenantProfile,
  TenantProfileForm,
  TenantBrief,
  TokenDestroyForm,
  TokenDestroyResult,
  TokenExportForm,
  TokenFile,
  TokenPageQuery,
  TokenRow
} from '../types'
import { download, get, postJson, postMultipart, putJson, remove, patchJson } from './http'

/**
 * 接口层：与后端 Controller 一一对应，路径与字段名以 `docs/DEVELOPMENT_PLAN.md` §8 为准。
 * 这里不做任何业务判断，只负责把类型对上，页面层拿到的已经是解包后的 data。
 */
export const authApi = {
  register: (form: RegisterForm) => postJson<LoginResult>('/api/auth/register', form),
  login: (form: LoginForm) => postJson<LoginResult>('/api/auth/login', form),
  logout: () => postJson<void>('/api/auth/logout'),
  profile: () => get<Profile>('/api/auth/profile'),
  switchTenant: (tenantId: number) => postJson<TenantBrief>('/api/auth/switch-tenant', { tenantId })
}

export const memberApi = {
  list: () => get<Member[]>('/api/members'),
  invite: (account: string) => postJson<Member>('/api/members/invite', { account }),
  updateStatus: (memberId: number, status: number) =>
    patchJson<void>(`/api/members/${memberId}/status`, { status }),
  remove: (memberId: number) => remove<void>(`/api/members/${memberId}`),
  transferOwner: (userId: number) => postJson<void>('/api/members/transfer-owner', { userId })
}

export const tenantApi = {
  profile: () => get<TenantProfile>('/api/tenant/profile'),
  updateProfile: (form: TenantProfileForm) => putJson<TenantProfile>('/api/tenant/profile', form),
  config: () => get<TenantConfigItem[]>('/api/tenant/config'),
  updateConfig: (values: Record<string, string>) =>
    putJson<TenantConfigItem[]>('/api/tenant/config', { values })
}

export const credentialApi = {
  metadata: () => get<CredentialMeta>('/api/tenant/credential'),
  replace: (certPem: string, keyPem: string) =>
    putJson<CredentialMeta>('/api/tenant/credential', { certPem, keyPem }),
  remove: () => remove<void>('/api/tenant/credential'),
  verify: () => postJson<CredentialVerify>('/api/tenant/credential/verify')
}

export const ppidApi = {
  page: (query: PpIdPageQuery) => get<PageResult<PpId>>('/api/ppid/page', { ...query }),
  stats: () => get<PpIdStats[]>('/api/ppid/stats'),
  available: (productModel: string, spu: string, quantity: number) =>
    get<PpIdAvailable>('/api/ppid/available', { productModel, spu, quantity }),
  create: (form: PpIdCreateForm) => postJson<PpId>('/api/ppid', form),
  update: (id: number, form: PpIdUpdateForm) => putJson<PpId>(`/api/ppid/${id}`, form),
  disable: (id: number) => remove<void>(`/api/ppid/${id}`),
  importExcel: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return postMultipart<PpIdImportResult>('/api/ppid/import', form)
  },
  template: () => download('/api/ppid/template', undefined, 'ppid-template.xlsx'),
  export: (query: PpIdPageQuery) => download('/api/ppid/export', { ...query }, 'ppid.xlsx')
}

export const applicationApi = {
  submit: (form: ApplyForm) => postJson<ApplySubmitResult>('/api/applications', form),
  page: (query: ApplicationPageQuery) => get<PageResult<Application>>('/api/applications/page', { ...query }),
  detail: (applicationNo: string) => get<ApplicationDetail>(`/api/applications/${encodeURIComponent(applicationNo)}`),
  files: (applicationNo: string) =>
    get<TokenFile[]>(`/api/applications/${encodeURIComponent(applicationNo)}/files`),
  retry: (applicationNo: string) =>
    postJson<ApplicationRetryResult>(`/api/applications/${encodeURIComponent(applicationNo)}/retry`)
}

export const tokenApi = {
  page: (query: TokenPageQuery) => get<PageResult<TokenRow>>('/api/tokens/page', { ...query }),
  export: (form: TokenExportForm) => postJson<ExportTask>('/api/tokens/export', form),
  destroy: (form: TokenDestroyForm) => postJson<TokenDestroyResult>('/api/tokens/destroy', form)
}

export const exportTaskApi = {
  status: (taskNo: string) => get<ExportTask>(`/api/export-tasks/${encodeURIComponent(taskNo)}`),
  download: (taskNo: string) =>
    download(`/api/export-tasks/${encodeURIComponent(taskNo)}/download`, undefined, `${taskNo}.xlsx`)
}
