/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': '/src',
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    // 组件测试需要 DOM：jsdom 提供 localStorage / window / StorageEvent，
    // crossTabSync 那类「跨标签页」逻辑才测得了。
    environment: 'jsdom',
    include: ['src/**/__tests__/*.spec.ts'],
    // 每个用例都从干净的全局状态开始：这个项目大量依赖 window 事件与 localStorage，
    // 漏掉这两项就会出现「单独跑过、一起跑挂」。
    restoreMocks: true,
    clearMocks: true,
  },
})
