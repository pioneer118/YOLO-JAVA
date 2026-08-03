<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { detectImage, type Detection, type DroneParams } from '../api/detect'
import ImageUploader from '../components/ImageUploader.vue'
import MapOverlay from '../components/MapOverlay.vue'
import { ElMessage } from 'element-plus'
import { Search, RefreshRight, Loading } from '@element-plus/icons-vue'
import { droneModelOptions, findDrone, CUSTOM_DRONE, DEFAULT_PITCH, type DroneCamera } from '../utils/droneDb'
import { calcGsdResult, type GsdResult } from '../utils/gsdCalc'

// ---- 基础参数 ----
const selectedFile = ref<File | null>(null)
const dataSource = ref('satellite')
const confThreshold = ref(0.25)
const loading = ref(false)

// ---- 目标选择 ----
const selectedTargets = ref<string[]>(['ship', 'plane', 'car'])

// ---- 无人机参数 ----
const droneModel = ref('')
const droneAltitude = ref<number | null>(null)
const cameraPitch = ref(DEFAULT_PITCH)
const droneLat = ref<number | null>(null)
const droneLng = ref<number | null>(null)
const customSensorWidth = ref<number | null>(null)
const customFocalLength = ref<number | null>(null)

const droneModels = droneModelOptions()

const activeCamera = computed<DroneCamera | null>(() => {
  if (!droneModel.value || droneModel.value === CUSTOM_DRONE) {
    if (customSensorWidth.value && customFocalLength.value) {
      return { model: CUSTOM_DRONE, sensorWidth: customSensorWidth.value, focalLength: customFocalLength.value, imageWidth: 4000 }
    }
    return null
  }
  return findDrone(droneModel.value)
})

const imageWidth = ref(0)
const imageHeight = ref(0)

const gsdResult = computed<GsdResult | null>(() => {
  const cam = activeCamera.value
  if (!cam || !droneAltitude.value || droneAltitude.value <= 0) return null
  if (!imageWidth.value || !imageHeight.value) return null
  return calcGsdResult(cam, droneAltitude.value, cameraPitch.value, imageWidth.value, imageHeight.value)
})

watch(dataSource, (val) => {
  if (val === 'satellite') {
    droneModel.value = ''
    droneAltitude.value = null
    cameraPitch.value = DEFAULT_PITCH
    droneLat.value = null
    droneLng.value = null
    customSensorWidth.value = null
    customFocalLength.value = null
  }
})

// ---- 结果 ----
const resultImage = ref('')
const detections = ref<Detection[]>([])
const totalCount = ref(0)
const processingTimeMs = ref(0)
const responseData = ref<any>(null)
const mapRef = ref<InstanceType<typeof MapOverlay> | null>(null)

// ---- 历史 ----
interface HistoryItem { time: string; fileName: string; totalCount: number; processingTimeMs: number }
const history = ref<HistoryItem[]>(loadHistory())

function loadHistory(): HistoryItem[] {
  try { return JSON.parse(localStorage.getItem('yolo-detect-history') || '[]') } catch { return [] }
}
function saveHistory() {
  try {
    // 不保存图片 base64（太大），只保留元数据，最多 20 条
    localStorage.setItem('yolo-detect-history', JSON.stringify(history.value.slice(0, 20)))
  } catch {
    // localStorage 满了就清空
    localStorage.removeItem('yolo-detect-history')
  }
}

function onFileUpload(file: File) {
  selectedFile.value = file
  resultImage.value = ''
  detections.value = []
  responseData.value = null
  const img = new Image()
  img.onload = () => { imageWidth.value = img.naturalWidth; imageHeight.value = img.naturalHeight; URL.revokeObjectURL(img.src) }
  img.src = URL.createObjectURL(file)
}

const canDetect = computed(() => selectedFile.value !== null && !loading.value && selectedTargets.value.length > 0)

