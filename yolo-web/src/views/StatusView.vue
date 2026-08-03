<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getHealth } from '../api/detect'
import { RefreshRight } from '@element-plus/icons-vue'

interface ServiceStatus {
  name: string
  group: string
  port: number
  status: 'UP' | 'DOWN'
  lastChecked: string
}

const services = ref<ServiceStatus[]>([
  { name: '舰船检测', group: 'ship', port: 8001, status: 'DOWN', lastChecked: '-' },
  { name: '飞机检测', group: 'plane', port: 8002, status: 'DOWN', lastChecked: '-' },
  { name: '车辆检测', group: 'car', port: 8003, status: 'DOWN', lastChecked: '-' },
])

const gatewayStatus = ref<'UP' | 'DOWN'>('DOWN')
const apiStatus = ref<'UP' | 'DOWN'>('DOWN')
const checking = ref(false)

interface HistoryItem {
  time: string
  fileName: string
  totalCount: number
  processingTimeMs: number
}

const recentHistory = ref<HistoryItem[]>([])

function loadRecentHistory() {
  try {
    const raw = localStorage.getItem('yolo-detect-history')
    recentHistory.value = raw ? JSON.parse(raw).slice(0, 10) : []
  } catch {
    recentHistory.value = []
  }
}

async function checkHealth() {
  checking.value = true
  try {
    const resp = await getHealth()
    apiStatus.value = 'UP'
    gatewayStatus.value = resp.gateway === 'UP' ? 'UP' : 'DOWN'
    services.value.forEach(s => {
      s.status = 'UP'
      s.lastChecked = new Date().toLocaleTimeString()
    })
  } catch {
    apiStatus.value = 'DOWN'
    gatewayStatus.value = 'DOWN'
    services.value.forEach(s => {
      s.status = 'DOWN'
      s.lastChecked = new Date().toLocaleTimeString()
    })
  } finally {
    checking.value = false
  }
}

onMounted(() => {
  loadRecentHistory()
  checkHealth()
})
</script>

<template>
  <div class="status-page">
    <el-row :gutter="20" style="margin-bottom: 20px">
      <el-col :span="4">
        <el-card shadow="hover">
          <div class="status-card">
            <div class="status-indicator" :class="apiStatus === 'UP' ? 'up' : 'down'" />
            <div>
              <p class="status-title">Web API</p>
              <p class="status-desc">:8080</p>
              <el-tag :type="apiStatus === 'UP' ? 'success' : 'danger'" size="small">
                {{ apiStatus }}
              </el-tag>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="4">
        <el-card shadow="hover">
          <div class="status-card">
            <div class="status-indicator" :class="gatewayStatus === 'UP' ? 'up' : 'down'" />
            <div>
              <p class="status-title">网关服务</p>
              <p class="status-desc">:9000</p>
              <el-tag :type="gatewayStatus === 'UP' ? 'success' : 'danger'" size="small">
                {{ gatewayStatus }}
              </el-tag>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col v-for="svc in services" :key="svc.group" :span="4">
        <el-card shadow="hover">
          <div class="status-card">
            <div class="status-indicator" :class="svc.status === 'UP' ? 'up' : 'down'" />
            <div>
              <p class="status-title">{{ svc.name }}</p>
              <p class="status-desc">:{{ svc.port }}</p>
              <el-tag :type="svc.status === 'UP' ? 'success' : 'danger'" size="small">
                {{ svc.status }}
              </el-tag>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-bottom: 20px">
      <el-col :span="24">
        <el-button
          type="primary"
          :loading="checking"
          :icon="RefreshRight"
          @click="checkHealth"
        >
          {{ checking ? '检查中...' : '刷新状态' }}
        </el-button>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <el-col :span="24">
        <el-card shadow="hover">
          <template #header><span>最近检测记录</span></template>
          <el-table v-if="recentHistory.length > 0" :data="recentHistory" size="small" stripe>
            <el-table-column type="index" width="50" />
            <el-table-column prop="time" label="时间" width="180" />
            <el-table-column prop="fileName" label="文件名" min-width="200" />
            <el-table-column prop="totalCount" label="目标数" width="100" />
            <el-table-column prop="processingTimeMs" label="耗时(ms)" width="120" />
          </el-table>
          <div v-else class="empty-text">暂无检测记录</div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.status-page {
  max-width: 1400px;
  margin: 0 auto;
}

.status-card {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-indicator {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-indicator.up {
  background: #67c23a;
  box-shadow: 0 0 6px rgba(103, 194, 58, 0.6);
}

.status-indicator.down {
  background: #f56c6c;
  box-shadow: 0 0 6px rgba(245, 108, 108, 0.6);
}

.status-title {
  font-size: 14px;
  color: #303133;
  margin: 0 0 2px;
}

.status-desc {
  font-size: 12px;
  color: #909399;
  margin: 0 0 6px;
}

.empty-text {
  text-align: center;
  padding: 40px 0;
  color: #c0c4cc;
}
</style>
