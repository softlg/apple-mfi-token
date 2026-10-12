<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { memberApi } from '../api'
import { useSessionStore } from '../stores/session'
import { errorMessage, fmtDateTime } from '../utils/format'
import type { Member } from '../types'

const session = useSessionStore()
const loading = ref(false)
const rows = ref<Member[]>([])
const inviteVisible = ref(false)
const inviteAccount = ref('')
const inviting = ref(false)
const transferVisible = ref(false)
const targetUserId = ref<number | null>(null)

const candidates = computed(() => rows.value.filter((item) => item.ownerFlag !== 1 && item.status === 1))

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await memberApi.list()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function openInvite(): void {
  inviteAccount.value = ''
  inviteVisible.value = true
}

async function submitInvite(): Promise<void> {
  if (!inviteAccount.value) {
    ElMessage.warning('请输入对方的登录账号')
    return
  }
  inviting.value = true
  try {
    await memberApi.invite(inviteAccount.value)
    ElMessage.success('已加入本租户')
    inviteVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    inviting.value = false
  }
}

async function toggleStatus(row: Member): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  try {
    await memberApi.updateStatus(row.memberId, next)
    ElMessage.success(next === 1 ? '已启用' : '已停用')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function removeRow(row: Member): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `移除后 ${row.name}（${row.account}）立即失去本租户访问权，其当前会话也会失效。`,
      '确认移除成员',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await memberApi.remove(row.memberId)
    ElMessage.success('已移除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function openTransfer(): void {
  targetUserId.value = null
  transferVisible.value = true
}

async function submitTransfer(): Promise<void> {
  if (targetUserId.value === null) {
    ElMessage.warning('请选择接收人')
    return
  }
  try {
    await ElMessageBox.confirm(
      '转让后你立即降为普通成员，owner 相关的写操作将由对方承担。',
      '确认转让 owner',
      { type: 'warning', confirmButtonText: '确认转让', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await memberApi.transferOwner(targetUserId.value)
    ElMessage.success('已转让，当前身份已降级')
    transferVisible.value = false
    await session.bootstrap(true)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <strong>成员管理</strong>
      <span class="hint">本产品只有 owner 一位：移除成员与转让 owner 仅限 owner，其余写操作任何成员可用。</span>
      <div class="grow" />
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="openInvite">邀请成员</el-button>
      <el-button v-if="session.isOwner" @click="openTransfer">转让 owner</el-button>
    </div>

    <el-alert
      v-if="!session.isOwner"
      class="mb12"
      type="info"
      :closable="false"
      title="你不是本租户 owner，移除成员与转让 owner 不可用。"
    />

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="account" label="登录账号" min-width="140" />
      <el-table-column prop="name" label="姓名" width="130" />
      <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
      <el-table-column prop="phone" label="手机号" width="140" />
      <el-table-column label="身份" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.ownerFlag === 1" type="warning" effect="plain">owner</el-tag>
          <span v-else>成员</span>
        </template>
      </el-table-column>
      <el-table-column label="租户内状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="plain">
            {{ row.status === 1 ? '正常' : '已停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="账号状态" width="110">
        <template #default="{ row }">{{ row.userStatus === 1 ? '正常' : '已禁用' }}</template>
      </el-table-column>
      <el-table-column label="加入时间" width="170">
        <template #default="{ row }">{{ fmtDateTime(row.joinTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.ownerFlag !== 1" link type="primary" @click="toggleStatus(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
          <el-button
            v-if="session.isOwner && row.ownerFlag !== 1"
            link
            type="danger"
            @click="removeRow(row)"
          >
            移除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="inviteVisible" title="邀请成员" width="420px">
      <el-form label-width="90px">
        <el-form-item label="登录账号">
          <el-input v-model.trim="inviteAccount" placeholder="对方需已注册" />
        </el-form-item>
      </el-form>
      <div class="hint">邀请以登录账号为键；对方无需接受，加入后立即以普通成员身份进入本租户。</div>
      <template #footer>
        <el-button @click="inviteVisible = false">取消</el-button>
        <el-button type="primary" :loading="inviting" @click="submitInvite">邀请</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="transferVisible" title="转让 owner" width="420px">
      <el-select v-model="targetUserId" placeholder="选择接收人" class="full">
        <el-option
          v-for="item in candidates"
          :key="item.userId"
          :label="`${item.name}（${item.account}）`"
          :value="item.userId"
        />
      </el-select>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="warning" @click="submitTransfer">下一步</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint {
  color: #909399;
  font-size: 13px;
}

.mb12 {
  margin-bottom: 12px;
}

.full {
  width: 100%;
}
</style>
