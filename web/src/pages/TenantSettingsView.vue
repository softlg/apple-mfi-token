<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { tenantApi } from '../api'
import { useSessionStore } from '../stores/session'
import { errorMessage } from '../utils/format'
import type { TenantConfigItem, TenantProfile, TenantProfileForm } from '../types'

const session = useSessionStore()
const loading = ref(false)
const savingProfile = ref(false)
const savingConfig = ref(false)

const profileRef = ref<FormInstance>()
const profile = ref<TenantProfile | null>(null)
const profileForm = reactive<TenantProfileForm>({ tenantName: '', appleTechnology: '', appleBaseUrl: '' })

const configRows = ref<TenantConfigItem[]>([])
/** 原值用于 diff：只把改动过的键提交，未提交的键服务端保持原样 */
const configOriginal = ref<Record<string, string>>({})
const configDraft = reactive<Record<string, string>>({})

const rules: FormRules<TenantProfileForm> = {
  tenantName: [{ required: true, message: '租户名称不能为空', trigger: 'blur' }],
  appleBaseUrl: [
    {
      pattern: /^$|^https:\/\/[^\s]+$/,
      message: 'BaseURL 需为 https:// 开头的完整地址，留空表示使用全局默认',
      trigger: 'blur'
    }
  ]
}

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    const [tenantProfile, configs] = await Promise.all([tenantApi.profile(), tenantApi.config()])
    profile.value = tenantProfile
    Object.assign(profileForm, {
      tenantName: tenantProfile.tenantName,
      appleTechnology: tenantProfile.appleTechnology ?? '',
      appleBaseUrl: tenantProfile.appleBaseUrl ?? ''
    })
    configRows.value = configs
    const original: Record<string, string> = {}
    configs.forEach((item) => {
      original[item.key] = item.effectiveValue ?? ''
      configDraft[item.key] = item.effectiveValue ?? ''
    })
    configOriginal.value = original
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function saveProfile(): Promise<void> {
  const valid = await profileRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  savingProfile.value = true
  try {
    profile.value = await tenantApi.updateProfile({
      tenantName: profileForm.tenantName,
      appleTechnology: profileForm.appleTechnology || undefined,
      appleBaseUrl: profileForm.appleBaseUrl || undefined
    })
    ElMessage.success('已保存')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    savingProfile.value = false
  }
}

async function saveConfig(): Promise<void> {
  const changed: Record<string, string> = {}
  Object.keys(configDraft).forEach((key) => {
    if ((configOriginal.value[key] ?? '') !== configDraft[key]) {
      changed[key] = configDraft[key] ?? ''
    }
  })
  if (Object.keys(changed).length === 0) {
    ElMessage.info('没有需要保存的修改')
    return
  }
  savingConfig.value = true
  try {
    configRows.value = await tenantApi.updateConfig(changed)
    const original: Record<string, string> = {}
    configRows.value.forEach((item) => {
      original[item.key] = item.effectiveValue ?? ''
      configDraft[item.key] = item.effectiveValue ?? ''
    })
    configOriginal.value = original
    ElMessage.success('已保存业务参数')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    savingConfig.value = false
  }
}

onMounted(loadAll)
</script>

<template>
  <div class="page" v-loading="loading">
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card>
          <template #header>租户信息</template>
          <el-descriptions v-if="!session.isOwner && profile" :column="1" border size="small">
            <el-descriptions-item label="租户编码">{{ profile.tenantCode }}</el-descriptions-item>
            <el-descriptions-item label="租户名称">{{ profile.tenantName }}</el-descriptions-item>
            <el-descriptions-item label="technology">{{ profile.appleTechnology ?? '（全局默认）' }}</el-descriptions-item>
            <el-descriptions-item label="BaseURL 覆盖">
              {{ profile.appleBaseUrl ?? '（未覆盖）' }}
            </el-descriptions-item>
            <el-descriptions-item label="生效 BaseURL">{{ profile.effectiveBaseUrl ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ profile.status === 1 ? '正常' : '已停用' }}</el-descriptions-item>
          </el-descriptions>

          <el-form
            v-else-if="profile"
            ref="profileRef"
            :model="profileForm"
            :rules="rules"
            label-width="120px"
          >
            <el-form-item label="租户编码">
              <el-input :model-value="profile.tenantCode" disabled />
            </el-form-item>
            <el-form-item label="租户名称" prop="tenantName">
              <el-input v-model.trim="profileForm.tenantName" />
            </el-form-item>
            <el-form-item label="technology" prop="appleTechnology">
              <el-input v-model.trim="profileForm.appleTechnology" placeholder="例如 MFi-auth，留空用全局默认" />
            </el-form-item>
            <el-form-item label="BaseURL 覆盖" prop="appleBaseUrl">
              <el-input v-model.trim="profileForm.appleBaseUrl" placeholder="留空表示使用全局默认地址" />
            </el-form-item>
            <el-form-item label="生效 BaseURL">
              <span class="readonly">{{ profile.effectiveBaseUrl ?? '-' }}</span>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存</el-button>
              <el-button @click="loadAll">重新加载</el-button>
            </el-form-item>
            <el-alert
              type="info"
              :closable="false"
              title="technology 或 BaseURL 变更后，服务端会立即重建该租户的 MFI 客户端。"
            />
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card>
          <template #header>
            <div class="card-head">
              <span>业务参数</span>
              <el-button v-if="session.isOwner" type="primary" size="small" :loading="savingConfig" @click="saveConfig">
                保存修改
              </el-button>
            </div>
          </template>
          <el-table :data="configRows" size="small" border>
            <el-table-column prop="key" label="参数" width="240" />
            <el-table-column label="生效值" width="180">
              <template #default="{ row }">
                <el-input
                  v-if="session.isOwner"
                  v-model="configDraft[row.key]"
                  size="small"
                  :placeholder="`默认 ${row.defaultValue ?? '-'}`"
                />
                <span v-else>{{ row.effectiveValue ?? '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="defaultValue" label="全局默认" width="120" />
            <el-table-column label="覆盖" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.overridden" type="warning" effect="plain">已覆盖</el-tag>
                <span v-else>默认</span>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="说明" min-width="180" show-overflow-tooltip />
          </el-table>
          <div class="hint">生效值留空并保存 = 该键回到全局默认；申请单次上限填 0 表示不限制。</div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.readonly {
  color: #606266;
}

.hint {
  margin-top: 10px;
  color: #909399;
  font-size: 13px;
}
</style>
