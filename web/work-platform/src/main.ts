import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import router from './router'
import './styles/index.css'

const savedTheme = localStorage.getItem('wp-theme') || 'dark'
document.documentElement.classList.toggle('dark', savedTheme === 'dark')

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
