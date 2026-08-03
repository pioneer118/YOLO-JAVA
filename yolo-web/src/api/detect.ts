import axios from 'axios'

const api = axios.create({
  baseURL: 'http://localhost:8080',
  timeout: 600000, // 10 分钟，1024 模型 + TIFF 裁切可能很慢
})

export interface Bbox {
  x1: number; y1: number; x2: number; y2: number
  x3: number; y3: number; x4: number; y4: number
}

export interface Detection {
  classId: number
  className: string
  confidence: number
  bbox: Bbox
  /** 地理纬度（后端可选返回） */
  lat?: number
  /** 地理经度（后端可选返回） */
  lng?: number
}

/** 后端返回的裁切元数据 */
export interface TilingMeta {
  enabled: boolean
  strategy: string
  tileSize: number
  tileCount: number
  gsd: number
  coverageKm2: number
  warning: string | null
}

/** 后端返回的地理坐标 */
export interface GeoInfo {
  north: number; south: number; east: number; west: number
  centerLat: number; centerLng: number
}

export interface DetectResult {
  totalCount: number
  processingTimeMs: number
  annotatedImage: string
  detections: Detection[]
  /** 裁切元数据（后端 1.1+ 返回） */
  tiling?: TilingMeta
  /** GeoTIFF 地理边界 */
  geo?: GeoInfo
  /** 无人机/标记中心纬度 */
  centerLat?: number
  /** 无人机/标记中心经度 */
  centerLng?: number
}

/** 无人机参数（可选，仅 dataSource='drone' 时有效） */
export interface DroneParams {
  /** 地面采样距离（米/像素），前端计算后传入 */
  gsd: number
  /** 纬度（可选） */
  lat?: number
  /** 经度（可选） */
  lng?: number
}

export async function detectImage(
  file: File,
  dataSource: string = 'satellite',
  confThreshold: number = 0.5,
  droneParams?: DroneParams,
  targets?: string[]
): Promise<DetectResult> {
  const formData = new FormData()
  formData.append('image', file)
  formData.append('dataSource', dataSource)
  formData.append('confThreshold', String(confThreshold))

  if (droneParams && droneParams.gsd > 0) {
    formData.append('gsd', String(droneParams.gsd))
    if (droneParams.lat !== undefined) {
      formData.append('lat', String(droneParams.lat))
    }
    if (droneParams.lng !== undefined) {
      formData.append('lng', String(droneParams.lng))
    }
  }

  if (targets && targets.length > 0 && targets.length < 3) {
    formData.append('targets', targets.join(','))
  }

  const { data } = await api.post<DetectResult>('/api/detect', formData)
  return data
}

export async function getHealth(): Promise<{ status: string; gateway: string; timestamp: string }> {
  const { data } = await api.get('/api/health')
  return data
}
