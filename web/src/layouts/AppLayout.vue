<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorMessage } from '../utils/format'
import { useSessionStore } from '../stores/session'

const MENUS = [
  { path: '/applications', title: '申请单' },
  { path: '/apply', title: '发起申请' },
  { path: '/ppid', title: 'PPID 池' },
  { path: '/tokens', title: 'Token 结果' },
  { path: '/members', title: '成员管理' },
  { path: '/tenant', title: '租户设置' },
  { path: '/credential', title: 'MFI 凭证' }
]

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

/** 详情页的路径带单号，高亮哪一项由路由 meta 指定 */
const activeMenu = computed(() => route.meta.activeMenu ?? route.path)
const tenantOptions = computed(() => session.tenants)

async function onSwitchTenant(tenantId: number): Promise<void> {
  try {
    await session.switchTenant(tenantId)
    const tenant = session.currentTenant
    ElMessage.success(`已切换到${tenant ? ` ${tenant.tenantName}` : '租户'}`)
  } catch (error) {
    ElMessage.error(errorMessage(error))
    await session.bootstrap(true)
  }
}

async function onLogout(): Promise<void> {
  await session.logout()
  await router.push('/login')
}

// 只有请求回来 A0401 才跳；主动退出走 onLogout，那时不该带 redirect 回去
watch(
  () => session.expired,
  (expired) => {
    if (!expired) {
      return
    }
    session.expired = false
    void router.replace({ path: '/login', query: { redirect: route.fullPath } })
  }
)
</script>

<template>
  <el-container class="layout">
    <el-header class="layout-header">
      <div class="brand">MFI Token 控制台</div>
      <div class="grow" />
      <el-select
        v-if="tenantOptions.length > 0"
        :model-value="session.currentTenant?.tenantId"
        :loading="session.switching"
        class="tenant-select"
        placeholder="选择租户"
        @change="onSwitchTenant"
      >
        <el-option
          v-for="item in tenantOptions"
          :key="item.tenantId"
          :label="item.tenantName"
          :value="item.tenantId"
        >
          <span>{{ item.tenantName }}</span>
          <span class="tenant-code">{{ item.tenantCode }}</span>
        </el-option>
      </el-select>
      <el-tag v-if="session.isOwner" class="owner-tag" type="warning" effect="plain">owner</el-tag>
      <el-dropdown>
        <span class="user-entry">{{ session.displayName }}</span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="onLogout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </el-header>
    <el-container class="layout-body">
      <el-aside width="190px">
        <el-menu :default-active="activeMenu" router>
          <el-menu-item v-for="item in MENUS" :key="item.path" :index="item.path">
            {{ item.title }}
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main class="layout-main">
        <el-alert
          v-if="session.isLoggedIn && tenantOptions.length === 0"
          type="warning"
          :closable="false"
          title="当前账号不属于任何租户"
          description="请让某个租户的 owner 用你的登录账号邀请你加入，或重新注册一个新租户。"
          class="no-tenant"
        />
        <router-view v-else />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.layout-header {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #ffffff;
  border-bottom: 1px solid #e4e7ed;
}

.brand {
  font-size: 16px;
  font-weight: 600;
}

.grow {
  flex: 1;
}

.tenant-select {
  width: 220px;
}

.tenant-code {
  float: right;
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}

.owner-tag {
  margin-left: 4px;
}

.user-entry {
  cursor: pointer;
  outline: none;
}

.layout-body {
  height: calc(100% - 60px);
}

.el-aside {
  background: #ffffff;
  border-right: 1px solid #e4e7ed;
}

.layout-main {
  padding: 0;
  overflow: auto;
}

.no-tenant {
  margin: 16px;
}
</style>
