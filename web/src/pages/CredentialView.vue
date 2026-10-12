<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { credentialApi } from '../api'
import { ApiError } from '../api/http'
import { useSessionStore } from '../stores/session'
import { errorMessage, fmtDate, fmtDateTime } from '../utils/format'
import type { CredentialMeta, CredentialVerify } from '../types'

const CODE_MISSING = 'B0404'

const session = useSessionStore()
const loading = ref(false)
const meta = ref<CredentialMeta | null>(null)
const missing = ref(false)

const uploadVisible = ref(false)
const uploading = ref(false)
/**
 * 两条来源分开存：文件读出来的 PEM 只在内存里，绝不进 DOM；粘贴框里的内容本来就是用户自己要看的。
 * 早期实现把两者共用一个 model，结果选完文件后私钥原文出现在隐藏 textarea 与可访问性树里，
 * 任何截图、DOM 检视或读屏都能读到——这是私钥不该待的地方。
 */
const fromFile = reactive({ cert: '', key: '' })
const pasted = reactive({ cert: '', key: '' })
const pasteOpen = ref(false)
const picked = reactive({ cert: '', key: '' })

const verifying = ref(false)
const verifyResult = ref<CredentialVerify | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    meta.value = await credentialApi.metadata()
    missing.value = false
  } catch (error) {
    // 没配凭证是预期状态而不是故障，给引导而不是报错
    if (error instanceof ApiError && error.code === CODE_MISSING) {
      meta.value = null
      missing.value = true
    } else {
      ElMessage.error(errorMessage(error))
    }
  } finally {
    loading.value = false
  }
}

function openUpload(): void {
  fromFile.cert = ''
  fromFile.key = ''
  pasted.cert = ''
  pasted.key = ''
  picked.cert = ''
  picked.key = ''
  pasteOpen.value = false
  uploadVisible.value = true
}

/**
 * 厂商拿到的是苹果下发的 .cer 与 .key 文件，选文件比粘贴省事，也避免私钥原文出现在
 * 剪贴板历史与页面输入框里。读进内存后只在提交那一瞬存在。
 */
async function onPick(kind: 'cert' | 'key', event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  fromFile[kind] = await file.text()
  picked[kind] = `${file.name}（${(file.size / 1024).toFixed(1)} KB）`
  pasted[kind] = ''
  input.value = ''
}

async function submitUpload(): Promise<void> {
  const certPem = fromFile.cert || pasted.cert
  const keyPem = fromFile.key || pasted.key
  if (!certPem.includes('BEGIN') || !keyPem.includes('BEGIN')) {
    ElMessage.error('请提供证书与私钥：选文件或粘贴完整 PEM（含 BEGIN/END 行）')
    return
  }
  uploading.value = true
  try {
    await credentialApi.replace(certPem, keyPem)
    missing.value = false
    // 明文只活到这一刻，提交后立刻擦掉
    fromFile.cert = ''
    fromFile.key = ''
    pasted.cert = ''
    pasted.key = ''
    pasteOpen.value = false
    picked.cert = ''
    picked.key = ''
    uploadVisible.value = false
    ElMessage.success('凭证已加密入库，MFI 客户端已重建')
    // 入库返回的是内存对象，update_time 由库默认值填充，只能再读一次才拿得到
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    uploading.value = false
  }
}

async function runVerify(): Promise<void> {
  verifying.value = true
  verifyResult.value = null
  try {
    verifyResult.value = await credentialApi.verify()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    verifying.value = false
  }
}

