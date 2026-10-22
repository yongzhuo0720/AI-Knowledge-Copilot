<script setup lang="ts">
import type { KnowledgeRetrievalChunk } from '@/services/api'

defineProps<{ sources: KnowledgeRetrievalChunk[] }>()
</script>

<template>
  <!-- 引用来源的轻量展示：编号 + 对象 key 的胶囊列表。Agent 结果面板与检索调试的回答面板共用。 -->
  <div class="source-chips"><span class="source-label">引用来源</span><span v-for="(source, index) in sources" :key="`${source.documentObjectKey}-${index}`" class="source-chip">[{{ index + 1 }}] {{ source.documentObjectKey }}</span></div>
</template>
