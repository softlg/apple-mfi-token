import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { useSessionStore } from '../stores/session'
import AppLayout from '../layouts/AppLayout.vue'
import ApplicationDetailView from '../pages/ApplicationDetailView.vue'
import ApplicationListView from '../pages/ApplicationListView.vue'
import ApplyView from '../pages/ApplyView.vue'
import CredentialView from '../pages/CredentialView.vue'
import LoginView from '../pages/LoginView.vue'
import MemberView from '../pages/MemberView.vue'
import PpIdView from '../pages/PpIdView.vue'
import RegisterView from '../pages/RegisterView.vue'
import TenantSettingsView from '../pages/TenantSettingsView.vue'
import TokenListView from '../pages/TokenListView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 免登录页面 */
    public?: boolean
    /** 详情页等高亮不到自身路径的页面，用它在菜单里指定归属项 */
    activeMenu?: string
  }
}

/**
 * 用 hash 路由：后端是单进程同时托管静态资源与 API，不打算再为 SPA 加一条转发规则，
 * 刷新 `/applications/xxx` 这类深层路径也不会 404。
 */
const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: LoginView, meta: { public: true, title: '登录' } },
  { path: '/register', name: 'register', component: RegisterView, meta: { public: true, title: '注册' } },
  {
    path: '/',
    component: AppLayout,
    redirect: '/applications',
    children: [
      { path: 'applications', name: 'applications', component: ApplicationListView, meta: { title: '申请单' } },
      { path: 'apply', name: 'apply', component: ApplyView, meta: { title: '发起申请' } },
      {
        path: 'applications/:applicationNo',
        name: 'application-detail',
        component: ApplicationDetailView,
        meta: { title: '申请进度', activeMenu: '/applications' }
      },
      { path: 'ppid', name: 'ppid', component: PpIdView, meta: { title: 'PPID 池' } },
      { path: 'tokens', name: 'tokens', component: TokenListView, meta: { title: 'Token 结果' } },
      { path: 'members', name: 'members', component: MemberView, meta: { title: '成员管理' } },
      { path: 'tenant', name: 'tenant', component: TenantSettingsView, meta: { title: '租户设置' } },
      { path: 'credential', name: 'credential', component: CredentialView, meta: { title: 'MFI 凭证' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/applications' }
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  await session.bootstrap()
  if (to.meta.public) {
    return session.isLoggedIn ? { path: '/' } : true
  }
  if (!session.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = title ? `${title} · MFI Token 控制台` : 'MFI Token 控制台'
})
