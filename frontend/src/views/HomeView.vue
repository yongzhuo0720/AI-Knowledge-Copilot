<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { getHealth } from '@/services/api'

const backendStatus = ref('检查中')

onMounted(async () => {
  try {
    const response = await getHealth()
    backendStatus.value = response.data.status
  } catch {
    backendStatus.value = '不可用'
  }
})
</script>

<template>
  <main class="shell">
    <section class="hero">
      <p class="eyebrow">AI KNOWLEDGE COPILOT</p>
      <h1>企业知识，从理解开始。</h1>
      <p class="summary">统一管理知识资产，用可靠的 AI 助手连接团队经验与业务决策。</p>
      <div class="status-card">
        <span class="status-dot" :class="{ online: backendStatus === 'UP' }" />
        <span>后端服务：{{ backendStatus }}</span>
      </div>
    </section>
  </main>
</template>
