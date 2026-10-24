import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
    },
    {
      path: '/knowledge',
      name: 'knowledge',
      component: () => import('@/views/KnowledgeView.vue'),
    },
    {
      path: '/documents',
      name: 'documents',
      component: () => import('@/views/DocumentsView.vue'),
    },
    {
      path: '/chat',
      name: 'chat',
      component: () => import('@/views/ChatView.vue'),
    },
    {
      path: '/retrieval',
      name: 'retrieval',
      component: () => import('@/views/RetrievalView.vue'),
    },
    {
      path: '/agents',
      name: 'agents',
      component: () => import('@/views/AgentView.vue'),
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue'),
    },
    {
      path: '/admin',
      name: 'admin',
      component: () => import('@/views/AdminView.vue'),
    },
    {
      // 兜底：未注册的路径一律回到总览，避免侧栏在、主区空白的「假死页」。
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

const PROTECTED_PATHS = ['/documents', '/chat', '/retrieval', '/agents', '/settings', '/admin']

/** 从 localStorage 读当前登录用户。解析失败一律当未登录处理，不让坏数据把路由卡死。 */
function readStoredUser(): { isAdmin?: boolean } | null {
  const stored = localStorage.getItem('currentUser')
  if (!stored) return null
  try { return JSON.parse(stored) as { isAdmin?: boolean } } catch { return null }
}

router.beforeEach((to) => {
  if (!PROTECTED_PATHS.includes(to.path)) return true
  if (!localStorage.getItem('accessToken')) return { path: '/knowledge' }
  // /admin 额外要求管理员身份。
  //
  // 真正的防线是后端的 AdminService.requireAdmin（非管理员一律 403），这里只是**纵深防御**：
  // 光靠后端兜底的话，普通用户手输 /admin 会看到一页加载失败的空表格，
  // 既浪费一次注定失败的请求，又把「这里有个后台」暴露给了不该看到的人。
  if (to.path === '/admin' && !readStoredUser()?.isAdmin) return { path: '/' }
  return true
})

export default router
