/**
 * 地理坐标转换工具。
 *
 * 将 GeoTIFF 的 Web Mercator (EPSG:3857) 投影坐标转为 WGS84 经纬度，
 * 供 Leaflet 地图使用。
 */

import type { LatLngBoundsExpression, LatLngTuple } from 'leaflet'

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
 * 从后端 geo 响应构建 Leaflet 边界。
 */
export function geoToLeafletBounds(geo: GeoBounds): LatLngBoundsExpression {
  return [
    [geo.south, geo.west] as LatLngTuple,
    [geo.north, geo.east] as LatLngTuple,
  ]
}

/**
 * 从中心点和缩放级别构建地图视图。
 */
export function geoToCenter(geo: GeoBounds): { center: LatLngTuple; zoom: number } {
  // 根据覆盖范围自动计算合适的缩放级别
  const latDiff = geo.north - geo.south
  const zoom = latDiff > 0.1 ? 13 : latDiff > 0.01 ? 15 : latDiff > 0.001 ? 17 : 19
  return {
    center: [geo.centerLat, geo.centerLng] as LatLngTuple,
    zoom,
  }
}

/**
 * 从后端响应中提取地理信息。
 * 优先使用 `geo` 字段（GeoTIFF），其次使用 `centerLat`/`centerLng`（无人机）。
 */
export function extractGeoFromResponse(data: any): {
  bounds: LatLngBoundsExpression | null
  center: LatLngTuple
  zoom: number
  hasGeo: boolean
} {
  if (data.geo && data.geo.north) {
    const geo = data.geo as GeoBounds
    return {
      bounds: geoToLeafletBounds(geo),
      ...geoToCenter(geo),
      hasGeo: true,
    }
  }
  if (data.centerLat && data.centerLng) {
    return {
      bounds: null,
      center: [data.centerLat, data.centerLng] as LatLngTuple,
      zoom: 17,
      hasGeo: true,
    }
  }
  // 默认：北京
  return {
    bounds: null,
    center: [39.9, 116.4] as LatLngTuple,
    zoom: 5,
    hasGeo: false,
  }
}