async function removeCredential(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '删除后本租户无法再向苹果发起任何调用，进行中的申请会停在当前阶段。确认删除？',
      '确认删除凭证',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await credentialApi.remove()
    meta.value = null
    missing.value = true
    ElMessage.success('已删除')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <el-alert
      v-if="missing"
      type="warning"
      :closable="false"
      class="mb12"
      title="本租户尚未配置 MFI 客户端证书"
      description="苹果按厂商证书识别调用方，没有证书无法申请 Token。证书与私钥由苹果 Developer 门户下发（.cer / .pem）。"
    />

    <el-card>
      <template #header>
        <div class="card-head">
          <span>MFI 客户端凭证</span>
          <div v-if="session.isOwner">
            <el-button size="small" :disabled="missing" :loading="verifying" @click="runVerify">
              连通性自检
            </el-button>
            <el-button size="small" :disabled="missing" type="danger" plain @click="removeCredential">
              删除
            </el-button>
            <el-button size="small" type="primary" @click="openUpload">
              {{ missing ? '上传凭证' : '替换凭证' }}
            </el-button>
          </div>
        </div>
      </template>

      <el-descriptions v-if="meta" :column="2" border size="small">
        <el-descriptions-item label="证书指纹">
          <span class="mono">{{ meta.fingerprint ?? '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="版本">v{{ meta.version ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="Subject" :span="2">{{ meta.certSubject ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="到期日">{{ fmtDate(meta.certNotAfter) }}</el-descriptions-item>
        <el-descriptions-item label="剩余天数">
          <el-tag
            v-if="meta.daysToExpire !== undefined"
            :type="meta.daysToExpire < 30 ? 'danger' : 'success'"
            effect="plain"
          >
            {{ meta.daysToExpire }} 天
          </el-tag>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="加密密钥 ID">{{ meta.encryptKeyId ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ fmtDateTime(meta.updateTime) }}</el-descriptions-item>
      </el-descriptions>
      <el-empty v-else description="还没有凭证" />

      <el-alert
        v-if="!session.isOwner"
        class="mt12"
        type="info"
        :closable="false"
        title="证书与私钥的上传、替换、删除仅限租户 owner。"
      />

      <el-alert
        v-if="verifyResult"
        class="mt12"
        :type="verifyResult.reachable ? 'success' : 'error'"
        :closable="false"
        :title="verifyResult.reachable ? '握手成功，对端接受了本证书' : '握手失败'"
        :description="`${verifyResult.httpStatus ?? '-'} ${verifyResult.detail ?? ''}`"
      />
    </el-card>

    <el-dialog v-model="uploadVisible" title="上传凭证" width="560px">
      <el-alert
        type="warning"
        :closable="false"
        class="mb12"
        title="服务端会先校验 PEM 格式、证书与私钥配对、有效期，三项都通过才加密入库；任何接口都不回显原文。"
      />
      <el-form label-position="top">
        <el-form-item label="证书文件（.cer / .crt / .pem）">
          <input type="file" accept=".cer,.crt,.pem,.txt" @change="onPick('cert', $event)" />
          <div v-if="picked.cert" class="hint">已选择 {{ picked.cert }}</div>
        </el-form-item>
        <el-form-item label="私钥文件（.key / .pem，PKCS#1 或 PKCS#8 均可）">
          <input type="file" accept=".key,.pem,.txt" @change="onPick('key', $event)" />
          <div v-if="picked.key" class="hint">已选择 {{ picked.key }}</div>
        </el-form-item>
      </el-form>
      <el-button link type="primary" @click="pasteOpen = !pasteOpen">
        {{ pasteOpen ? '收起粘贴输入' : '改为直接粘贴 PEM' }}
      </el-button>
      <el-form v-if="pasteOpen" label-position="top" class="mt12">
        <el-form-item label="证书 PEM">
          <el-input v-model="pasted.cert" type="textarea" :rows="5" spellcheck="false" />
        </el-form-item>
        <el-form-item label="私钥 PEM">
          <el-input v-model="pasted.key" type="textarea" :rows="5" spellcheck="false" />
        </el-form-item>
        <div class="hint">粘贴内容会显示在页面上，保存成功后请立即关闭本页面。</div>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">校验并保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.mono {
  font-family: Consolas, Monaco, monospace;
}

.hint {
  margin-top: 4px;
  color: #909399;
  font-size: 13px;
}

.mb12 {
  margin-bottom: 12px;
}

.mt12 {
  margin-top: 12px;
}
</style>
