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
  ],
})

router.beforeEach((to) => {
  if ((to.path === '/documents' || to.path === '/chat' || to.path === '/retrieval' || to.path === '/agents' || to.path === '/settings') && !localStorage.getItem('accessToken')) {
    return { path: '/knowledge' }
  }
  return true
})

export default router
