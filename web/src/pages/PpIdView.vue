<script setup lang="ts">
import type { FormInstance, FormRules, UploadFile, UploadInstance } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { ppidApi } from '../api'
import { ApiError, saveBlob } from '../api/http'
import { errorMessage, fmtDateTime } from '../utils/format'
import type { PpId, PpIdCreateForm, PpIdImportResult, PpIdPageQuery, PpIdStats, PpIdUpdateForm } from '../types'

const loading = ref(false)
const rows = ref<PpId[]>([])
const total = ref(0)
const stats = ref<PpIdStats[]>([])
const showStats = ref(false)

const query = reactive<Required<Pick<PpIdPageQuery, 'pageNum' | 'pageSize'>> & PpIdPageQuery>({
  pageNum: 1,
  pageSize: 20,
  ppid: '',
  spu: '',
  productModel: '',
  status: null
})

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const editFormRef = ref<FormInstance>()
const editForm = reactive<PpIdCreateForm>({
  ppid: '',
  spu: '',
  productModel: '',
  totalQuantity: null,
  remark: ''
})
const saving = ref(false)

const importResult = ref<PpIdImportResult | null>(null)
const importVisible = ref(false)
const uploadRef = ref<UploadInstance>()

// 编辑时 PPID/SPU/产品型号是身份键，不允许改，所以两套规则分开
const createRules: FormRules<PpIdCreateForm> = {
  ppid: [{ required: true, message: '请输入 PPID', trigger: 'blur' }],
  spu: [{ required: true, message: '请输入 SPU', trigger: 'blur' }],
  productModel: [{ required: true, message: '请输入产品型号', trigger: 'blur' }],
  totalQuantity: [
    { required: true, message: '请输入可申请总量', trigger: 'blur' },
    { type: 'number', min: 1, max: 1000000, message: '总量需在 1-1000000 之间', trigger: 'blur' }
  ]
}

