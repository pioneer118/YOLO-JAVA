# YOLO-RPC 分布式目标检测系统 — 启动教程

## 一、项目简介

基于 **Java 21 + Dubbo3 + Spring Boot 3.4** 的分布式 YOLO 旋转目标检测系统，用于卫星遥感影像中的**舰船 / 飞机 / 车辆**检测。

### 系统架构

```
前端(5173, Cesium 三维地球)
    ↓ HTTP
yolo-web-api (8080, REST API)
    ↓ Dubbo3 RPC
yolo-gateway (9000, 聚合网关, 虚拟线程并行扇出)
    ↓ Dubbo3 RPC (group 区分)
yolo-server × 3:
  ship  (8001, bestship.onnx,  15类舰船)
  plane (8002, bestplane.onnx, 31类飞机)
  car   (8003, bestcar.onnx,   1类坦克)
    ↑
Nacos (8848, 服务注册发现)
```

### 核心能力

- 大图自适应裁切（GeoTIFF GSD 驱动策略）
- 三模型并行推理 + 结果聚合 + 跨 tile NMS
- 旋转边界框（OBB）检测与可视化
- 前端 Cesium 三维地球，检测结果地理叠加

---

## 二、环境要求

| 软件 | 版本 | 说明 |
|------|------|------|
| JDK | 21+ | 本项目用虚拟线程，必须 Java 21 |
| Maven | 3.9+ | 多模块构建 |
| Node.js | 18+ | 前端构建 |
| ONNX Runtime | 1.26.0 | 本地库需单独配置 |
| Nacos | 2.4+ | 服务注册中心 |

---

## 三、前置准备

### 1. 模型文件（必须放在 models/ 目录）

| 文件 | 类别 | 说明 |
|------|------|------|
| bestship.onnx | 15 类舰船 | 输入 1024×1024，输出 7 列 OBB |
| bestplane.onnx | 31 类飞机 | 输入 1024×1024，输出 7 列 OBB |
| bestcar.onnx | 1 类坦克 | 输入动态，输出 6 列标准框 |

配套 yaml：models/ship.yaml、models/plane.yaml、models/car.yaml（训练配置，含类别名）。

### 2. ONNX Runtime 本地库

Windows 环境下载 onnxruntime-win-x64-1.26.0，解压后记录 lib 路径，例如：
`C:\Users\昼\tools\onnxruntime\onnxruntime-win-x64-1.26.0\lib\onnxruntime.dll`

### 3. Nacos 服务端

需要 nacos-server.jar（或完整 Nacos 发行版），例如：
`C:\Users\昼\tools\nacos\target\nacos-server.jar`

> ⚠️ 若 Nacos 反复启动失败并报 Derby 数据库错误，删除 `nacos/data/derby-data` 目录后重启（服务会自动重新注册）。

---

## 四、编译打包

```bash
cd C:\Users\昼\IdeaProjects\YOLO-RPC
mvn package -DskipTests -Dprotoc.skip=true
```

编译产物：
- `yolo-server/target/yolo-server-1.0.0.jar`
- `yolo-gateway/target/yolo-gateway-1.0.0.jar`
- `yolo-web-api/target/yolo-web-api-1.0.0.jar`

前端安装依赖：
```bash
cd yolo-web
npm install
```

> Maven 若不在 PATH 中，用完整路径：`D:\develop\apache-maven-3.9.4-bin\apache-maven-3.9.4\bin\mvn`

---

## 五、启动步骤（严格按顺序）

### 第 1 步：启动 Nacos（:8848）

```bash
java -Xms512m -Xmx512m -Dnacos.standalone=true \
  -jar C:\Users\昼\tools\nacos\target\nacos-server.jar
```

验证：浏览器访问 `http://localhost:8848/nacos`（默认账号密码 `nacos/nacos`）。

### 第 2 步：启动三个 yolo-server

每个服务启动前确认：模型文件存在、ONNX Runtime lib 路径正确（`-Donnxruntime.lib.path` 指向 dll）。

