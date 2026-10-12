export interface ApiEnvelope<T> {
  code: string
  message?: string
  data?: T
}

export interface PageResult<T> {
  pageNum: number
  pageSize: number
  total: number
  records: T[]
}

export interface PageQuery {
  pageNum?: number
  pageSize?: number
}

export type ApplicationStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'PARTIAL_FAILED' | 'FAILED'

export type Stage = 'APPLY' | 'FILE_NAME' | 'FILE_CONTENT' | 'REGISTER' | 'FINISH'

export type StageStatus = 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'DEAD'

export type TokenStatus = 'ISSUED' | 'REGISTERED' | 'SCRAPPED' | 'DESTROYED'

export type RegisterStatus = 'NOT_REQUIRED' | 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED'

export type ExportTaskStatus = 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED'

export type ExportFormat = 'CSV' | 'XLSX'

export interface TenantBrief {
  tenantId: number
  tenantCode: string
  tenantName: string
  owner: boolean
  current: boolean
}

export interface LoginResult {
  tokenName: string
  tokenValue: string
  userId: number
  account: string
  name: string
  currentTenant?: TenantBrief
  tenants?: TenantBrief[]
}

export interface Profile {
  userId: number
  account: string
  name: string
  email?: string
  phone?: string
  status: number
  lastLoginTime?: string
  currentTenantId?: number
  tenants?: TenantBrief[]
}

export interface Member {
  memberId: number
  userId: number
  account: string
  name: string
  email?: string
  phone?: string
  ownerFlag: number
  status: number
  userStatus: number
  joinTime?: string
}

export interface TenantProfile {
  tenantCode: string
  tenantName: string
  appleTechnology?: string
  effectiveBaseUrl?: string
  appleBaseUrl?: string
  status: number
}

export interface TenantConfigItem {
  key: string
  effectiveValue?: string
  defaultValue?: string
  overridden: boolean
  remark?: string
}

export interface CredentialMeta {
  fingerprint?: string
  certSubject?: string
  certNotAfter?: string
  daysToExpire?: number
  version?: number
  encryptKeyId?: string
  updateTime?: string
}

export interface CredentialVerify {
  reachable: boolean
  httpStatus?: number
  detail?: string
  fingerprint?: string
}

export interface PpId {
  id: number
  ppid: string
  spu: string
  productModel: string
  totalQuantity: number
  appliedQuantity: number
  remainingQuantity: number
  status: number
  remark?: string
  createTime?: string
  updateTime?: string
}

export interface PpIdStats {
  productModel: string
  spu: string
  ppidRows: number
  enabledRows: number
  totalQuantity: number
  appliedQuantity: number
  remainingQuantity: number
}

export interface PpIdPlanItem {
  ppidId: number
  ppid: string
  quantity: number
  remainingBefore: number
}

export interface PpIdAvailable {
  productModel: string
  spu: string
  requestedQuantity: number
  allocatableQuantity: number
  sufficient: boolean
  plan?: PpIdPlanItem[]
}

export interface PpIdRowError {
  rowNo: number
  ppid?: string
  message: string
}

export interface PpIdImportResult {
  readRows: number
  insertedRows: number
  rejectedRows: number
  errors?: PpIdRowError[]
}

export interface Application {
  applicationNo: string
  productModel: string
  spu: string
  appleTechnology?: string
  applyQuantity: number
  ppidCount?: number
  tokenCount?: number
  status: ApplicationStatus
  failReason?: string
  applicantName?: string
  createTime?: string
  finishTime?: string
}

export interface ApplicationShard {
  shardId: number
  ppid: string
  allocateCount: number
  stage: Stage
  stageStatus: StageStatus
  retryCount?: number
  lastError?: string
  nextExecuteTime?: string
}

export interface StageProgress {
  stage: Stage
  reached: number
  succeeded: number
  waiting: number
  processing: number
  dead: number
}

export interface TokenCount {
  status: TokenStatus
  registerStatus?: RegisterStatus
  count: number
}

export interface OperationLog {
  action: string
  shardId?: number
  ppid?: string
  operatorName?: string
  quantity?: number
  detail?: string
  createTime?: string
}

export interface ApplicationDetail {
  application: Application
  stages?: StageProgress[]
  shards?: ApplicationShard[]
  tokenSummary?: TokenCount[]
  timeline?: OperationLog[]
}

export interface ApplicationRetryResult {
  applicationNo: string
  replayedCount: number
  shards?: ApplicationShard[]
}

export interface ApplySubmitResult {
  applicationNo: string
  status: ApplicationStatus
  applyQuantity: number
  ppidCount?: number
  shards?: ApplicationShard[]
  duplicated?: boolean
}

export interface TokenFile {
  fileId: number
  shardId: number
  ppid: string
  fileName?: string
  tokenCount?: number
  downloadStatus?: string
  retryCount?: number
  downloadTime?: string
  lastError?: string
}

export interface TokenRow {
  id: number
  ppid: string
  manufactureSn?: string
  status: TokenStatus
  registerStatus?: RegisterStatus
  downloadTime?: string
  registerTime?: string
}

export interface TokenDestroyResult {
  applicationNo: string
  selected: number
  destroyed: number
  hasMore: boolean
  failReason?: string
}

export interface ExportTask {
  taskNo: string
  status: ExportTaskStatus
  exportFormat?: ExportFormat
  totalRows?: number
  failReason?: string
  createTime?: string
  finishTime?: string
  downloadUrl?: string
}

export interface RegisterForm {
  account: string
  password: string
  name: string
  email?: string
  phone?: string
  tenantName: string
}

export interface LoginForm {
  account: string
  password: string
}

export interface PpIdCreateForm {
  ppid: string
  spu: string
  productModel: string
  totalQuantity: number | null
  remark?: string
}

export interface PpIdUpdateForm {
  totalQuantity: number | null
  status: number
  remark?: string
}

export interface PpIdPageQuery extends PageQuery {
  ppid?: string
  spu?: string
  productModel?: string
  status?: number | null
}

export interface ApplyForm {
  productModel: string
  spu: string
  applyQuantity: number | null
  requestId?: string
}

export interface ApplicationPageQuery extends PageQuery {
  applicationNo?: string
  productModel?: string
  spu?: string
  status?: ApplicationStatus | ''
}

export interface TokenPageQuery extends PageQuery {
  applicationNo?: string
  ppid?: string
  status?: TokenStatus | ''
  registerStatus?: RegisterStatus | ''
  manufactureSn?: string
}

export interface TokenExportForm {
  applicationNo?: string
  ppid?: string
  status?: TokenStatus | ''
  format: ExportFormat
}

export interface TokenDestroyForm {
  applicationNo: string
  confirmApplicationNo: string
  ppid?: string
}

export interface TenantProfileForm {
  tenantName: string
  appleTechnology?: string
  appleBaseUrl?: string
}
