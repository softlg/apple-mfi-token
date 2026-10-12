<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { exportTaskApi, tokenApi } from '../api'
import { ApiError, saveBlob } from '../api/http'
import { EXPORT_STATUS, REGISTER_STATUS, TOKEN_STATUS, dictOf } from '../dict'
import { useSessionStore } from '../stores/session'
import { errorMessage, fmtDateTime, fmtNumber } from '../utils/format'
import type {
  ExportTask,
  ExportFormat,
  TokenDestroyForm,
  TokenDestroyResult,
  TokenExportForm,
  TokenPageQuery,
  TokenRow
} from '../types'

const POLL_MS = 2000
const POLL_MAX_TIMES = 60

const session = useSessionStore()
const loading = ref(false)
const rows = ref<TokenRow[]>([])
const total = ref(0)

const query = reactive<TokenPageQuery>({
  pageNum: 1,
  pageSize: 20,
  applicationNo: '',
  ppid: '',
  status: '',
  registerStatus: '',
  manufactureSn: ''
})

const exportForm = reactive<TokenExportForm>({ format: 'XLSX' })
const exporting = ref(false)
const task = ref<ExportTask | null>(null)
const taskTip = ref('')
let exportTimer: number | undefined

const destroyVisible = ref(false)
const destroyForm = reactive<TokenDestroyForm>({ applicationNo: '', confirmApplicationNo: '' })
const destroying = ref(false)
const destroyResult = ref<TokenDestroyResult | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await tokenApi.page({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      applicationNo: query.applicationNo || undefined,
      ppid: query.ppid || undefined,
      status: query.status || undefined,
      registerStatus: query.registerStatus || undefined,
      manufactureSn: query.manufactureSn || undefined
    })
    rows.value = page.records
    total.value = Number(page.total)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function reset(): void {
  query.applicationNo = ''
  query.ppid = ''
  query.status = ''
  query.registerStatus = ''
  query.manufactureSn = ''
  query.pageNum = 1
  void load()
}

function stopExportPolling(): void {
  if (exportTimer !== undefined) {
    window.clearTimeout(exportTimer)
    exportTimer = undefined
  }
}

async function startExport(): Promise<void> {
  stopExportPolling()
  exporting.value = true
  taskTip.value = ''
  try {
    task.value = await tokenApi.export({
      applicationNo: query.applicationNo || undefined,
      ppid: query.ppid || undefined,
      status: query.status || undefined,
      format: exportForm.format
    })
    taskTip.value = '导出任务已排队'
    pollExport(0)
  } catch (error) {
    task.value = null
    ElMessage.error(errorMessage(error))
  } finally {
    exporting.value = false
  }
}

/** 轮询而不是长连接：任务在服务端是异步线程池跑的，前端只要知道"能不能取件"。 */
function pollExport(times: number): void {
  if (!task.value) {
    return
  }
  if (times >= POLL_MAX_TIMES) {
    taskTip.value = '导出仍在进行，可稍后用任务号查询下载'
    return
  }
  const taskNo = task.value.taskNo
  exportTimer = window.setTimeout(async () => {
    try {
      const latest = await exportTaskApi.status(taskNo)
      task.value = latest
      if (latest.status === 'SUCCESS') {
        ElMessage.success(`导出完成，共 ${fmtNumber(latest.totalRows)} 行`)
        return
      }
      if (latest.status === 'FAILED') {
        taskTip.value = latest.failReason ?? '导出失败'
        ElMessage.error(taskTip.value)
        return
      }
      pollExport(times + 1)
    } catch (error) {
      taskTip.value = errorMessage(error)
      ElMessage.error(taskTip.value)
    }
  }, POLL_MS)
}

async function downloadTask(): Promise<void> {
  if (!task.value) {
    return
  }
  try {
    saveBlob(await exportTaskApi.download(task.value.taskNo))
  } catch (error) {
    if (error instanceof ApiError) {
      ElMessage.error(error.message)
    } else {
      ElMessage.error('下载失败')
    }
  }
}

function openDestroy(): void {
  destroyForm.applicationNo = query.applicationNo ?? ''
  destroyForm.confirmApplicationNo = ''
  destroyResult.value = null
  destroyVisible.value = true
}

async function submitDestroy(): Promise<void> {
  if (!destroyForm.applicationNo) {
    ElMessage.warning('请填写申请单号')
    return
  }
  if (destroyForm.applicationNo !== destroyForm.confirmApplicationNo) {
    ElMessage.error('两次输入的申请单号不一致，已取消')
    return
  }
  destroying.value = true
  try {
    const result = await tokenApi.destroy({
      applicationNo: destroyForm.applicationNo,
      confirmApplicationNo: destroyForm.confirmApplicationNo,
      ppid: destroyForm.ppid || undefined
    })
    destroyResult.value = result
    if (result.destroyed > 0) {
      ElMessage.success(`已销毁 ${result.destroyed} 个实体`)
      await load()
    } else {
      ElMessage.warning(result.failReason ?? '没有可销毁的实体')
    }
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    destroying.value = false
  }
}

