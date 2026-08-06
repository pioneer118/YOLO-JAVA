<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import * as Cesium from 'cesium'
import 'cesium/Build/Cesium/Widgets/widgets.css'
import type { Detection } from '../api/detect'
import { extractGeoFromResponse } from '../utils/geoTransform'
import { CESIUM_ION_TOKEN, TILE_PROXY_URL } from '../config'

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
let viewer: Cesium.Viewer | null = null
let imageEntity: Cesium.Entity | null = null
let markerEntities: Cesium.Entity[] = []
let droneEntity: Cesium.Entity | null = null
let currentHighlight: number = -1

const showOverlay = ref(true)
const showMarkers = ref(true)
const mapReady = ref(false)

// 配置 Cesium Ion token
Cesium.Ion.defaultAccessToken = CESIUM_ION_TOKEN

// ---- 初始化 3D 地球 ----
function initMap() {
  if (!mapContainer.value || viewer) return

  try {
    viewer = new Cesium.Viewer(mapContainer.value, {
      // Cesium 1.104+ 已移除 terrain 选项，用 terrainProvider
      terrainProvider: new Cesium.EllipsoidTerrainProvider(),
      baseLayer: false, // 手动控制底图
      infoBox: false,
      selectionIndicator: false,
      timeline: false,
      animation: false,
      baseLayerPicker: false,
      geocoder: false,
      homeButton: false,
      sceneModePicker: false,
      navigationHelpButton: false,
      fullscreenButton: false,
    })
    console.log('[Cesium] Viewer 创建成功')
  } catch (e: any) {
    console.error('[Cesium] Viewer 创建失败:', e.message || e)
    viewer = null
    return
  }

  loadBaseLayer()
  mapReady.value = true
}

// ---- 底图多级回退 ----
async function loadBaseLayer() {
  if (!viewer) return

  const tryProviders: Array<() => Cesium.ImageryProvider> = [
    // 1. 后端瓦片代理（Esri World Imagery，已验证可用）
    //    浏览器无法直接访问第三方瓦片，由后端 JVM 转发
    () => new Cesium.UrlTemplateImageryProvider({
      url: TILE_PROXY_URL,
      maximumLevel: 18,
    }),
    // 2. Cesium Ion 全球卫星影像（需 assets.cesium.com 可达）
    () => new Cesium.IonImageryProvider({ assetId: 2 }),
    // 3. ArcGIS World Imagery
    () => new Cesium.ArcGisMapServerImageryProvider({
      url: 'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer',
    }),
    // 4. OpenStreetMap
    () => new Cesium.OpenStreetMapImageryProvider({
      url: 'https://tile.openstreetmap.org/',
    }),
  ]

  for (let i = 0; i < tryProviders.length; i++) {
    try {
      const provider = tryProviders[i]()
      // 尝试加载一张瓦片验证可用性
      await provider.readyPromise
      viewer.imageryLayers.addImageryProvider(provider)
      console.log('[Cesium] 底图加载成功:', provider.constructor.name)
      return
    } catch (e) {
      console.warn(`[Cesium] 底图源[${i}]失败:`, e)
    }
  }
  console.warn('[Cesium] 所有底图源均失败')
}

