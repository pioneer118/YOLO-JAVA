<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import type { LatLngTuple, LatLngBoundsExpression } from 'leaflet'
import type { Detection } from '../api/detect'
import { extractGeoFromResponse } from '../utils/geoTransform'

const props = defineProps<{
  /** 后端返回的完整响应数据（含 geo / centerLat 等字段） */
  responseData: any
  /** 检测结果列表 */
  detections: Detection[]
  /** 标注后的图片 base64 URL */
  annotatedImage: string
}>()

const emit = defineEmits<{
  (e: 'markerHover', index: number): void
  (e: 'markerClick', index: number): void
}>()

// ---- 状态 ----
const mapContainer = ref<HTMLDivElement>()
let map: L.Map | null = null
let basemap: L.TileLayer | null = null
let imageOverlay: L.ImageOverlay | null = null
let markersLayer: L.LayerGroup | null = null
let droneMarker: L.Marker | null = null

const showOverlay = ref(true)
const showMarkers = ref(true)
const mapReady = ref(false)

// ---- 初始化地图 ----
function initMap() {
  if (!mapContainer.value || map) return

  map = L.map(mapContainer.value, {
    center: [32.0, 118.7],
    zoom: 5,
    zoomControl: false,
    attributionControl: false,
  })

  L.control.zoom({ position: 'bottomleft' }).addTo(map)

  // 卫星底图
  basemap = L.tileLayer(
    'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    { maxZoom: 19 }
  ).addTo(map)

  mapReady.value = true
}

// ---- 更新叠加层 ----
function updateOverlays() {
  if (!map) return

  const { bounds, center, zoom, hasGeo } = extractGeoFromResponse(props.responseData)

  // 清除旧图层
  if (imageOverlay) { map.removeLayer(imageOverlay); imageOverlay = null }
  if (markersLayer) { map.removeLayer(markersLayer); markersLayer = null }
  if (droneMarker) { map.removeLayer(droneMarker); droneMarker = null }

  // TIFF 影像叠加 — ImageOverlay
  if (hasGeo && bounds && props.annotatedImage) {
    imageOverlay = L.imageOverlay(props.annotatedImage, bounds as LatLngBoundsExpression, {
      opacity: 0.7,
    })
    if (showOverlay.value) imageOverlay.addTo(map)
    map.fitBounds(bounds as LatLngBoundsExpression, { padding: [20, 20] })
  }
  // 无人机标记 — 单点 Marker
  else if (hasGeo && props.annotatedImage) {
    const icon = L.divIcon({
      className: '',
      html: '<div style="background:#409eff;color:#fff;border-radius:50%;width:28px;height:28px;display:flex;align-items:center;justify-content:center;font-size:14px;border:2px solid #fff;box-shadow:0 2px 8px rgba(64,158,255,0.5);">📍</div>',
      iconSize: [28, 28],
      iconAnchor: [14, 28],
    })
    droneMarker = L.marker(center, { icon }).addTo(map)
    droneMarker.bindPopup(`
      <div style="min-width:200px;color:#e0e0e0;">
        <b style="color:#fff;">🛸 无人机检测结果</b><br>
        检测到 ${props.detections.length} 个目标<br>
        <img src="${props.annotatedImage}" style="width:100%;border-radius:4px;margin-top:6px;" />
      </div>
    `, { maxWidth: 320 })
    map.setView(center, zoom)
  }
  // 无地理信息：保持默认视图
  else if (!hasGeo) {
    map.setView(center, zoom)
  }

  // 检测目标标记
  if (props.detections.length > 0) {
    markersLayer = L.layerGroup()
    props.detections.forEach((d, i) => {
      // 用 bbox 中心作为标记位置
      const cx = (d.bbox.x1 + d.bbox.x3) / 2
      const cy = (d.bbox.y1 + d.bbox.y3) / 2
      const lat = d._lat ?? (center[0] + (cy - 2500) * 0.000003)
      const lng = d._lng ?? (center[1] + (cx - 2500) * 0.000003)

      const icon = L.divIcon({
        className: '',
        html: `<div class="marker-dot" data-idx="${i}">${i + 1}</div>`,
        iconSize: [22, 22],
        iconAnchor: [11, 11],
      })

      const marker = L.marker([lat, lng], { icon })
      marker.bindPopup(
        `<div style="color:#e0e0e0;"><b style="color:#fff;">${d.className}</b><br>置信度: ${Math.round(d.confidence * 100)}%</div>`
      )
      marker.on('mouseover', () => emit('markerHover', i))
      marker.on('click', () => emit('markerClick', i))
      ;(marker as any)._detIndex = i
      markersLayer!.addLayer(marker)
    })
    if (showMarkers.value) markersLayer.addTo(map)
  }
}

