<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { applicationApi } from '../api'
import {
  APPLICATION_STATUS,
  FILE_DOWNLOAD_STATUS,
  REGISTER_STATUS,
  STAGE,
  STAGE_ORDER,
  STAGE_STATUS,
  TOKEN_STATUS,
  dictOf,
  stageLabel
} from '../dict'
import { errorMessage, fmtDateTime, fmtNumber } from '../utils/format'
import type { ApplicationDetail, Stage, StageProgress, TokenFile } from '../types'

const POLL_MS = 5000

const route = useRoute()
const router = useRouter()
const detail = ref<ApplicationDetail | null>(null)
const files = ref<TokenFile[]>([])
const loading = ref(false)
const autoRefresh = ref(true)
let timer: number | undefined

const applicationNo = computed(() => String(route.params.applicationNo ?? ''))
const status = computed(() => detail.value?.application.status)
const terminal = computed(() => {
  const current = status.value
  return current === 'SUCCESS' || current === 'FAILED' || current === 'PARTIAL_FAILED'
})
const deadShards = computed(() => (detail.value?.shards ?? []).filter((item) => item.stageStatus === 'DEAD'))
const stageMap = computed(() => {
  const map = new Map<Stage, StageProgress>()
  ;(detail.value?.stages ?? []).forEach((item) => map.set(item.stage, item))
  return map
})

/** 五个阶段按固定顺序渲染：后端只回有进度的阶段，缺项显示为"未开始" */
const stageRows = computed(() =>
  STAGE_ORDER.map((stage) => ({ stage, label: STAGE[stage].label }))
)

/** 连续完成的阶段数：作为 el-steps 的 active，再给每一步显式状态覆盖颜色 */
const activeStageCount = computed(() => {
  let count = 0
  for (const row of stageRows.value) {
    if (stepStatus(row.stage) === 'success') {
      count += 1
    } else {
      break
    }
  }
  return count
})

function stepStatus(stage: Stage): 'success' | 'process' | 'error' | 'wait' {
  const item = stageMap.value.get(stage)
  if (!item || item.reached === 0) {
    return 'wait'
  }
  if (item.dead > 0) {
    return 'error'
  }
  if (item.succeeded === item.reached) {
    return 'success'
  }
  return 'process'
}

function stepDescription(stage: Stage): string {
  const item = stageMap.value.get(stage)
  if (!item || item.reached === 0) {
    return '未开始'
  }
  const parts = [`到站 ${item.reached}`, `完成 ${item.succeeded}`]
  if (item.waiting > 0) {
    parts.push(`等待 ${item.waiting}`)
  }
  if (item.processing > 0) {
    parts.push(`执行中 ${item.processing}`)
  }
  if (item.dead > 0) {
    parts.push(`已终止 ${item.dead}`)
  }
  return parts.join(' / ')
}

