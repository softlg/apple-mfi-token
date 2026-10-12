import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import './styles/base.css'

/**
 * 全量引入 Element Plus：控制台一共十个页面，按需自动引入要再挂两个插件与一份类型声明，
 * 体积收益也抵不过打包配置的复杂度；后端是单进程托管静态资源，缓存一次即可。
 */
createApp(App).use(createPinia()).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
