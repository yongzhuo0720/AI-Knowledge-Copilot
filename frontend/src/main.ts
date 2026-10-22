import { createApp } from 'vue'

import App from './App.vue'
import router from './router'
import { installCrossTabSync } from './services/crossTabSync'
// highlight.js 主题需在 styles.css 之前引入，让项目自定义样式拥有更高优先级
import 'highlight.js/styles/github.css'
import './styles.css'

// 先装同步桥再接应用：挂载过程中发生的跨标签页事件才不会漏掉
installCrossTabSync()

createApp(App).use(router).mount('#app')
