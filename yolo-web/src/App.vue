<script setup lang="ts">
import { useRoute } from 'vue-router'
import { Search, Monitor } from '@element-plus/icons-vue'
const route = useRoute()
</script>

<template>
  <div id="app-container">
    <el-container>
      <el-header class="app-header">
        <div class="header-left">
          <h1>YOLO 分布式目标检测系统</h1>
        </div>
        <div class="header-right">
          <el-menu
            mode="horizontal"
            :ellipsis="false"
            router
            :default-active="$route.path"
          >
            <el-menu-item index="/">
              <el-icon><Search /></el-icon>
              检测
            </el-menu-item>
            <el-menu-item index="/status">
              <el-icon><Monitor /></el-icon>
              状态
            </el-menu-item>
          </el-menu>
        </div>
      </el-header>

      <el-main>
        <router-view />
      </el-main>

      <el-footer v-if="route.path !== '/'" class="app-footer">
        <span>YOLO Distributed Detection System v1.0.0</span>
      </el-footer>
    </el-container>
  </div>
</template>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  padding: 0 24px;
  height: 60px;
}
.app-header h1 {
  font-size: 18px;
  color: #303133;
  margin: 0;
}
.header-right {
  display: flex;
  align-items: center;
}
.header-right .el-menu {
  border-bottom: none;
}
.app-footer {
  text-align: center;
  padding: 16px 0;
  color: #909399;
  font-size: 13px;
  border-top: 1px solid #e4e7ed;
}
</style>
