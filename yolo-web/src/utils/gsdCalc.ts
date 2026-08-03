/**
 * GSD（地面采样距离）及裁切策略计算工具。
 *
 * 核心公式：
 *   GSD = (传感器宽度 / 图片宽度) × (高度 / 焦距)
 *
 * 倾斜拍摄时用俯仰角修正：
 *   平均 GSD ≈ GSD_nadir / sin(俯仰角)
 *   其中俯仰角从水平面算起（0°=水平, 90°=正射垂直向下）
 */

import type { DroneCamera } from './droneDb'

// ---- 类型 ----

export interface GsdResult {
  /** 地面采样距离（米/像素） */
  gsd: number
  /** 地面覆盖宽度（米） */
  groundWidth: number
  /** 地面覆盖高度（米） */
  groundHeight: number
  /** 覆盖面积（km²） */
  coverageKm2: number
  /** 推荐裁切瓦片尺寸（像素），0 表示不建议裁切 */
  tileSize: number
  /** 策略说明 */
  recommendation: string
  /** 告警信息，null 表示无警告 */
  warning: string | null
}

// ---- 常量 ----

/** 最小关注目标尺寸（米） */
const MIN_TARGET_METERS = 10

/** YOLO 模型可检测的最小像素数（经验值） */
const MIN_DETECTABLE_PX = 15

/** 瓦片触发阈值：图片宽/高超过此像素数时考虑裁切 */
const TILE_THRESHOLD = 1280

// ---- 核心计算 ----

/**
 * 计算正射（垂直向下）时的 GSD。
 *
 * @param camera    无人机相机参数
 * @param altitude  飞行高度（米，海拔/离地高度取决于用户定义）
 * @param imageWidth 实际图片宽度（像素），从上传的图片中读取
 */
export function calcNadirGsd(
  camera: DroneCamera,
  altitude: number,
  imageWidth: number
): number {
  // 使用实际图片宽度而非数据库中的典型值
  return (camera.sensorWidth / imageWidth) * (altitude / camera.focalLength)
}

/**
 * 俯仰角修正：将正射 GSD 修正为倾斜拍摄的平均 GSD。
 *
 * @param nadirGsd  正射 GSD（米/像素）
 * @param pitchDeg  俯仰角（°），0=水平, 90=正射
 */
export function adjustForPitch(nadirGsd: number, pitchDeg: number): number {
  if (pitchDeg >= 90) return nadirGsd
  if (pitchDeg <= 5) return nadirGsd * 11.5 // 接近水平，覆盖范围极大

  const pitchRad = (pitchDeg * Math.PI) / 180
  return nadirGsd / Math.sin(pitchRad)
}

/**
 * 完整计算：根据相机参数、飞行参数、图片尺寸，
 * 输出 GSD、覆盖范围、裁切建议。
 *
 * @param camera     无人机相机参数
 * @param altitude   飞行高度（米）
 * @param pitchDeg   俯仰角（°），默认 90（正射）
 * @param imageWidth 图片宽度（像素）
 * @param imageHeight 图片高度（像素）
 */
export function calcGsdResult(
  camera: DroneCamera,
  altitude: number,
  pitchDeg: number,
  imageWidth: number,
  imageHeight: number
): GsdResult {
  const nadirGsd = calcNadirGsd(camera, altitude, imageWidth)
  const gsd = adjustForPitch(nadirGsd, pitchDeg)

  const groundWidth = imageWidth * gsd
  const groundHeight = imageHeight * gsd
  const coverageKm2 = (groundWidth * groundHeight) / 1_000_000

  // 10m 目标在模型输入中的像素数
  const targetPx = (MIN_TARGET_METERS / gsd) * (640 / Math.max(imageWidth, imageHeight))

  let tileSize: number
  let recommendation: string
  let warning: string | null = null

  if (Math.max(imageWidth, imageHeight) <= TILE_THRESHOLD) {
    tileSize = 0
    recommendation = `覆盖范围 ${groundWidth.toFixed(0)}m × ${groundHeight.toFixed(0)}m，无需裁切`
  } else if (targetPx > 20) {
    tileSize = 1280
    recommendation = `精细影像，使用 1280px 瓦片（2×缩小），${MIN_TARGET_METERS}m 目标在模型中约 ${targetPx.toFixed(0)}px`
  } else if (targetPx > 10) {
    tileSize = 960
    recommendation = `中等分辨率，使用 960px 瓦片（1.5×缩小），${MIN_TARGET_METERS}m 目标在模型中约 ${targetPx.toFixed(0)}px`
  } else if (targetPx > MIN_DETECTABLE_PX / 2) {
    tileSize = 640
    recommendation = `分辨率较低，使用 640px 瓦片（不缩放），${MIN_TARGET_METERS}m 目标仅 ${targetPx.toFixed(0)}px`
    warning = `当前 GSD=${gsd.toFixed(3)} m/px，${MIN_TARGET_METERS}m 目标仅 ${targetPx.toFixed(0)} 像素，接近检测极限`
  } else {
    tileSize = 640
    recommendation = `分辨率极低，不建议裁切，${MIN_TARGET_METERS}m 目标仅 ${targetPx.toFixed(0)}px，低于检测阈值`
    warning = `当前 GSD=${gsd.toFixed(3)} m/px，${MIN_TARGET_METERS}m 目标仅 ${targetPx.toFixed(0)} 像素，低于模型可检测的最小阈值（约 ${MIN_DETECTABLE_PX}px）。检测结果可能严重不全。`
  }

  return { gsd, groundWidth, groundHeight, coverageKm2, tileSize, recommendation, warning }
}
