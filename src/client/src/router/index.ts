import { createRouter, createWebHistory } from 'vue-router'
import { pinia } from '@/stores'
import { setupAuthGuard } from './guards'
import { routes } from './routes'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

setupAuthGuard(router, pinia)

export default router