async function load(silent = false): Promise<void> {
  if (!silent) {
    loading.value = true
  }
  try {
    const [result, fileList] = await Promise.all([
      applicationApi.detail(applicationNo.value),
      applicationApi.files(applicationNo.value)
    ])
    detail.value = result
    files.value = fileList
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function startPolling(): void {
  stopPolling()
  if (autoRefresh.value && !terminal.value) {
    timer = window.setInterval(() => void load(true), POLL_MS)
  }
}

function stopPolling(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function retryDead(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将 ${deadShards.value.length} 个已终止分片按当前阶段重新入队，复用已有的苹果 request id，不重复扣额度。`,
      '确认重放',
      { type: 'warning', confirmButtonText: '重放', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const result = await applicationApi.retry(applicationNo.value)
    ElMessage.success(`已重放 ${result.replayedCount} 个分片`)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

watch([applicationNo, autoRefresh, terminal], startPolling)

onMounted(async () => {
  await load()
  startPolling()
})

onUnmounted(stopPolling)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-toolbar">
      <el-button @click="router.push('/applications')">返回列表</el-button>
      <strong class="no">{{ applicationNo }}</strong>
      <el-tag v-if="status" :type="dictOf(APPLICATION_STATUS, status).type" effect="plain">
        {{ dictOf(APPLICATION_STATUS, status).label }}
      </el-tag>
      <div class="grow" />
      <el-switch v-model="autoRefresh" active-text="自动刷新" :disabled="terminal" />
      <el-button :loading="loading" @click="load()">刷新</el-button>
      <el-button v-if="deadShards.length > 0" type="warning" @click="retryDead">
        重放失败分片（{{ deadShards.length }}）
      </el-button>
    </div>

    <el-card v-if="detail" class="mb12">
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="产品型号">{{ detail.application.productModel }}</el-descriptions-item>
        <el-descriptions-item label="SPU">{{ detail.application.spu }}</el-descriptions-item>
        <el-descriptions-item label="technology">{{ detail.application.appleTechnology ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ detail.application.applicantName ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="申请数量">{{ fmtNumber(detail.application.applyQuantity) }}</el-descriptions-item>
        <el-descriptions-item label="PPID 分片">{{ detail.application.ppidCount ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="Token 数">{{ fmtNumber(detail.application.tokenCount) }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{ fmtDateTime(detail.application.finishTime) }}</el-descriptions-item>
        <el-descriptions-item label="创建时间" :span="2">
          {{ fmtDateTime(detail.application.createTime) }}
        </el-descriptions-item>
        <el-descriptions-item label="失败原因" :span="2">
          {{ detail.application.failReason ?? '-' }}
        </el-descriptions-item>
      </el-descriptions>

      <el-steps :active="activeStageCount" align-center class="steps">
        <el-step
          v-for="row in stageRows"
          :key="row.stage"
          :title="row.label"
          :description="stepDescription(row.stage)"
          :status="stepStatus(row.stage)"
        />
      </el-steps>
    </el-card>

    <el-tabs v-if="detail" class="mb12">
      <el-tab-pane label="分片明细" name="shards">
        <el-table :data="detail.shards ?? []" size="small" border>
          <el-table-column prop="shardId" label="分片" width="90" />
          <el-table-column prop="ppid" label="PPID" min-width="180" show-overflow-tooltip />
          <el-table-column prop="allocateCount" label="分配数量" width="100" />
          <el-table-column label="当前阶段" width="140">
            <template #default="{ row }">{{ stageLabel(row.stage) }}</template>
          </el-table-column>
          <el-table-column label="阶段状态" width="110">
            <template #default="{ row }">
              <el-tag :type="dictOf(STAGE_STATUS, row.stageStatus).type" effect="plain">
                {{ dictOf(STAGE_STATUS, row.stageStatus).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="retryCount" label="重试" width="80" />
          <el-table-column label="下次执行" width="170">
            <template #default="{ row }">{{ fmtDateTime(row.nextExecuteTime) }}</template>
          </el-table-column>
          <el-table-column prop="lastError" label="最近错误" min-width="200" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="文件明细" name="files">
        <el-table :data="files" size="small" border>
          <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip />
          <el-table-column prop="ppid" label="PPID" width="180" show-overflow-tooltip />
          <el-table-column prop="shardId" label="分片" width="90" />
          <el-table-column prop="tokenCount" label="Token 数" width="100" />
          <el-table-column label="下载状态" width="110">
            <template #default="{ row }">
              <el-tag :type="dictOf(FILE_DOWNLOAD_STATUS, row.downloadStatus).type" effect="plain">
                {{ dictOf(FILE_DOWNLOAD_STATUS, row.downloadStatus).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="retryCount" label="重试" width="80" />
          <el-table-column label="下载时间" width="170">
            <template #default="{ row }">{{ fmtDateTime(row.downloadTime) }}</template>
          </el-table-column>
          <el-table-column prop="lastError" label="最近错误" min-width="180" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="Token 统计" name="tokens">
        <el-table :data="detail.tokenSummary ?? []" size="small" border>
          <el-table-column label="Token 状态" width="160">
            <template #default="{ row }">
              <el-tag :type="dictOf(TOKEN_STATUS, row.status).type" effect="plain">
                {{ dictOf(TOKEN_STATUS, row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="注册状态" width="160">
            <template #default="{ row }">{{ dictOf(REGISTER_STATUS, row.registerStatus).label }}</template>
          </el-table-column>
          <el-table-column prop="count" label="数量" width="140" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="进度留痕" name="timeline">
        <el-table :data="detail.timeline ?? []" size="small" border>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ fmtDateTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column prop="action" label="动作" width="160" />
          <el-table-column prop="ppid" label="PPID" width="180" show-overflow-tooltip />
          <el-table-column prop="quantity" label="数量" width="90" />
          <el-table-column prop="operatorName" label="操作人" width="120" />
          <el-table-column prop="detail" label="明细" min-width="220" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.no {
  font-size: 15px;
}

.mb12 {
  margin-bottom: 12px;
}

.steps {
  margin-top: 18px;
}
</style>