// ---- 更新叠加层 ----
function updateOverlays() {
  if (!viewer) return

  clearOverlays()

  const { bounds, center, hasGeo } = extractGeoFromResponse(props.responseData)

  // TIFF 影像叠加 — 3D 地球上的矩形影像
  if (hasGeo && bounds && props.annotatedImage) {
    imageEntity = viewer.entities.add({
      rectangle: {
        coordinates: Cesium.Rectangle.fromDegrees(
          bounds[0], bounds[1], bounds[2], bounds[3]
        ),
        material: new Cesium.ImageMaterialProperty({
          image: props.annotatedImage,
          transparent: true,
        }),
      },
    })
    // 飞行到影像位置
    viewer.camera.flyTo({
      destination: Cesium.Rectangle.fromDegrees(
        bounds[0], bounds[1], bounds[2], bounds[3]
      ),
      duration: 1.0,
    })
  } else if (hasGeo) {
    // 无人机标记位置
    viewer.camera.flyTo({
      destination: Cesium.Cartesian3.fromDegrees(center[0], center[1], 5000),
      duration: 1.0,
    })
  }

  // 检测目标标记
  if (props.detections.length > 0 && bounds) {
    const imgW = props.responseData?.geo?.imageWidth || 1000
    const imgH = props.responseData?.geo?.imageHeight || 1000

    props.detections.forEach((d, i) => {
      // 用 bbox 中心像素转经纬度
      const cx = (d.bbox.x1 + d.bbox.x3) / 2
      const cy = (d.bbox.y1 + d.bbox.y3) / 2
      const [lng, lat] = pixelToLngLatRaw(cx, cy, imgW, imgH, bounds)

      const entity = viewer!.entities.add({
        position: Cesium.Cartesian3.fromDegrees(lng, lat),
        point: {
          pixelSize: 12,
          color: Cesium.Color.GREEN,
          outlineColor: Cesium.Color.WHITE,
          outlineWidth: 2,
          heightReference: Cesium.HeightReference.NONE,
        },
        label: {
          text: String(i + 1),
          font: '11px sans-serif',
          fillColor: Cesium.Color.WHITE,
          pixelOffset: new Cesium.Cartesian2(0, -14),
          disableDepthTestDistance: Number.POSITIVE_INFINITY,
        },
      })
      entity.description = `
        <div style="color:#222">
          <b>${d.className}</b><br>
          置信度: ${Math.round(d.confidence * 100)}%<br>
          坐标: ${lat.toFixed(6)}°N, ${lng.toFixed(6)}°E
        </div>`
      // 点击标记
      entity!.point!.color = new Cesium.CallbackProperty(() => {
        return currentHighlight === i ? Cesium.Color.ORANGE : Cesium.Color.GREEN
      }, false)
      entity!.point!.pixelSize = new Cesium.CallbackProperty(() => {
        return currentHighlight === i ? 18 : 12
      }, false)
      ;(entity as any)._detIndex = i
      markerEntities.push(entity)
    })

    viewer.screenSpaceEventHandler = new Cesium.ScreenSpaceEventHandler(
      viewer.scene.canvas
    )
    viewer.screenSpaceEventHandler.setInputAction((movement: any) => {
      const picked = viewer!.scene.pick(movement.position)
      if (Cesium.defined(picked) && picked.id && picked.id._detIndex !== undefined) {
        const idx = picked.id._detIndex
        emit('markerClick', idx)
        viewer!.selectedEntity = picked.id
      } else {
        viewer!.selectedEntity = undefined
      }
    }, Cesium.ScreenSpaceEventType.LEFT_CLICK)
  }

  // 无人机 GPS 标记
  if (hasGeo && props.responseData?.centerLat && !props.responseData?.geo) {
    droneEntity = viewer.entities.add({
      position: Cesium.Cartesian3.fromDegrees(
        props.responseData.centerLng,
        props.responseData.centerLat
      ),
      billboard: {
        image: 'data:image/svg+xml;base64,' + btoa(
          '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><circle cx="16" cy="16" r="12" fill="#409eff" stroke="#fff" stroke-width="2"/><text x="16" y="20" font-size="14" text-anchor="middle" fill="#fff">🛸</text></svg>'
        ),
        heightReference: Cesium.HeightReference.NONE,
      },
      label: {
        text: '无人机检测',
        font: '12px sans-serif',
        fillColor: Cesium.Color.WHITE,
        pixelOffset: new Cesium.Cartesian2(0, -20),
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
    })
    droneEntity.description = `
      <div style="color:#222">
        <b>🛸 无人机检测结果</b><br>
        检测到 ${props.detections.length} 个目标<br>
        <img src="${props.annotatedImage}" style="width:100%;border-radius:4px"/>
      </div>`
  }
}

/** 像素 → 经纬度（本地辅助，bounds 为 [west,south,east,north]） */
function pixelToLngLatRaw(
  px: number, py: number, imgW: number, imgH: number,
  bounds: [number, number, number, number]
): [number, number] {
  const [west, south, east, north] = bounds
  const lng = west + (px / imgW) * (east - west)
  const lat = north - (py / imgH) * (north - south)
  return [lng, lat]
}

// ---- 清理 ----
function clearOverlays() {
  if (!viewer) return
  if (imageEntity) { viewer.entities.remove(imageEntity); imageEntity = null }
  markerEntities.forEach(e => viewer!.entities.remove(e))
  markerEntities = []
  if (droneEntity) { viewer.entities.remove(droneEntity); droneEntity = null }
}

// ---- 图层开关 ----
function toggleOverlay() {
  showOverlay.value = !showOverlay.value
  if (imageEntity) imageEntity.show = showOverlay.value
}

function toggleMarkers() {
  showMarkers.value = !showMarkers.value
  markerEntities.forEach(e => { e.show = showMarkers.value })
}

// ---- 暴露给父组件的方法 ----
function highlightMarker(index: number) {
  currentHighlight = index
}
function unhighlightMarker(index: number) {
  if (currentHighlight === index) currentHighlight = -1
}
function flyToMarker(index: number) {
  if (!viewer) return
  const entity = markerEntities[index]
  if (entity) {
    viewer.selectedEntity = entity
    viewer.camera.flyTo({
      destination: entity.position!.getValue(Cesium.JulianDate.now()) as Cesium.Cartesian3,
      duration: 1.0,
    })
  }
}

defineExpose({ highlightMarker, unhighlightMarker, flyToMarker })

// ---- 生命周期 ----
let resizeObserver: ResizeObserver | null = null

function ensureMapInitialized() {
  const el = mapContainer.value
  if (!el || viewer) return
  // Cesium 要求容器有明确尺寸（>0），否则渲染崩溃
  if (el.clientWidth > 0 && el.clientHeight > 0) {
    try {
      initMap()
      updateOverlays()
    } catch (e) {
      console.error('[Cesium] 初始化失败:', e)
    }
  } else {
    console.warn('[Cesium] 容器尺寸为 0，等待 ResizeObserver...')
  }
}

onMounted(() => {
  nextTick(() => {
    // ResizeObserver：容器获得尺寸后初始化地图，尺寸变化时 resize
    if (mapContainer.value && 'ResizeObserver' in window) {
      resizeObserver = new ResizeObserver(() => {
        const el = mapContainer.value
        if (!el) return
        // ★ 关键：容器尺寸无效（0）时不 resize，避免 Cesium canvas 被设为 0
        if (el.clientWidth > 0 && el.clientHeight > 0) {
          if (viewer) {
            viewer.resize()
          } else {
            ensureMapInitialized()
          }
        }
      })
      resizeObserver.observe(mapContainer.value)
    }
    // 首次尝试初始化
    setTimeout(ensureMapInitialized, 50)
    setTimeout(ensureMapInitialized, 300)
    setTimeout(ensureMapInitialized, 800)
  })
})

onUnmounted(() => {
  if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
  if (viewer) { viewer.destroy(); viewer = null }
})

watch(() => [props.responseData, props.detections, props.annotatedImage], () => {
  nextTick(() => {
    ensureMapInitialized()
    updateOverlays()
    viewer?.resize()
  })
})
</script>

<template>
  <div class="map-wrapper">
    <div ref="mapContainer" class="cesium-container" />

    <!-- 图层控制 -->
    <div v-if="mapReady" class="layer-ctrl">
      <label><input type="checkbox" checked disabled> 🌍 三维地球</label>
      <label v-if="imageEntity">
        <input type="checkbox" :checked="showOverlay" @change="toggleOverlay"> 🖼️ 影像叠加
      </label>
      <label v-if="markerEntities.length > 0">
        <input type="checkbox" :checked="showMarkers" @change="toggleMarkers"> 🎯 检测标记 ({{ markerEntities.length }})
      </label>
    </div>

    <!-- 底部信息栏 -->
    <div v-if="responseData?.geo" class="map-info">
      中心: {{ responseData.geo.centerLat.toFixed(4) }}°N, {{ responseData.geo.centerLng.toFixed(4) }}°E
    </div>
    <div v-else-if="responseData?.centerLat" class="map-info">
      坐标: {{ responseData.centerLat.toFixed(6) }}°N, {{ responseData.centerLng.toFixed(6) }}°E
    </div>
  </div>
</template>

<style scoped>
.map-wrapper {
  position: relative;
  width: 100%;
  height: calc(100vh - 60px); /* 明确高度，不依赖 flex 链（header 60px） */
  display: block;
  overflow: hidden;
}

.cesium-container {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 1;
  background: #0a0a1a;
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
  z-index: 1000;
}
</style>