async function startDetect() {
  if (!selectedFile.value) return
  loading.value = true
  resultImage.value = ''
  detections.value = []
  responseData.value = null

  let droneParams: DroneParams | undefined
  if (dataSource.value === 'drone' && gsdResult.value) {
    droneParams = { gsd: gsdResult.value.gsd }
    if (droneLat.value != null) droneParams.lat = droneLat.value
    if (droneLng.value != null) droneParams.lng = droneLng.value
  }

  try {
    const response = await detectImage(
      selectedFile.value, dataSource.value, confThreshold.value,
      droneParams, selectedTargets.value
    )
    totalCount.value = response.totalCount
    processingTimeMs.value = response.processingTimeMs
    resultImage.value = response.annotatedImage
    detections.value = response.detections
    responseData.value = response

    history.value.unshift({
      time: new Date().toLocaleString(),
      fileName: selectedFile.value.name,
      totalCount: response.totalCount,
      processingTimeMs: response.processingTimeMs,
    })
    saveHistory()
    ElMessage.success(`检测完成：发现 ${response.totalCount} 个目标`)
  } catch (err: any) {
    ElMessage.error('检测失败：' + (err.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

function reset() {
  selectedFile.value = null
  resultImage.value = ''
  detections.value = []
  totalCount.value = 0
  processingTimeMs.value = 0
  responseData.value = null
}

// 右侧列表 ↔ 地图联动
const hoveredIdx = ref(-1)
function onListHover(idx: number) {
  hoveredIdx.value = idx
  mapRef.value?.highlightMarker(idx)
}
function onListLeave(idx: number) {
  mapRef.value?.unhighlightMarker(idx)
}
function onListClick(idx: number) {
  mapRef.value?.flyToMarker(idx)
}
function onMarkerHover(idx: number) { hoveredIdx.value = idx }
function onMarkerClick(idx: number) { /* 地图点击由组件内部处理popup */ }

// 下载检测结果为 CSV
function downloadCSV() {
  const hasGeo = detections.value.some(d => d.lat != null)
  let csv = hasGeo
    ? 'classId,className,confidence,lat,lng,x1,y1,x2,y2,x3,y3,x4,y4\n'
    : 'classId,className,confidence,x1,y1,x2,y2,x3,y3,x4,y4\n'
  for (const d of detections.value) {
    if (hasGeo) {
      csv += `${d.classId},"${d.className}",${d.confidence},${d.lat ?? ''},${d.lng ?? ''},${d.bbox.x1},${d.bbox.y1},${d.bbox.x2},${d.bbox.y2},${d.bbox.x3},${d.bbox.y3},${d.bbox.x4},${d.bbox.y4}\n`
    } else {
      csv += `${d.classId},"${d.className}",${d.confidence},${d.bbox.x1},${d.bbox.y1},${d.bbox.x2},${d.bbox.y2},${d.bbox.x3},${d.bbox.y3},${d.bbox.x4},${d.bbox.y4}\n`
    }
  }
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = `detections_${new Date().toISOString().slice(0,19).replace(/:/g,'-')}.csv`
  a.click(); URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="detect-app">
    <!-- ===== 左栏：参数 ===== -->
    <div class="left-panel">
      <!-- 数据来源 -->
      <div class="panel-section">
        <div class="section-title">📡 数据来源</div>
        <div class="source-tabs">
          <div class="source-tab" :class="{ active: dataSource === 'satellite' }" @click="dataSource = 'satellite'">🛰️ 卫星遥感</div>
          <div class="source-tab" :class="{ active: dataSource === 'drone' }" @click="dataSource = 'drone'">🛸 无人机</div>
        </div>
      </div>

      <!-- 检测目标 -->
      <div class="panel-section">
        <div class="section-title">🎯 检测目标</div>
        <div class="target-checks">
          <label class="target-check" :class="{ checked: selectedTargets.includes('ship') }">
            <input type="checkbox" v-model="selectedTargets" value="ship" :disabled="loading" />
            <span class="check-icon"></span>
            <span class="target-icon">🚢</span>
            <span class="target-label">舰船</span>
            <span class="target-hint">ship</span>
          </label>
          <label class="target-check" :class="{ checked: selectedTargets.includes('plane') }">
            <input type="checkbox" v-model="selectedTargets" value="plane" :disabled="loading" />
            <span class="check-icon"></span>
            <span class="target-icon">✈️</span>
            <span class="target-label">飞机</span>
            <span class="target-hint">plane</span>
          </label>
          <label class="target-check" :class="{ checked: selectedTargets.includes('car') }">
            <input type="checkbox" v-model="selectedTargets" value="car" :disabled="loading" />
            <span class="check-icon"></span>
            <span class="target-icon">🚗</span>
            <span class="target-label">车辆</span>
            <span class="target-hint">car</span>
          </label>
        </div>
      </div>

      <!-- 无人机参数 -->
      <div v-if="dataSource === 'drone'" class="panel-section">
        <div class="section-title">🛸 无人机参数</div>
        <div class="form-group">
          <span class="form-label">无人机型号</span>
          <select v-model="droneModel" :disabled="loading" class="input-dark">
            <option value="" disabled>选择无人机</option>
            <option v-for="m in droneModels" :key="m" :value="m">{{ m }}</option>
          </select>
        </div>
        <template v-if="droneModel === CUSTOM_DRONE">
          <div class="form-group">
            <span class="form-label">传感器宽度 (mm)</span>
            <input v-model.number="customSensorWidth" type="number" class="input-dark" :disabled="loading" />
          </div>
          <div class="form-group">
            <span class="form-label">焦距 (mm)</span>
            <input v-model.number="customFocalLength" type="number" class="input-dark" :disabled="loading" />
          </div>
        </template>
        <div class="form-group">
          <span class="form-label">飞行高度 (m，海拔)</span>
          <input v-model.number="droneAltitude" type="number" class="input-dark" placeholder="输入飞行高度" :disabled="loading" />
        </div>
        <div class="form-group">
          <span class="form-label">相机俯仰角 (°)</span>
          <input v-model.number="cameraPitch" type="number" class="input-dark" :disabled="loading" />
          <span class="form-hint">0°=水平, 90°=垂直向下正射</span>
        </div>
        <div class="form-group">
          <span class="form-label">纬度（可选）</span>
          <input v-model.number="droneLat" type="number" class="input-dark" placeholder="例如 30.123456" :disabled="loading" />
        </div>
        <div class="form-group">
          <span class="form-label">经度（可选）</span>
          <input v-model.number="droneLng" type="number" class="input-dark" placeholder="例如 120.123456" :disabled="loading" />
        </div>
        <div v-if="gsdResult" class="calc-box">
          <div class="calc-row"><span>GSD</span><strong>{{ (gsdResult.gsd * 100).toFixed(1) }} cm/px</strong></div>
          <div class="calc-row"><span>覆盖范围</span><strong>{{ gsdResult.groundWidth.toFixed(0) }}m × {{ gsdResult.groundHeight.toFixed(0) }}m</strong></div>
          <div class="calc-row"><span>裁切建议</span><strong :class="gsdResult.warning ? 'text-warn' : 'text-good'">{{ gsdResult.recommendation }}</strong></div>
          <div v-if="gsdResult.warning" class="warning-text">{{ gsdResult.warning }}</div>
        </div>
      </div>

      <!-- 置信度 -->
      <div class="panel-section">
        <div class="section-title">📊 置信度阈值</div>
        <input type="range" v-model.number="confThreshold" :min="0.1" :max="1.0" :step="0.05" :disabled="loading" class="range-slider" />
        <div class="range-labels"><span>0.10</span><span class="range-val">{{ confThreshold.toFixed(2) }}</span><span>1.00</span></div>
      </div>

      <!-- 上传 -->
      <div class="panel-section">
        <div class="section-title">📷 上传图片</div>
        <ImageUploader @upload="onFileUpload" />
      </div>

      <!-- 按钮 -->
      <div class="panel-section">
        <button class="btn-detect" :disabled="!canDetect" @click="startDetect">
          {{ loading ? '⏳ 检测中...' : '🔍 开始检测' }}
        </button>
        <button v-if="resultImage" class="btn-reset" @click="reset">🔄 重新开始</button>
      </div>
    </div>

    <!-- ===== 中栏：地图 ===== -->
    <div class="center-panel">
      <MapOverlay
        ref="mapRef"
        :response-data="responseData || {}"
        :detections="detections"
        :annotated-image="resultImage"
        @marker-hover="onMarkerHover"
        @marker-click="onMarkerClick"
      />
    </div>

    <!-- ===== 右栏：结果 ===== -->
    <div class="right-panel">
      <!-- 空状态 -->
      <div v-if="!resultImage" class="empty-result">
        <div class="empty-icon">📋</div>
        <p>暂无检测结果</p>
        <p class="empty-hint">上传图片并点击「开始检测」<br>结果将显示在此处</p>
        <p class="empty-tip">💡 检测结果会同步显示在地图上</p>
      </div>

      <!-- 结果状态 -->
      <template v-else>
        <div class="result-header">
          <span class="tag tag-green">🎯 {{ totalCount }} 个目标</span>
          <span class="tag tag-blue">⏱ {{ processingTimeMs }}ms</span>
          <span v-if="responseData?.geo" class="tag tag-purple">🗺️ 已定位</span>
          <button class="btn-download" @click="downloadCSV" title="下载检测结果 CSV">📥 下载</button>
        </div>
        <div class="result-list">
          <div
            v-for="(d, i) in detections"
            :key="i"
            class="result-item"
            :class="{ hovered: hoveredIdx === i }"
            @mouseenter="onListHover(i)"
            @mouseleave="onListLeave(i)"
            @click="onListClick(i)"
          >
            <div class="item-idx">{{ i + 1 }}</div>
            <div class="item-info">
              <div class="item-cls">{{ d.className }}</div>
              <div v-if="d.lat != null" class="item-coord">{{ d.lat.toFixed(6) }}, {{ d.lng!.toFixed(6) }}</div>
              <div class="item-conf">
                <div class="conf-bar">
                  <div class="conf-fill" :class="d.confidence > 0.7 ? 'bg-green' : d.confidence > 0.4 ? 'bg-orange' : 'bg-red'" :style="{ width: Math.round(d.confidence * 100) + '%' }"></div>
                </div>
                <span>{{ Math.round(d.confidence * 100) }}%</span>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
/* ===== 布局 ===== */
.detect-app {
  display: flex;
  height: 100%;
  background: #1a1a2e;
  color: #e0e0e0;
}
.left-panel   { width: 340px; flex-shrink: 0; background: #16213e; overflow-y: auto; border-right: 1px solid #0f3460; }
.center-panel { flex: 1; min-width: 0; background: #0a0a1a; position: relative; overflow: hidden; }
.right-panel  { width: 300px; flex-shrink: 0; background: #16213e; overflow-y: auto; border-left: 1px solid #0f3460; }

.left-panel::-webkit-scrollbar, .right-panel::-webkit-scrollbar { width: 5px; }
.left-panel::-webkit-scrollbar-thumb, .right-panel::-webkit-scrollbar-thumb { background: #0f3460; border-radius: 3px; }

/* ===== 面板区块 ===== */
.panel-section { padding: 14px 18px; border-bottom: 1px solid #0f3460; }
.section-title { font-size: 12px; font-weight: 600; color: #a0a0b0; margin-bottom: 10px; text-transform: uppercase; letter-spacing: 0.5px; }

/* ===== 数据来源 ===== */
.source-tabs { display: flex; background: #0f3460; border-radius: 6px; padding: 3px; }
.source-tab { flex: 1; text-align: center; padding: 7px 10px; border-radius: 5px; font-size: 13px; cursor: pointer; color: #a0a0b0; transition: all .2s; user-select: none; }
.source-tab.active { background: #409eff; color: #fff; }

/* ===== 目标勾选 ===== */
.target-checks { display: flex; flex-direction: column; gap: 8px; }
.target-check { display: flex; align-items: center; gap: 10px; padding: 10px 14px; background: #0f3460; border-radius: 6px; cursor: pointer; transition: all .2s; }
.target-check:hover { background: rgba(64,158,255,0.12); }
.target-check input { display: none; }
.target-check .check-icon { width: 16px; height: 16px; border-radius: 3px; border: 2px solid #334; flex-shrink: 0; display: flex; align-items: center; justify-content: center; }
.target-check.checked .check-icon { background: #409eff; border-color: #409eff; }
.target-check.checked .check-icon::after { content: '✓'; color: #fff; font-size: 10px; font-weight: 700; }
.target-icon { font-size: 18px; }
.target-label { font-size: 13px; color: #c0c0d0; flex: 1; }
.target-hint { font-size: 10px; color: #707080; }

/* ===== 表单 ===== */
.form-group { margin-bottom: 12px; }
.form-label { font-size: 11px; color: #808090; display: block; margin-bottom: 4px; }
.form-hint { font-size: 10px; color: #506080; margin-top: 2px; display: block; }
.input-dark { width: 100%; padding: 8px 10px; background: #0f3460; border: 1px solid #1a3a6e; border-radius: 5px; color: #e0e0e0; font-size: 13px; outline: none; transition: border .2s; box-sizing: border-box; }
.input-dark:focus { border-color: #409eff; }
select.input-dark { appearance: none; cursor: pointer; }

/* ===== GSD 计算 ===== */
.calc-box { background: #0f3460; border-radius: 6px; padding: 10px 12px; margin-top: 10px; }
.calc-row { display: flex; justify-content: space-between; padding: 2px 0; font-size: 12px; }
.calc-row span { color: #707080; }
.calc-row strong { color: #c0c0d0; }
.text-good { color: #67c23a !important; }
.text-warn { color: #e6a23c !important; }
.warning-text { font-size: 11px; color: #e6a23c; margin-top: 6px; }

/* ===== 滑块 ===== */
.range-slider { width: 100%; appearance: none; height: 5px; background: #0f3460; border-radius: 3px; outline: none; }
.range-slider::-webkit-slider-thumb { appearance: none; width: 16px; height: 16px; background: #409eff; border-radius: 50%; cursor: pointer; }
.range-labels { display: flex; justify-content: space-between; font-size: 10px; color: #506080; margin-top: 4px; }
.range-val { color: #409eff; font-weight: 600; }

/* ===== 按钮 ===== */
.btn-detect { width: 100%; padding: 11px; background: #409eff; color: #fff; border: none; border-radius: 6px; font-size: 14px; font-weight: 600; cursor: pointer; transition: all .2s; }
.btn-detect:hover:not(:disabled) { background: #337ecc; }
.btn-detect:disabled { background: #0d2137; color: #6b7d8e; cursor: not-allowed; border: 1px solid #1a3a6e; }
.btn-reset { width: 100%; padding: 9px; background: transparent; color: #a0a0b0; border: 1px solid #1a3a6e; border-radius: 6px; font-size: 13px; cursor: pointer; margin-top: 8px; }
.btn-reset:hover { color: #fff; border-color: #409eff; }

/* ===== 空结果 ===== */
.empty-result { text-align: center; padding: 60px 20px; color: #506080; }
.empty-icon { font-size: 44px; margin-bottom: 12px; }
.empty-result p { font-size: 13px; margin: 0; }
.empty-hint { margin-top: 8px !important; font-size: 12px !important; color: #405070; }
.empty-tip { margin-top: 14px !important; font-size: 11px !important; color: #405070; }

/* ===== 结果头部 ===== */
.result-header { padding: 12px 16px; display: flex; gap: 8px; flex-wrap: wrap; border-bottom: 1px solid #0f3460; }
.tag { padding: 3px 10px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.tag-green { background: rgba(103,194,58,0.15); color: #67c23a; }
.tag-blue  { background: rgba(64,158,255,0.15); color: #409eff; }
.tag-purple{ background: rgba(160,128,255,0.15); color: #a080ff; }

/* ===== 结果列表 ===== */
.result-list { overflow-y: auto; }
.result-item { display: flex; align-items: center; gap: 10px; padding: 10px 16px; border-bottom: 1px solid #0f3460; cursor: pointer; transition: all .15s; }
.result-item:hover, .result-item.hovered { background: rgba(64,158,255,0.08); }
.item-idx { width: 22px; height: 22px; border-radius: 50%; background: #409eff; color: #fff; font-size: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.result-item.hovered .item-idx { background: #e6a23c; width: 26px; height: 26px; font-size: 12px; }
.item-info { flex: 1; min-width: 0; }
.item-cls { font-size: 13px; color: #e0e0e0; }
.item-coord { font-size: 10px; color: #607080; margin: 2px 0; font-family: monospace; }
.btn-download { padding: 3px 12px; background: rgba(64,158,255,0.15); color: #409eff; border: 1px solid rgba(64,158,255,0.3); border-radius: 4px; font-size: 12px; cursor: pointer; margin-left: auto; }
.btn-download:hover { background: rgba(64,158,255,0.25); }
.item-conf { display: flex; align-items: center; gap: 8px; font-size: 11px; color: #707080; }
.conf-bar { width: 50px; height: 3px; background: #0f3460; border-radius: 2px; }
.conf-fill { height: 100%; border-radius: 2px; }
.bg-green  { background: #67c23a; }
.bg-orange { background: #e6a23c; }
.bg-red    { background: #f56c6c; }
</style>
