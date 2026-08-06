/**
 * 前端全局配置
 */

/** Cesium Ion access token（从 cesium.com/ion 获取） */
export const CESIUM_ION_TOKEN = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJqdGkiOiJmOWEzMWE5Ni00Y2ZmLTQxZWItYThhMC05ZWIzOWU5YzA3ODMiLCJpZCI6NDYzNzA4LCJpc3MiOiJodHRwczovL2FwaS5jZXNpdW0uY29tIiwiYXVkIjoidW5kZWZpbmVkX2RlZmF1bHQiLCJpYXQiOjE3ODU4MjE0Nzh9.BfcVzMJOfUszyNFyWsGqOUJKwkEZ_Hlu1Njr9LA1ZD8'

/** 后端瓦片代理地址（作为底图回退） */
export const TILE_PROXY_URL = 'http://localhost:8080/api/tile/{z}/{x}/{y}'
