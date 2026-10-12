import type {
  ApplicationStatus,
  ExportTaskStatus,
  RegisterStatus,
  Stage,
  StageStatus,
  TokenStatus
} from './types'

export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

/**
 * 状态字典。key 必须是后端枚举名：列表中出现的未知值走 fallback 原样显示，
 * 这样后端加状态时前端不会整页空白，只是少了中文与配色。
 */
export const APPLICATION_STATUS: Record<ApplicationStatus, { label: string; type: TagType }> = {
  PENDING: { label: '待执行', type: 'info' },
  RUNNING: { label: '执行中', type: 'primary' },
  SUCCESS: { label: '成功', type: 'success' },
  PARTIAL_FAILED: { label: '部分失败', type: 'warning' },
  FAILED: { label: '失败', type: 'danger' }
}

export const STAGE_ORDER: Stage[] = ['APPLY', 'FILE_NAME', 'FILE_CONTENT', 'REGISTER', 'FINISH']

export const STAGE: Record<Stage, { label: string }> = {
  APPLY: { label: '申请认证实体' },
  FILE_NAME: { label: '获取文件列表' },
  FILE_CONTENT: { label: '下载并入库' },
  REGISTER: { label: '注册回苹果' },
  FINISH: { label: '分配序列号' }
}

export const STAGE_STATUS: Record<StageStatus, { label: string; type: TagType }> = {
  PENDING: { label: '待执行', type: 'info' },
  PROCESSING: { label: '执行中', type: 'primary' },
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'warning' },
  DEAD: { label: '已终止', type: 'danger' }
}

export const TOKEN_STATUS: Record<TokenStatus, { label: string; type: TagType }> = {
  ISSUED: { label: '已下发', type: 'primary' },
  REGISTERED: { label: '已注册', type: 'success' },
  SCRAPPED: { label: '已报废', type: 'warning' },
  DESTROYED: { label: '已销毁', type: 'info' }
}

export const REGISTER_STATUS: Record<RegisterStatus, { label: string; type: TagType }> = {
  NOT_REQUIRED: { label: '无需注册', type: 'info' },
  PENDING: { label: '待注册', type: 'warning' },
  PROCESSING: { label: '注册中', type: 'primary' },
  SUCCESS: { label: '已注册', type: 'success' },
  FAILED: { label: '注册失败', type: 'danger' }
}

export const EXPORT_STATUS: Record<ExportTaskStatus, { label: string; type: TagType }> = {
  PENDING: { label: '排队中', type: 'info' },
  PROCESSING: { label: '生成中', type: 'primary' },
  SUCCESS: { label: '已完成', type: 'success' },
  FAILED: { label: '失败', type: 'danger' }
}

/** 文件下载状态，取值是 StageStatus 的子集，由 `t_token_file.download_status` 直接给出 */
export const FILE_DOWNLOAD_STATUS: Record<string, { label: string; type: TagType }> = {
  PENDING: { label: '待下载', type: 'info' },
  PROCESSING: { label: '下载中', type: 'primary' },
  SUCCESS: { label: '已入库', type: 'success' },
  FAILED: { label: '下载失败', type: 'danger' }
}

export function dictOf(
  dict: Record<string, { label: string; type: TagType }>,
  key?: string | null
): { label: string; type: TagType } {
  if (!key) {
    return { label: '-', type: 'info' }
  }
  return dict[key] ?? { label: key, type: 'info' }
}

export function stageLabel(stage?: string): string {
  if (!stage) {
    return '-'
  }
  return (STAGE as Record<string, { label: string }>)[stage]?.label ?? stage
}
