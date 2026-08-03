import { createRouter, createWebHistory } from 'vue-router'
import DetectView from '../views/DetectView.vue'
import StatusView from '../views/StatusView.vue'

const routes = [
  { path: '/', name: 'Detect', component: DetectView },
  { path: '/status', name: 'Status', component: StatusView },
]

export default createRouter({
  history: createWebHistory(),
  routes,
})
