<script setup lang="ts">
import { ref } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'

const emit = defineEmits<{
  (e: 'upload', file: File): void
}>()

const previewUrl = ref<string>('')
const isDragging = ref(false)

function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  if (target.files && target.files.length > 0) {
    loadFile(target.files[0])
  }
}

function loadFile(file: File) {
  previewUrl.value = URL.createObjectURL(file)
  emit('upload', file)
}

function onDragOver(e: DragEvent) {
  e.preventDefault()
  isDragging.value = true
}

function onDragLeave() {
  isDragging.value = false
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  isDragging.value = false
  if (e.dataTransfer?.files && e.dataTransfer.files.length > 0) {
    loadFile(e.dataTransfer.files[0])
  }
}

function clearImage() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
  }
  previewUrl.value = ''
}
</script>

<template>
  <div class="uploader">
    <div
      class="drop-zone"
      :class="{ dragging: isDragging }"
      @dragover="onDragOver"
      @dragleave="onDragLeave"
      @drop="onDrop"
    >
      <template v-if="!previewUrl">
        <el-icon :size="48" color="#c0c4cc"><UploadFilled /></el-icon>
        <p class="drop-text">将图片拖拽到此处，或点击选择文件</p>
        <p class="drop-hint">支持 JPG、PNG、TIFF 格式，单张不超过 500MB</p>
        <input
          type="file"
          accept="image/jpeg,image/png,image/tiff,.tif,.tiff"
          class="file-input"
          @change="handleFileChange"
        />
      </template>

      <div v-else class="preview-container">
        <img :src="previewUrl" alt="预览" class="preview-image" />
        <div class="preview-actions">
          <label class="reupload-btn">
            重新选择
            <input
              type="file"
              accept="image/jpeg,image/png,image/tiff,.tif,.tiff"
              class="file-input"
              @change="handleFileChange"
            />
          </label>
          <el-button type="danger" size="small" plain @click="clearImage">清除</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.uploader {
  width: 100%;
}

.drop-zone {
  border: 2px dashed #dcdfe6;
  border-radius: 8px;
  padding: 48px 24px;
  text-align: center;
  cursor: pointer;
  transition: all 0.3s;
  position: relative;
  background: #fafafa;
}

.drop-zone:hover,
.drop-zone.dragging {
  border-color: #409eff;
  background: #ecf5ff;
}

.file-input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.drop-text {
  margin: 12px 0 8px;
  color: #606266;
  font-size: 15px;
}

.drop-hint {
  color: #c0c4cc;
  font-size: 13px;
}

.preview-container {
  position: relative;
}

.preview-image {
  max-width: 100%;
  max-height: 360px;
  border-radius: 6px;
  object-fit: contain;
}

.preview-actions {
  margin-top: 12px;
  display: flex;
  justify-content: center;
  gap: 12px;
}

.reupload-btn {
  display: inline-block;
  position: relative;
  padding: 5px 15px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 12px;
  color: #606266;
  cursor: pointer;
  background: #fff;
}

.reupload-btn:hover {
  color: #409eff;
  border-color: #c6e2ff;
}
</style>
