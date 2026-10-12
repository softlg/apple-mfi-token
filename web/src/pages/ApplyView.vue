<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ppidApi, applicationApi } from '../api'
import { errorMessage } from '../utils/format'
import type { ApplyForm, ApplySubmitResult, PpIdAvailable, PpIdStats } from '../types'

const formRef = ref<FormInstance>()
const form = reactive<ApplyForm>({ productModel: '', spu: '', applyQuantity: null, requestId: '' })
const pools = ref<PpIdStats[]>([])
const preview = ref<PpIdAvailable | null>(null)
const previewing = ref(false)
const submitting = ref(false)
const result = ref<ApplySubmitResult | null>(null)
const router = useRouter()

const rules: FormRules<ApplyForm> = {
  productModel: [{ required: true, message: '请选择或输入产品型号', trigger: 'blur' }],
  spu: [{ required: true, message: '请选择或输入 SPU', trigger: 'blur' }],
  applyQuantity: [
    { required: true, message: '请输入申请数量', trigger: 'blur' },
    { type: 'number', min: 1, message: '申请数量至少为 1', trigger: 'blur' }
  ]
}

/**
 * 幂等键在同一次填写里保持不变：网络抖动或重复点击要能被服务端认出来并返回同一张单。
 * 成功之后必须换键，否则下一次真实申请会被当成重复提交。
 */
function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

const insufficient = computed(() => preview.value !== null && preview.value.sufficient === false)

async function loadPools(): Promise<void> {
  try {
    pools.value = await ppidApi.stats()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

let previewTimer: number | undefined

/** 余量预览与真实分配同序同余量，所以在提交前就能告出"池子不够"。 */
function schedulePreview(): void {
  if (previewTimer !== undefined) {
    window.clearTimeout(previewTimer)
  }
  previewTimer = window.setTimeout(runPreview, 300)
}

async function runPreview(): Promise<void> {
  if (!form.productModel || !form.spu || !form.applyQuantity || form.applyQuantity < 1) {
    preview.value = null
    return
  }
  previewing.value = true
  try {
    preview.value = await ppidApi.available(form.productModel, form.spu, form.applyQuantity)
  } catch (error) {
    preview.value = null
    ElMessage.error(errorMessage(error))
  } finally {
    previewing.value = false
  }
}

watch(() => [form.productModel, form.spu, form.applyQuantity], schedulePreview)

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    const submitted = await applicationApi.submit({ ...form })
    result.value = submitted
    if (submitted.duplicated) {
      ElMessage.warning(`该请求已提交过，返回原申请单 ${submitted.applicationNo}`)
    } else {
      ElMessage.success(`已受理，申请单号 ${submitted.applicationNo}`)
    }
    form.requestId = newRequestId()
    await Promise.all([runPreview(), loadPools()])
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    submitting.value = false
  }
}

function goDetail(): void {
  if (result.value) {
    void router.push(`/applications/${encodeURIComponent(result.value.applicationNo)}`)
  }
}

onMounted(() => {
  form.requestId = newRequestId()
  void loadPools()
})
</script>

<template>
  <div class="page">
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card>
          <template #header>发起 Token 申请</template>
          <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
            <el-form-item label="产品型号" prop="productModel">
              <el-select
                v-model="form.productModel"
                filterable
                allow-create
                default-first-option
                placeholder="从池子总览选择，或直接输入"
              >
                <el-option
                  v-for="item in pools"
                  :key="`m-${item.productModel}-${item.spu}`"
                  :label="item.productModel"
                  :value="item.productModel"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="SPU" prop="spu">
              <el-select
                v-model="form.spu"
                filterable
                allow-create
                default-first-option
                placeholder="从池子总览选择，或直接输入"
              >
                <el-option
                  v-for="item in pools"
                  :key="`s-${item.productModel}-${item.spu}`"
                  :label="`${item.spu}（剩余 ${item.remainingQuantity}）`"
                  :value="item.spu"
                  :disabled="item.productModel !== form.productModel"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="申请数量" prop="applyQuantity">
              <el-input-number v-model="form.applyQuantity" :min="1" :max="100000" class="w180" />
            </el-form-item>
            <el-form-item label="幂等键">
              <el-input v-model="form.requestId" readonly>
                <template #append>
                  <el-button @click="form.requestId = newRequestId()">换新键</el-button>
                </template>
              </el-input>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="onSubmit">提交申请</el-button>
            </el-form-item>
          </el-form>

          <el-alert
            v-if="insufficient"
            type="error"
            :closable="false"
            :title="`额度不足：本池可分配 ${preview?.allocatableQuantity ?? 0}，本次需要 ${form.applyQuantity ?? 0}`"
            description="先补充或启用 PPID，再提交；不足时服务端会整单拒绝，不会部分扣减。"
          />
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card v-loading="previewing">
          <template #header>分配预览</template>
          <el-descriptions v-if="preview" :column="2" border size="small" class="mb12">
            <el-descriptions-item label="产品型号">{{ preview.productModel }}</el-descriptions-item>
            <el-descriptions-item label="SPU">{{ preview.spu }}</el-descriptions-item>
            <el-descriptions-item label="申请数量">{{ preview.requestedQuantity }}</el-descriptions-item>
            <el-descriptions-item label="可分配总量">{{ preview.allocatableQuantity }}</el-descriptions-item>
          </el-descriptions>
          <el-table v-if="preview?.plan?.length" :data="preview.plan" size="small" border>
            <el-table-column prop="ppid" label="PPID" min-width="180" show-overflow-tooltip />
            <el-table-column prop="quantity" label="本次分配" width="110" />
            <el-table-column prop="remainingBefore" label="分配前剩余" width="120" />
          </el-table>
          <el-empty v-else description="填好型号、SPU 与数量后自动预览" />
        </el-card>

        <el-card v-if="result" class="mt12">
          <template #header>受理结果</template>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="申请单号">{{ result.applicationNo }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ result.status }}</el-descriptions-item>
            <el-descriptions-item label="申请数量">{{ result.applyQuantity }}</el-descriptions-item>
            <el-descriptions-item label="PPID 分片">{{ result.ppidCount }}</el-descriptions-item>
          </el-descriptions>
          <el-button type="primary" class="mt12" @click="goDetail">查看进度</el-button>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.w180 {
  width: 180px;
}

.mb12 {
  margin-bottom: 12px;
}

.mt12 {
  margin-top: 12px;
}
</style>