// ---- 图层开关 ----
function toggleOverlay() {
  showOverlay.value = !showOverlay.value
  if (!map) return
  if (showOverlay.value && imageOverlay) imageOverlay.addTo(map)
  else if (imageOverlay) map.removeLayer(imageOverlay)
}

function toggleMarkers() {
  showMarkers.value = !showMarkers.value
  if (!map) return
  if (showMarkers.value && markersLayer) markersLayer.addTo(map)
  else if (markersLayer) map.removeLayer(markersLayer)
}

// 外部调用：高亮某个标记
function highlightMarker(index: number) {
  if (!markersLayer) return
  markersLayer.eachLayer((layer: any) => {
    if (layer._detIndex === index) {
      layer.setIcon(L.divIcon({
        className: '',
        html: `<div class="marker-dot highlighted">${index + 1}</div>`,
        iconSize: [28, 28],
        iconAnchor: [14, 14],
      }))
    }
  })
}

function unhighlightMarker(index: number) {
  if (!markersLayer) return
  markersLayer.eachLayer((layer: any) => {
    if (layer._detIndex === index) {
      layer.setIcon(L.divIcon({
        className: '',
        html: `<div class="marker-dot">${index + 1}</div>`,
        iconSize: [22, 22],
        iconAnchor: [11, 11],
      }))
    }
  })
}

function flyToMarker(index: number) {
  if (!markersLayer || !map) return
  markersLayer.eachLayer((layer: any) => {
    if (layer._detIndex === index) {
      map!.flyTo(layer.getLatLng(), 18)
      layer.openPopup()
    }
  })
}

defineExpose({ highlightMarker, unhighlightMarker, flyToMarker })

// ---- 生命周期 ----
onMounted(() => {
  nextTick(() => {
    initMap()
    updateOverlays()
    // 确保 Leaflet 在 flex 容器中正确计算尺寸
    setTimeout(() => map?.invalidateSize(), 100)
  })
})

onUnmounted(() => {
  if (map) { map.remove(); map = null }
})

watch(() => [props.responseData, props.detections, props.annotatedImage], () => {
  nextTick(() => {
    if (!map) initMap()
    updateOverlays()
    // 数据更新后重算地图尺寸
    setTimeout(() => map?.invalidateSize(), 50)
  })
})
</script>

<template>
  <div class="map-wrapper">
    <div ref="mapContainer" class="map-container" />

    <!-- 图层控制 -->
    <div v-if="mapReady" class="layer-ctrl">
      <label><input type="checkbox" checked disabled> 🛰️ 卫星底图</label>
      <label v-if="imageOverlay || droneMarker">
        <input type="checkbox" :checked="showOverlay" @change="toggleOverlay"> 🖼️ 影像叠加
      </label>
      <label v-if="detections.length > 0">
        <input type="checkbox" :checked="showMarkers" @change="toggleMarkers"> 🎯 检测标记 ({{ detections.length }})
      </label>
    </div>

    <!-- 底部信息栏 -->
    <div v-if="responseData?.geo" class="map-info">
      覆盖: {{ ((responseData.geo.north - responseData.geo.south) * 111000).toFixed(0) }}m ×
      {{ ((responseData.geo.east - responseData.geo.west) * 111000 * Math.cos((responseData.geo.centerLat * Math.PI) / 180)).toFixed(0) }}m
      | 中心: {{ responseData.geo.centerLat.toFixed(4) }}°N, {{ responseData.geo.centerLng.toFixed(4) }}°E
    </div>
    <div v-else-if="responseData?.centerLat" class="map-info">
      坐标: {{ responseData.centerLat.toFixed(6) }}°N, {{ responseData.centerLng.toFixed(6) }}°E
    </div>
  </div>
</template>

<style scoped>
.map-wrapper {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
}

.map-container {
  flex: 1;
  min-height: 0;
  z-index: 1;
}

.layer-ctrl {
  position: absolute;
  top: 10px;
  right: 10px;
  background: rgba(22, 33, 62, 0.95);
  border-radius: 6px;
  padding: 8px 12px;
  z-index: 1000;
  font-size: 12px;
  border: 1px solid #0f3460;
}

.layer-ctrl label {
  display: block;
  margin: 4px 0;
  cursor: pointer;
  color: #c0c0d0;
}

.map-info {
  padding: 6px 14px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.5);
  background: rgba(0, 0, 0, 0.4);
  flex-shrink: 0;
}
</style>

<style>
/* 全局样式：地图上的标记点 */
.marker-dot {
  background: #67c23a;
  color: #fff;
  border-radius: 50%;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 700;
  border: 2px solid #fff;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.4);
}
.marker-dot.highlighted {
  background: #e6a23c;
  width: 28px;
  height: 28px;
  font-size: 12px;
  border-color: #fff;
}
.leaflet-popup-content-wrapper {
  background: #16213e;
  color: #e0e0e0;
  border-radius: 6px;
}
.leaflet-popup-tip {
  background: #16213e;
}
</style>