function resetQuery(): void {
  query.ppid = ''
  query.spu = ''
  query.productModel = ''
  query.status = null
  query.pageNum = 1
  void load()
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await ppidApi.page({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      ppid: query.ppid || undefined,
      spu: query.spu || undefined,
      productModel: query.productModel || undefined,
      status: query.status ?? undefined
    })
    rows.value = page.records
    total.value = Number(page.total)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function loadStats(): Promise<void> {
  try {
    stats.value = await ppidApi.stats()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function openCreate(): void {
  editingId.value = null
  Object.assign(editForm, { ppid: '', spu: '', productModel: '', totalQuantity: null, remark: '' })
  dialogVisible.value = true
}

function openEdit(row: PpId): void {
  editingId.value = row.id
  Object.assign(editForm, {
    ppid: row.ppid,
    spu: row.spu,
    productModel: row.productModel,
    totalQuantity: row.totalQuantity,
    remark: row.remark ?? ''
  })
  dialogVisible.value = true
}

async function submitEdit(): Promise<void> {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    if (editingId.value === null) {
      await ppidApi.create({ ...editForm, remark: editForm.remark || undefined })
      ElMessage.success('已新增额度')
    } else {
      const form: PpIdUpdateForm = {
        totalQuantity: editForm.totalQuantity,
        status: statusOf(rows.value.find((item) => item.id === editingId.value)),
        remark: editForm.remark || undefined
      }
      await ppidApi.update(editingId.value, form)
      ElMessage.success('已修改额度')
    }
    dialogVisible.value = false
    await Promise.all([load(), loadStats()])
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

function statusOf(row?: PpId): number {
  return row?.status ?? 1
}

async function disableRow(row: PpId): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `停用后 PPID ${row.ppid} 不再参与分配，已申请量仍留在库里对账。`,
      '确认停用',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await ppidApi.disable(row.id)
    ElMessage.success('已停用')
    await Promise.all([load(), loadStats()])
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function downloadTemplate(): Promise<void> {
  try {
    saveBlob(await ppidApi.template())
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function exportRows(): Promise<void> {
  try {
    saveBlob(
      await ppidApi.export({
        ppid: query.ppid || undefined,
        spu: query.spu || undefined,
        productModel: query.productModel || undefined,
        status: query.status ?? undefined
      })
    )
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

/** 导入走手动上传：后端"整份文件或留或走"，要把逐行失败原因原样回给用户。 */
async function onFileChange(file: UploadFile): Promise<void> {
  const raw = file.raw
  uploadRef.value?.clearFiles()
  if (!raw) {
    return
  }
  try {
    const result = await ppidApi.importExcel(raw)
    importResult.value = result
    importVisible.value = true
    if (result.insertedRows > 0) {
      ElMessage.success(`已导入 ${result.insertedRows} 行`)
      await Promise.all([load(), loadStats()])
    }
  } catch (error) {
    if (error instanceof ApiError) {
      ElMessage.error(error.message)
    } else {
      ElMessage.error('导入失败，请检查文件格式')
    }
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <el-input v-model.trim="query.ppid" placeholder="PPID 模糊" clearable class="w160" @keyup.enter="query.pageNum = 1; load()" />
      <el-input v-model.trim="query.productModel" placeholder="产品型号" clearable class="w160" @keyup.enter="query.pageNum = 1; load()" />
      <el-input v-model.trim="query.spu" placeholder="SPU" clearable class="w160" @keyup.enter="query.pageNum = 1; load()" />
      <el-select v-model="query.status" placeholder="状态" clearable class="w120">
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-button type="primary" @click="query.pageNum = 1; load()">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
      <div class="grow" />
      <el-button @click="showStats = !showStats">{{ showStats ? '收起总览' : '池子总览' }}</el-button>
      <el-button @click="downloadTemplate">下载模板</el-button>
      <el-upload
        ref="uploadRef"
        :show-file-list="false"
        :auto-upload="false"
        accept=".xlsx,.xls"
        :on-change="onFileChange"
      >
        <el-button>Excel 导入</el-button>
      </el-upload>
      <el-button @click="exportRows">导出</el-button>
      <el-button type="primary" @click="openCreate">新增额度</el-button>
    </div>

    <el-table v-if="showStats" :data="stats" size="small" class="stats" border>
      <el-table-column prop="productModel" label="产品型号" min-width="140" />
      <el-table-column prop="spu" label="SPU" min-width="140" />
      <el-table-column prop="ppidRows" label="PPID 行数" width="100" />
      <el-table-column prop="enabledRows" label="启用行数" width="100" />
      <el-table-column prop="totalQuantity" label="总量" width="110" />
      <el-table-column prop="appliedQuantity" label="已申请" width="110" />
      <el-table-column prop="remainingQuantity" label="剩余可分配" width="120" />
    </el-table>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="ppid" label="PPID" min-width="180" show-overflow-tooltip />
      <el-table-column prop="productModel" label="产品型号" width="140" />
      <el-table-column prop="spu" label="SPU" width="140" />
      <el-table-column prop="totalQuantity" label="总量" width="100" />
      <el-table-column prop="appliedQuantity" label="已申请" width="100" />
      <el-table-column prop="remainingQuantity" label="剩余" width="100" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="plain">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ fmtDateTime(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status === 1" link type="danger" @click="disableRow(row)">停用</el-button>
        </template>
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

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增额度' : '编辑额度'"
      width="460px"
    >
      <el-form ref="editFormRef" :model="editForm" :rules="createRules" label-width="110px">
        <el-form-item label="PPID" prop="ppid">
          <el-input v-model.trim="editForm.ppid" :disabled="editingId !== null" />
        </el-form-item>
        <el-form-item label="产品型号" prop="productModel">
          <el-input v-model.trim="editForm.productModel" :disabled="editingId !== null" />
        </el-form-item>
        <el-form-item label="SPU" prop="spu">
          <el-input v-model.trim="editForm.spu" :disabled="editingId !== null" />
        </el-form-item>
        <el-form-item label="可申请总量" prop="totalQuantity">
          <el-input-number v-model="editForm.totalQuantity" :min="0" :max="1000000" class="w160" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model.trim="editForm.remark" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="导入结果" width="620px">
      <el-descriptions v-if="importResult" :column="3" border size="small">
        <el-descriptions-item label="读取行数">{{ importResult.readRows }}</el-descriptions-item>
        <el-descriptions-item label="入库行数">{{ importResult.insertedRows }}</el-descriptions-item>
        <el-descriptions-item label="拒绝行数">{{ importResult.rejectedRows }}</el-descriptions-item>
      </el-descriptions>
      <el-alert
        v-if="importResult && (importResult.errors?.length ?? 0) > 0"
        class="import-alert"
        type="warning"
        :closable="false"
        title="整份导入未生效：只要有任一行不合法，服务端就不入库。修正后请重新上传整份文件。"
      />
      <el-table v-if="importResult?.errors?.length" :data="importResult.errors" size="small" max-height="280" border>
        <el-table-column prop="rowNo" label="行号" width="80" />
        <el-table-column prop="ppid" label="PPID" width="180" show-overflow-tooltip />
        <el-table-column prop="message" label="原因" min-width="220" />
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.w160 {
  width: 160px;
}

.w120 {
  width: 120px;
}

.stats {
  margin-bottom: 12px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.import-alert {
  margin: 12px 0;
}
</style>