onMounted(load)
onUnmounted(stopExportPolling)
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <el-input
        v-model.trim="query.applicationNo"
        placeholder="申请单号"
        clearable
        class="w200"
        @keyup.enter="query.pageNum = 1; load()"
      />
      <el-input
        v-model.trim="query.ppid"
        placeholder="PPID"
        clearable
        class="w160"
        @keyup.enter="query.pageNum = 1; load()"
      />
      <el-input
        v-model.trim="query.manufactureSn"
        placeholder="制造序列号"
        clearable
        class="w180"
        @keyup.enter="query.pageNum = 1; load()"
      />
      <el-select v-model="query.status" placeholder="Token 状态" clearable class="w140">
        <el-option v-for="(item, key) in TOKEN_STATUS" :key="key" :label="item.label" :value="key" />
      </el-select>
      <el-select v-model="query.registerStatus" placeholder="注册状态" clearable class="w140">
        <el-option v-for="(item, key) in REGISTER_STATUS" :key="key" :label="item.label" :value="key" />
      </el-select>
      <el-button type="primary" @click="query.pageNum = 1; load()">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="grow" />
      <el-select v-model="exportForm.format" class="w120">
        <el-option label="XLSX" value="XLSX" />
        <el-option label="CSV" value="CSV" />
      </el-select>
      <el-button :loading="exporting" @click="startExport">发起导出</el-button>
      <el-button v-if="session.isOwner" type="danger" plain @click="openDestroy">销毁实体</el-button>
    </div>

    <el-card v-if="task" class="task-card" shadow="never">
      <div class="task-row">
        <span>任务 {{ task.taskNo }}</span>
        <el-tag :type="dictOf(EXPORT_STATUS, task.status).type" effect="plain">
          {{ dictOf(EXPORT_STATUS, task.status).label }}
        </el-tag>
        <span v-if="task.totalRows !== undefined">行数 {{ fmtNumber(task.totalRows) }}</span>
        <span v-if="task.exportFormat">格式 {{ task.exportFormat }}</span>
        <span class="grow" />
        <el-button v-if="task.status === 'SUCCESS'" type="primary" @click="downloadTask">下载产物</el-button>
      </div>
      <div v-if="taskTip" class="task-tip">{{ taskTip }}</div>
    </el-card>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="id" label="ID" width="90" />
      <el-table-column prop="ppid" label="PPID" min-width="200" show-overflow-tooltip />
      <el-table-column prop="manufactureSn" label="制造序列号" min-width="180" />
      <el-table-column label="Token 状态" width="120">
        <template #default="{ row }">
          <el-tag :type="dictOf(TOKEN_STATUS, row.status).type" effect="plain">
            {{ dictOf(TOKEN_STATUS, row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="注册状态" width="120">
        <template #default="{ row }">
          {{ dictOf(REGISTER_STATUS, row.registerStatus).label }}
        </template>
      </el-table-column>
      <el-table-column label="下载时间" width="170">
        <template #default="{ row }">{{ fmtDateTime(row.downloadTime) }}</template>
      </el-table-column>
      <el-table-column label="注册时间" width="170">
        <template #default="{ row }">{{ fmtDateTime(row.registerTime) }}</template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.pageNum"
      v-model:page-size="query.pageSize"
      :total="total"
      :page-sizes="[20, 50, 100, 200]"
      layout="total, sizes, prev, pager, next"
      class="pager"
      @current-change="load"
      @size-change="query.pageNum = 1; load()"
    />

    <el-dialog v-model="destroyVisible" title="销毁未使用实体" width="520px">
      <el-alert
        type="error"
        :closable="false"
        class="mb12"
        title="不可逆"
        description="只销毁本租户该申请单里已下载、尚未注册回苹果（状态=已下发）的实体；调用苹果后即生效，事后无法恢复。"
      />
      <el-form label-width="120px">
        <el-form-item label="申请单号" required>
          <el-input v-model.trim="destroyForm.applicationNo" placeholder="例如 A261012-XXXXXXXX" />
        </el-form-item>
        <el-form-item label="PPID（选填）">
          <el-input v-model.trim="destroyForm.ppid" placeholder="留空表示该单下全部 PPID" />
        </el-form-item>
        <el-form-item label="二次确认" required>
          <el-input v-model.trim="destroyForm.confirmApplicationNo" placeholder="把上面的申请单号原样再输一次" />
        </el-form-item>
      </el-form>
      <el-descriptions v-if="destroyResult" :column="2" border size="small" class="mt12">
        <el-descriptions-item label="命中数量">{{ destroyResult.selected }}</el-descriptions-item>
        <el-descriptions-item label="已销毁">{{ destroyResult.destroyed }}</el-descriptions-item>
        <el-descriptions-item label="仍有剩余">{{ destroyResult.hasMore ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="失败原因">{{ destroyResult.failReason ?? '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="destroyVisible = false">关闭</el-button>
        <el-button type="danger" :loading="destroying" @click="submitDestroy">确认销毁</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.w200 {
  width: 200px;
}

.w180 {
  width: 180px;
}

.w160 {
  width: 160px;
}

.w140 {
  width: 140px;
}

.w120 {
  width: 120px;
}

.task-card {
  margin-bottom: 12px;
}

.task-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.task-tip {
  margin-top: 6px;
  color: #909399;
  font-size: 13px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.mb12 {
  margin-bottom: 12px;
}

.mt12 {
  margin-top: 12px;
}
</style>