```bash
# 舰船检测服务 (group=ship, :8001)
java -Xmx1536m -Donnxruntime.lib.path="C:\Users\昼\tools\onnxruntime\onnxruntime-win-x64-1.26.0\lib\onnxruntime.dll" \
  -jar yolo-server\target\yolo-server-1.0.0.jar \
  --yolo.model-path="C:\Users\昼\IdeaProjects\YOLO-RPC\models\bestship.onnx" \
  --yolo.model-type=ship --dubbo.protocol.port=8001

# 飞机检测服务 (group=plane, :8002)
java -Xmx1536m -Donnxruntime.lib.path="C:\Users\昼\tools\onnxruntime\onnxruntime-win-x64-1.26.0\lib\onnxruntime.dll" \
  -jar yolo-server\target\yolo-server-1.0.0.jar \
  --yolo.model-path="C:\Users\昼\IdeaProjects\YOLO-RPC\models\bestplane.onnx" \
  --yolo.model-type=plane --dubbo.protocol.port=8002

# 车辆检测服务 (group=car, :8003)
java -Xmx1g -Donnxruntime.lib.path="C:\Users\昼\tools\onnxruntime\onnxruntime-win-x64-1.26.0\lib\onnxruntime.dll" \
  -jar yolo-server\target\yolo-server-1.0.0.jar \
  --yolo.model-path="C:\Users\昼\IdeaProjects\YOLO-RPC\models\bestcar.onnx" \
  --yolo.model-type=car --dubbo.protocol.port=8003
```

验证：三个服务端口监听，Nacos 控制台能看到 3 个 `yolo-server` 实例。

### 第 3 步：启动网关（:9000）

```bash
java -Xmx1g -jar yolo-gateway\target\yolo-gateway-1.0.0.jar --dubbo.protocol.port=9000
```

### 第 4 步：启动 Web API（:8080）

```bash
java -Xmx384m -jar yolo-web-api\target\yolo-web-api-1.0.0.jar --server.port=8080
```

### 第 5 步：启动前端（:5173）

```bash
cd yolo-web
npx vite --port 5173 --host
```

---

## 六、验证系统

### 1. 健康检查

```bash
curl http://localhost:8080/api/health
# 预期: {"status":"UP","gateway":"UP"}
```

### 2. 命令行检测测试

```bash
curl -X POST http://localhost:8080/api/detect \
  -F "image=@testimage/星载可见光影像.png" \
  -F "confThreshold=0.25" \
  -F "targets=ship,plane,car"
```

### 3. 前端使用

浏览器打开 `http://localhost:5173`：
1. 左侧：选择数据来源（卫星/无人机）、勾选检测目标、设置置信度阈值
2. 中间：Cesium 三维地球（Esri 卫星底图，经后端代理加载）
3. 上传图片 → 开始检测 → 结果叠加到三维地球 + 右侧检测列表

---

## 七、端口一览

| 服务 | 端口 | 说明 |
|------|------|------|
| Nacos | 8848 | 服务注册中心 |
| yolo-server (ship) | 8001 | 舰船检测 |
| yolo-server (plane) | 8002 | 飞机检测 |
| yolo-server (car) | 8003 | 车辆检测 |
| yolo-gateway | 9000 | 聚合网关 |
| yolo-web-api | 8080 | REST API |
| yolo-web | 5173 | 前端 |

---

## 八、关闭项目

```bash
# 按端口关闭所有服务（Windows）
for /f "tokens=5" %a in ('netstat -ano ^| findstr ":5173 :8001 :8002 :8003 :8080 :8848 :9000" ^| findstr LISTENING') do taskkill /F /PID %a
```

或逐个 Ctrl+C 关闭前台进程。

---

## 九、常见问题

| 问题 | 解决 |
|------|------|
| ONNX 加载失败 `UnsatisfiedLinkError` | 检查 `-Donnxruntime.lib.path` 指向的 dll 路径 |
| 服务注册不上 Nacos | 确认 Nacos 已启动，`dubbo.registry.address` 正确 |
| Nacos 反复崩 `Derby Login timeout` | 删除 `nacos/data/derby-data` 后重启 |
| 网关 JVM 崩溃 OOM | 降低并发 `yolo.tiling.max-concurrent-tiles`，或调低 -Xmx |
| 检测 0 目标 | 降低阈值；或确认模型/类别名匹配 |
| 检测太多误检（几千个） | 模型置信度头问题，需重新训练 |
| 三维地球黑屏 | 确认后端 `/api/tile` 代理可用 |
| TIFF 检测慢（几十秒） | 1024 模型 + 大图裁切属正常 |

---

## 十、配置要点

### 网关并发（yolo-gateway/application.yml）

```yaml
yolo:
  tiling:
    max-concurrent-tiles: 8    # 过高会导致内存峰值 OOM
```

### 前端底图（yolo-web/src/config.ts）

```typescript
export const CESIUM_ION_TOKEN = '...'       // Cesium Ion token
export const TILE_PROXY_URL = 'http://localhost:8080/api/tile/{z}/{x}/{y}'  // 底图经后端代理
```

浏览器无法直接访问第三方瓦片，由后端 `TileProxyController` 代理 Esri 卫星影像。
