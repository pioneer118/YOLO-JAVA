/**
 * 地理坐标转换工具。
 *
 * 将 GeoTIFF 的 Web Mercator (EPSG:3857) 投影坐标转为 WGS84 经纬度。
 * 纯函数，无地图框架依赖，Cesium/Leaflet 均可使用。
 */

/** WGS84 地理边界 */
export interface GeoBounds {
  north: number
  south: number
  east: number
  west: number
  centerLat: number
  centerLng: number
}

/** 地球半径（米），Web Mercator 使用 */
const EARTH_RADIUS = 6378137

/**
 * Web Mercator Y → 纬度（度）
 */
export function mercatorToLat(y: number): number {
  return (Math.atan(Math.sinh(y / EARTH_RADIUS)) * 180) / Math.PI
}

/**
 * Web Mercator X → 经度（度）
 */
export function mercatorToLng(x: number): number {
  return (x / EARTH_RADIUS) * (180 / Math.PI)
}

/**
 * 从后端响应中提取地理信息。
 * 优先使用 `geo` 字段（GeoTIFF），其次使用 `centerLat`/`centerLng`（无人机）。
 *
 * 返回 WGS84 经纬度集合：
 * - bounds: 影像四角边界 [west, south, east, north]（Cesium Rectangle 格式）
 * - center: [经度, 纬度]
 * - hasGeo: 是否包含地理信息
 */
export function extractGeoFromResponse(data: any): {
  bounds: [number, number, number, number] | null  // [west, south, east, north]
  center: [number, number]                          // [lng, lat]
  hasGeo: boolean
} {
  if (data.geo && data.geo.north) {
    const g = data.geo as GeoBounds
    return {
      bounds: [g.west, g.south, g.east, g.north],
      center: [g.centerLng, g.centerLat],
      hasGeo: true,
    }
  }
  if (data.centerLat && data.centerLng) {
    return {
      bounds: null,
      center: [data.centerLng, data.centerLat],
      hasGeo: true,
    }
  }
  // 默认：北京
  return {
    bounds: null,
    center: [116.4, 39.9],
    hasGeo: false,
  }
}

/**
 * 像素坐标 → 经纬度（用于将检测框中心像素转为 WGS84 坐标）
 *
 * @param px 图片像素 X
 * @param py 图片像素 Y
 * @param imgW 图片宽度
 * @param imgH 图片高度
 * @param bounds [west, south, east, north]
 */
export function pixelToLngLat(
  px: number, py: number, imgW: number, imgH: number,
  bounds: [number, number, number, number]
): [number, number] {
  const [west, south, east, north] = bounds
  const lng = west + (px / imgW) * (east - west)
  const lat = north - (py / imgH) * (north - south)
  return [lng, lat]
}
