<script setup lang="ts">
import { ref } from 'vue'

import { createKnowledgeBase, uploadDocument, type KnowledgeBase, type KnowledgeDocument } from '@/services/api'

const workspaceId = ref('')
const name = ref('')
const description = ref('')
const knowledgeBase = ref<KnowledgeBase | null>(null)
const document = ref<KnowledgeDocument | null>(null)
const message = ref('')
const loading = ref(false)
const selectedFile = ref<File | null>(null)

async function create() {
  message.value = ''
  loading.value = true
  try {
    const response = await createKnowledgeBase(Number(workspaceId.value), name.value, description.value)
    knowledgeBase.value = response.data
    message.value = '知识库创建成功，可以登记文档元数据。'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '创建失败'
  } finally {
    loading.value = false
  }
}

function selectFile(event: Event) {
  selectedFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
}

async function upload() {
  if (!knowledgeBase.value || !selectedFile.value) return
  loading.value = true
  try {
    const response = await uploadDocument(knowledgeBase.value.id, selectedFile.value)
    document.value = response.data
    message.value = '文档上传成功，已登记并提交解析任务。'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '登记失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="workspace-shell">
    <header class="workspace-header">
      <p class="eyebrow">KNOWLEDGE WORKSPACE</p>
      <h1>把团队知识，整理成可协作的资产。</h1>
      <p class="summary">创建知识库后上传文档，系统会将文件保存到对象存储并提交 AI 解析。</p>
    </header>

    <section class="workspace-grid">
      <form class="panel" @submit.prevent="create">
        <h2>创建知识库</h2>
        <label>工作空间 ID<input v-model="workspaceId" type="number" min="1" required /></label>
        <label>名称<input v-model="name" maxlength="128" required placeholder="例如：研发规范" /></label>
        <label>描述<textarea v-model="description" maxlength="500" placeholder="可选" /></label>
        <button :disabled="loading" type="submit">{{ loading ? '处理中…' : '创建知识库' }}</button>
      </form>

      <section class="panel result-panel">
        <h2>当前知识库</h2>
        <div v-if="knowledgeBase" class="result-card">
          <strong>{{ knowledgeBase.name }}</strong>
          <span>编号 #{{ knowledgeBase.id }} · {{ knowledgeBase.status }}</span>
          <label class="file-picker">选择文档<input type="file" @change="selectFile" /></label>
          <button :disabled="loading || !selectedFile" type="button" @click="upload">{{ loading ? '上传中…' : '上传并解析' }}</button>
        </div>
        <p v-else class="empty">创建后将在这里显示知识库信息。</p>
        <p v-if="document" class="success">文档 {{ document.originalFilename }} 已登记，状态：{{ document.status }}</p>
        <p v-if="message" class="message">{{ message }}</p>
      </section>
    </section>
  </main>
</template>
