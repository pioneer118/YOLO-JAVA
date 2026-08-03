# YOLO-Dist 开发计划文档

## 基于分布式 RPC 框架的高性能 YOLO 目标检测系统

---

## 一、项目概述

### 1.1 这个系统做什么

一个分布式的 YOLO 旋转目标检测系统。用户上传一张卫星遥感图像，系统自动检测出图中的**飞机、舰船、车辆**三类目标，在图片上绘制标注框，返回结果。

和普通目标检测的区别：本系统输出的是**旋转边界框（OBB）**，即矩形框可以倾斜，紧密贴合目标（比如斜向停泊的舰船），而不是只能画水平/垂直的矩形框。

### 1.2 为什么是"分布式"

系统拆成三个独立的后端服务（ship/plane/car），每个服务加载一个专用的 ONNX 模型，通过 Dubbo3 RPC 通信。网关收到请求后，用虚拟线程同时并行调用三个后端，聚合结果。各服务都可独立部署、独立扩缩容。

### 1.3 项目路径

`/Users/admin/Desktop/0605/yolo-dist/` — 全新项目，从零搭建。

---

## 二、前提条件

在开始开发前，确保以下环境已就绪：

| 软件 | 最低版本 | 验证命令 |
|------|---------|---------|
| JDK | 21+ | `java --version` |
| Maven | 3.9+ | `mvn --version` |
| protoc | 3.25+ | `protoc --version` |
| ONNX Runtime | 1.26.0 | `ls /opt/homebrew/Cellar/onnxruntime/1.26.0_1/lib/libonnxruntime.dylib` |
| Nacos | 2.4+ | 需下载安装 |

**模型文件**（必须提前准备好，放在 `yolo-dist/models/` 目录下）：

| 文件 | 大小 | 用途 |
|------|------|------|
| `best.onnx` | ~98MB | 舰船检测模型 |
| `best-plane.onnx` | ~98MB | 飞机检测模型 |
| `best-car.onnx` | ~98MB | 车辆检测模型 |

---

## 三、技术栈

| 层次 | 技术 | 版本 | 用途 |
|------|------|------|------|
| 语言 | Java | 21+ | 虚拟线程是核心依赖 |
| 框架 | Spring Boot | 3.4.x | 应用框架 |
| RPC | Dubbo | 3.3.x | Triple 协议，服务通信 + 负载均衡 |
| 序列化 | Protobuf | 3.25.x | 高性能二进制序列化 |
| 注册中心 | Nacos | 2.4.x | 服务注册发现 + 配置中心 |
| 推理引擎 | ONNX Runtime | 1.26.0 | Java API 直接调用，底层 C++ 推理 |
| 图像处理 | JavaCV | 1.5.10 | OpenCV Java 绑定（预处理/NMS/绘图） |
| 构建 | Maven | 3.9+ | 多模块项目管理 |
| 前端 | Vue 3 + Vite + Element Plus | — | 操作界面 + 状态监控 |

---

## 四、系统架构

```
                        ┌─────────────────┐
                        │     Nacos       │  服务注册发现 + 配置中心
                        │    :8848        │
                        └────────┬────────┘
                                 │
    ┌────────────────────────────┼────────────────────────────┐
    │                            │                            │
    ▼                            ▼                            ▼
┌───────────┐            ┌───────────┐            ┌───────────┐
│ ship 服务  │            │ plane 服务 │            │  car 服务  │
│  :8001    │            │  :8002    │            │  :8003    │
│           │            │           │            │           │
│ 加载      │            │ 加载      │            │ 加载      │
│ ship.onnx │            │ plane.onnx│            │ car.onnx  │
│           │            │           │            │           │
│ Dubbo3    │            │ Dubbo3    │            │ Dubbo3    │
│ Provider  │            │ Provider  │            │ Provider  │
│ group=ship│            │group=plane│            │ group=car │
│ 实现      │            │ 实现      │            │ 实现      │
│ ModelInf- │            │ ModelInf- │            │ ModelInf- │
│ erenceSvc │            │ erenceSvc │            │ erenceSvc │
└─────┬─────┘            └─────┬─────┘            └─────┬─────┘
      │                        │                        │
      └────────────────────────┼────────────────────────┘
                               │  Dubbo3 Triple (Protobuf)
                               │  @DubboReference(group="ship|plane|car")
                               ▼
                     ┌──────────────────┐
                     │   yolo-gateway   │
                     │     :9000        │
                     │                  │
                     │  Dubbo3 Provider │
                     │  + Consumer      │
                     │                  │
                     │  实现             │
                     │  YoloAggregation │
                     │  Service         │
                     │                  │
                     │  虚拟线程并行扇出  │
                     │  结果聚合 + 标注  │
                     └────────┬─────────┘
                              │  Dubbo3 Triple (Protobuf)
                              │  @DubboReference(YoloAggregationService)
                              ▼
                     ┌──────────────────┐
                     │   yolo-web-api   │
                     │     :8080        │
                     │                  │
                     │  REST API 层     │
                     │  JSON ↔ Protobuf │
                     │  Multipart 上传  │
                     └────────┬─────────┘
                              │  HTTP (JSON)
                              ▼
                     ┌──────────────────┐
                     │   yolo-web       │
                     │   Vue 3 前端     │
                     │   :5173          │
                     │                  │
                     │  检测页面 + 状态页│
                     └──────────────────┘
```

**关键区分**：
- 后端服务暴露 `ModelInferenceService`，通过不同的 Dubbo group 区分（ship/plane/car）
- 网关暴露 `YoloAggregationService`，客户端只和这个接口打交道
- 路由在 Dubbo 服务发现阶段完成，不依赖请求中的字符串参数

**数据流向**：
1. 用户通过 Vue 前端上传图片 → HTTP POST 到 yolo-web-api
2. yolo-web-api 将 JSON 转为 Protobuf，通过 Dubbo3 调用 yolo-gateway
3. yolo-gateway 用虚拟线程并行调用 ship/plane/car 三个后端
4. 每个后端用 ONNX Runtime 执行模型推理，返回检测结果
5. yolo-gateway 聚合三路结果，在原始图片上统一绘制标注框
6. 标注图以 Base64 编码返回前端，前端渲染展示

---

## 五、项目目录结构

```
yolo-dist/
├── pom.xml                              # 父 POM（管理子模块 + 依赖版本）
├── models/                              # ONNX 模型文件目录
│   ├── best.onnx
│   ├── best-plane.onnx
│   └── best-car.onnx
├── yolo-proto/                          # 模块1：Protobuf 消息定义 + Java RPC 接口
│   ├── pom.xml
│   ├── src/main/proto/yolo_detect.proto
│   └── src/main/java/com/yolo/proto/
│       ├── ModelInferenceService.java   # 后端推理服务 RPC 接口
│       └── YoloAggregationService.java  # 网关聚合服务 RPC 接口
├── yolo-inference/                      # 模块2：ONNX Runtime 推理引擎
│   ├── pom.xml
│   └── src/main/java/com/yolo/inference/
│       ├── YoloInferenceEngine.java     # 推理核心类
│       ├── DetResult.java              # 检测结果 POJO
│       ├── RotatedBox.java             # 旋转框 POJO
│       ├── DetectionOptions.java       # 检测参数（阈值等）
│       └── YoloClassNames.java         # 15 类中文类别名
├── yolo-server/                         # 模块3：后端推理服务
│   ├── pom.xml
│   ├── src/main/resources/application.yml
│   └── src/main/java/com/yolo/server/
│       ├── YoloServerApplication.java  # Spring Boot 启动类
│       └── ModelInferenceServiceImpl.java  # @DubboService(group="ship|plane|car") 实现
├── yolo-gateway/                        # 模块4：聚合网关
│   ├── pom.xml
│   ├── src/main/resources/application.yml
│   └── src/main/java/com/yolo/gateway/
│       ├── YoloGatewayApplication.java # Spring Boot 启动类
│       └── YoloAggregationServiceImpl.java # 扇出 + 聚合 + 降级
├── yolo-web-api/                        # 模块5：REST API 层
│   ├── pom.xml
│   ├── src/main/resources/application.yml
│   └── src/main/java/com/yolo/webapi/
│       ├── YoloWebApiApplication.java  # Spring Boot 启动类
│       ├── controller/
│       │   ├── DetectController.java   # POST /api/detect
│       │   └── HealthController.java   # GET /api/health
│       └── config/CorsConfig.java      # 跨域配置
├── yolo-client/                         # 模块6：命令行测试客户端
│   ├── pom.xml
│   └── src/main/java/com/yolo/client/
│       └── YoloClient.java
└── yolo-web/                            # 模块7：Vue 3 前端
    ├── package.json
    ├── vite.config.ts
    ├── index.html
    ├── tsconfig.json
    └── src/
        ├── App.vue
        ├── main.ts
        ├── style.css
        ├── router/index.ts
        ├── views/
        │   ├── DetectView.vue          # 检测主页面
        │   └── StatusView.vue          # 系统状态页面
        ├── api/
        │   └── detect.ts               # Axios API 封装
        └── components/
            └── ImageUploader.vue       # 图片上传组件（拖拽）
```

---

## 六、各模块详细设计

### 6.1 yolo-proto — Protobuf 消息定义 + Java RPC 接口

#### 6.1.1 设计思路

第一版为了降低踩坑概率，**不依赖 Dubbo Triple IDL 代码生成插件**。采用方案：Protobuf 只定义消息格式，RPC 接口用普通 Java 接口定义，Dubbo 使用 Triple 协议 + Protobuf 序列化。

**为什么拆成两个 RPC 接口**：

三个后端模型服务（ship/plane/car）和网关聚合服务的职责不同，不能共用同一个接口。如果都暴露 `YoloDetectService`：
1. Dubbo 无法仅凭 Java 字段名区分三个后端——ship 的请求可能被路由到 plane 实例
2. yolo-web-api 的 `@DubboReference` 可能绕过网关直接调用模型服务

**解决方案**：
- `ModelInferenceService`：后端推理服务暴露的接口，三个服务用不同的 **Dubbo group** 注册
- `YoloAggregationService`：网关暴露的聚合接口，客户端只和这个接口打交道
- 路由在 Dubbo 服务发现阶段完成，不依赖请求里的 `model_type` 字符串

#### 6.1.2 Protobuf 消息定义

**文件**：`yolo-proto/src/main/proto/yolo_detect.proto`

```protobuf
syntax = "proto3";
package yolo;
option java_package = "com.yolo.proto";
option java_multiple_files = true;

// ============================================================
// 单张图片检测 — 请求（后端推理使用）
// ============================================================
message ModelDetectRequest {
    bytes  image_data     = 1;   // JPEG/PNG 编码的图片二进制数据
    float  conf_threshold = 2;   // 置信度阈值（默认 0.5）
    string data_source    = 3;   // 数据来源：satellite / drone（预留）
    // 注意：没有 model_type 字段，因为路由在 Dubbo group 层面完成
}

// ============================================================
// 单张图片检测 — 请求（网关聚合使用，客户端调用）
// ============================================================
message AggregateDetectRequest {
    bytes  image_data     = 1;   // JPEG/PNG 编码的图片二进制数据
    float  conf_threshold = 2;   // 置信度阈值（默认 0.5）
    string data_source    = 3;   // 数据来源：satellite / drone（预留）
}

// ============================================================
// 旋转边界框
// ============================================================
message BoundingBox {
    float x1 = 1; float y1 = 2;
    float x2 = 3; float y2 = 4;
    float x3 = 5; float y3 = 6;
    float x4 = 7; float y4 = 8;
}

// ============================================================
// 单个检测目标
// ============================================================
message Detection {
    int32       class_id    = 1;
    string      class_name  = 2;
    float       confidence  = 3;
    BoundingBox bbox        = 4;
}

// ============================================================
// 后端推理响应（不包含标注图，交给网关统一绘制）
// ============================================================
message ModelDetectResponse {
    repeated Detection detections         = 1;
    int32              total_count        = 2;
    int32              processing_time_ms = 3;
}

// ============================================================
// 网关聚合响应（包含标注图）
// ============================================================
message AggregateDetectResponse {
    repeated Detection detections         = 1;
    int32              total_count        = 2;
    bytes              annotated_image    = 3;   // JPEG 标注图
    int32              processing_time_ms = 4;
}
```

#### 6.1.3 Java RPC 接口

**文件**：`yolo-proto/src/main/java/com/yolo/proto/ModelInferenceService.java`

```java
package com.yolo.proto;

/**
 * 后端模型推理服务 RPC 接口。
 * 三个模型服务（ship/plane/car）各自实现此接口，
 * 通过不同的 Dubbo group 注册到 Nacos。
 */
public interface ModelInferenceService {
    ModelDetectResponse detect(ModelDetectRequest request);
}
```

**文件**：`yolo-proto/src/main/java/com/yolo/proto/YoloAggregationService.java`

```java
package com.yolo.proto;

/**
 * 网关聚合服务 RPC 接口。
 * 网关暴露此接口，客户端（yolo-web-api / yolo-client）只和此接口打交道。
 */
public interface YoloAggregationService {
    AggregateDetectResponse detect(AggregateDetectRequest request);
}
```

**说明**：两个接口的方法签名只是普通的 Java 方法，参数和返回值是 Protobuf 生成的消息类型。Dubbo3 Triple 协议会自动使用 Protobuf 序列化这些消息。不需要任何 Dubbo IDL 代码生成插件。

#### 6.1.4 pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.yolo</groupId>
        <artifactId>yolo-dist</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>yolo-proto</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>com.google.protobuf</groupId>
            <artifactId>protobuf-java</artifactId>
        </dependency>
        <dependency>
            <groupId>com.google.protobuf</groupId>
            <artifactId>protobuf-java-util</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.xolstice.maven.plugins</groupId>
                <artifactId>protobuf-maven-plugin</artifactId>
                <version>0.6.1</version>
                <configuration>
                    <protocArtifact>com.google.protobuf:protoc:3.25.5:exe:osx-aarch_64</protocArtifact>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>compile</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

---

### 6.2 yolo-inference — 推理引擎

这是整个系统最核心的模块。它封装了 ONNX Runtime，负责加载模型、图像预处理、推理执行、结果后处理、NMS 去重、标注绘图。

#### 6.2.1 关键前提：理解 ONNX 模型的输入输出

**不管模型是用什么框架训练的（PyTorch/YOLO/Ultralytics），导出为 ONNX 后，它就是一个黑盒函数，只关心输入张量和输出张量的格式。**

本项目使用的模型规格：

| 属性 | 值 |
|------|-----|
| 输入节点名 | `images` |
| 输入形状 | `[1, 3, 640, 640]` float32 |
| 输入含义 | 1 张图，3 通道 (RGB)，640×640 像素，值域 [0, 1] |
| 输出节点名 | `output0` |
| 输出形状 | `[1, N, 7]` float32（N 为检测框数量，通常 300） |
| 输出含义 | 每行 7 个 float 描述一个检测框 |

**输出每行 7 个值的含义**：

| 位置 | 字段名 | 含义 | 值域 |
|------|--------|------|------|
| 0 | cx | 边界框中心点 X 坐标 | 0~640（模型输入空间） |
| 1 | cy | 边界框中心点 Y 坐标 | 0~640 |
| 2 | w | 边界框宽度 | 0~640 |
| 3 | h | 边界框高度 | 0~640 |
| 4 | angle | 边界框旋转角度 | 弧度，通常 -π ~ π |
| 5 | cls_id | 类别 ID | 0~14（float，需转 int） |
| 6 | raw_conf | 原始置信度 logits | 任意实数，**需 sigmoid 转换** |

**重要**：第 6 列的 conf 是原始 logits 值，不是 0~1 的概率。必须经过 sigmoid 激活：`conf = 1.0 / (1.0 + exp(-raw_conf))` 后才能得到真实的置信度。

#### 6.2.2 推理管线（完整流程）

一个完整的 `detect()` 调用包含以下步骤：

```
输入: BufferedImage (原始图片)
  │
  ▼
【预处理 preprocess】
  1. BufferedImage → JavaCV Mat (OpenCV 读取为 BGR)
  2. BGR → RGB (cvtColor)
  3. Resize 到 640×640 (resize)
  4. 转为 float32，每个像素除以 255.0
  5. HWC → CHW 排列（分离三通道，交错排列成 1×3×640×640 的一维数组）
  │
  ▼
【ONNX 推理】
  6. 构建 OnnxTensor，形状 [1, 3, 640, 640]
  7. session.run()，输入 "images"，输出 "output0"
  │
  ▼
【后处理 postprocess】
  8. 获取输出 float[][][] → 遍历 N 个检测框
  9. 对每个检测框：
     a. raw_conf → sigmoid → confidence
     b. 如果 confidence < 阈值，丢弃
     c. 坐标从 640×640 缩放回原始图片尺寸
     d. (cx,cy,w,h,angle) → 四个角点坐标 (x1,y1,x2,y2,x3,y3,x4,y4)
     e. 查找类别名称
  │
  ▼
【NMS 去重】
  10. 按置信度降序排序
  11. 贪心抑制：同类别中 IoU > 0.5 的框互相抑制
  12. 旋转框 IoU 用 cv::rotatedRectangleIntersection 计算真实交集
  │
  ▼
输出: List<DetResult> (检测结果列表)
```

#### 6.2.3 旋转框角点转换公式（obbToCorners）

YOLO OBB 模型输出 `(cx, cy, w, h, angle)` 格式，需要转换为四个角点坐标用于可视化和 IoU 计算。

给定中心点 (cx, cy)、宽高 (w, h)、旋转角 angle（弧度）：

```
cos_a = cos(angle)
sin_a = sin(angle)
hw = w * 0.5   // 半宽
hh = h * 0.5   // 半高

// 四个角点（以中心为原点做旋转，再平移到 (cx, cy)）
x1 = cx - hw*cos_a + hh*sin_a    y1 = cy - hw*sin_a - hh*cos_a  // 左上
x2 = cx + hw*cos_a + hh*sin_a    y2 = cy + hw*sin_a - hh*cos_a  // 右上
x3 = cx + hw*cos_a - hh*sin_a    y3 = cy + hw*sin_a + hh*cos_a  // 右下
x4 = cx - hw*cos_a - hh*sin_a    y4 = cy - hw*sin_a + hh*cos_a  // 左下
```

**实现**：纯 Java 数学，`Math.cos()` + `Math.sin()`，不需要任何第三方库。

#### 6.2.4 旋转框 NMS（非极大值抑制）

**为什么需要 NMS**：模型会对同一个目标输出多个高度重叠的检测框，NMS 去重保留最好的那个。

**为什么旋转框 NMS 比普通 NMS 复杂**：普通 NMS 用轴对齐矩形算 IoU 即可。旋转框如果也用轴对齐矩形近似，会出现"两个旋转框完全不重叠，但它们的轴对齐外包矩形大面积重叠"的错误抑制。

**正确做法**：用 OpenCV 的 `rotatedRectangleIntersection` 计算两个旋转矩形的真实交集多边形面积，再算 IoU。

**算法步骤**：
1. 将所有检测框按置信度从高到低排序
2. 预计算每个框的 `cv::RotatedRect`（从 4 个角点还原）和面积
3. 从置信度最高的框开始遍历：
   - 保留该框
   - 遍历所有置信度更低、同类别、未被抑制的框
   - 计算两个旋转矩形的交集面积（`rotatedRectangleIntersection` + `contourArea`）
   - IoU = 交集面积 / (面积1 + 面积2 - 交集面积)
   - 如果 IoU > 0.5，抑制低置信度的框
4. 只抑制同类别框（不同类别的框不互相抑制）

**JavaCV 中对应的 API**：
```java
import org.bytedeco.opencv.global.opencv_imgproc.*;
import org.bytedeco.opencv.opencv_core.*;

// 从四个角点还原 RotatedRect
Point2fVector pts = new Point2fVector(4);
pts.put(new Point2f(x1, y1), new Point2f(x2, y2),
        new Point2f(x3, y3), new Point2f(x4, y4));
RotatedRect rect = minAreaRect(new Mat(pts));

// 计算两个旋转矩形的交集
Mat intersection = new Mat();
rotatedRectangleIntersection(rect1, rect2, intersection);
float interArea = intersection.empty() ? 0f : (float) contourArea(intersection);

// 计算 IoU
float iou = interArea / (area1 + area2 - interArea + 1e-6f);
```

#### 6.2.5 标注绘制

在图片上绘制检测结果，包括旋转边框、标签、顶点标记。

**绘制步骤**：
1. 拷贝原图
2. 准备 15 种预定义颜色（BGR 格式），按 class_id 取模选择
3. 对每个检测框：
   - `polylines`：绘制旋转四边形（闭合，线宽 2）
   - `circle`：在第一个顶点画红色圆点标记（半径 4，填充）
   - `rectangle`：绘制标签背景（填充，颜色同边框）
   - `putText`：绘制标签文字 `"类别名 0.95"`（白色，字体 0.5）
   - 标签位置自适应：如果标签超出图片顶部，放到框的下方

**15 种颜色（BGR 顺序）**：
```java
(255,0,0), (0,255,0), (0,0,255), (255,255,0),
(255,0,255), (0,255,255), (128,0,0), (0,128,0),
(0,0,128), (128,128,0), (128,0,128), (0,128,128),
(64,128,0), (192,128,0), (64,0,128)
```

#### 6.2.6 类别名称

```java
// 当前三个模型（ship/plane/car）共享同一套 15 类舰船类别名
// 后续 plane 和 car 有独立模型后可替换为各自的类别名
public static final List<String> CLASS_NAMES = List.of(
    "航空母舰", "驱逐舰", "护卫舰", "巡洋舰", "补给舰",
    "医疗舰", "救援舰", "运输舰", "巡逻舰", "两栖船坞登陆舰",
    "两栖攻击舰", "濒海战斗舰", "指挥舰", "扫布雷舰", "其他"
);
```

#### 6.2.7 核心类 API

```java
// DetectionOptions.java — 不可变的检测参数
public record DetectionOptions(float confidenceThreshold, float iouThreshold) {
    public DetectionOptions(float confidenceThreshold) {
        this(confidenceThreshold, 0.5f);  // 默认 IoU 阈值 0.5
    }
}

// YoloInferenceEngine.java — 线程安全的推理引擎
// 所有字段在构造后不可变，detect() 方法接受 DetectionOptions 参数，
// 不同请求之间不会互相干扰。
public class YoloInferenceEngine implements AutoCloseable {
    private final OrtEnvironment env;        // ONNX Runtime 环境（不可变）
    private final OrtSession session;        // ONNX 推理会话（不可变）
    private final List<String> classNames;   // 类别名称列表（不可变）

    /**
     * @param modelPath   ONNX 模型文件路径，如 "models/best.onnx"
     * @param classNames  类别名称列表
     */
    public YoloInferenceEngine(String modelPath, List<String> classNames);

    /**
     * 对单张图片执行目标检测（预处理→推理→后处理→NMS）。
     * 所有可变参数通过 options 传入，引擎本身是无状态的，线程安全。
     *
     * @param image   输入图片
     * @param options 检测参数（置信度阈值、IoU 阈值等）
     */
    public List<DetResult> detect(BufferedImage image, DetectionOptions options);

    /** 在图片上绘制检测框和标签，返回新图片 */
    public BufferedImage drawDetections(BufferedImage image, List<DetResult> results);

    /** 将图片编码为 JPEG 字节数组 */
    public byte[] encodeToJpeg(BufferedImage image);

    public void close();  // 释放 ONNX Runtime 资源
}

// DetResult.java — 单个检测结果
public class DetResult {
    public int classId;
    public String className;
    public float confidence;
    public float x1, y1, x2, y2, x3, y3, x4, y4;
}

// RotatedBox.java — 旋转框（中间表示）
public class RotatedBox {
    public float cx, cy, w, h, angle;
    public float[] corners;
}
```

#### 6.2.8 关键实现注意事项

**ONNX Runtime 本地库加载**：
ONNX Runtime Java 的 Maven 依赖只提供 Java 类（`ai.onnxruntime.*`），底层 C 库 `libonnxruntime.dylib` 需要单独加载。有两种方式：
- **方式一（推荐）**：启动时加 JVM 参数 `-Djava.library.path=/opt/homebrew/Cellar/onnxruntime/1.26.0_1/lib`
- **方式二**：在代码中显式加载 `System.load("/opt/homebrew/Cellar/onnxruntime/1.26.0_1/lib/libonnxruntime.dylib")`

**ONNX Runtime Java API 使用方式**：
```java
// 创建环境
OrtEnvironment env = OrtEnvironment.getEnvironment();

// 加载模型
OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
OrtSession session = env.createSession(modelPath, opts);

// 构建输入 tensor — 输入必须是 float[] 一维数组，按 CHW 排列
float[] inputData = new float[1 * 3 * 640 * 640];
// ... 填充 inputData ...
OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputData, new long[]{1, 3, 640, 640});

// 执行推理
Map<String, OnnxTensor> inputs = Map.of("images", inputTensor);
OrtSession.Result result = session.run(inputs);

// 获取输出
OnnxTensor outputTensor = (OnnxTensor) result.get("output0").get();
long[] shape = outputTensor.getInfo().getShape();  // [1, N, 7]
float[][][] output = (float[][][]) outputTensor.getValue();
// output[0][i] 是第 i 个检测框的 7 个 float 值
```

**BufferedImage 与 JavaCV Mat 互转**：
```java
// BufferedImage → Mat (BGR)
ByteArrayOutputStream baos = new ByteArrayOutputStream();
ImageIO.write(image, "jpg", baos);
Mat mat = imdecode(new Mat(baos.toByteArray()), IMREAD_COLOR);

// Mat (BGR) → BufferedImage
Mat rgb = new Mat();
cvtColor(bgr, rgb, COLOR_BGR2RGB);
// 然后用 Java2DFrameUtils 或手动构造 BufferedImage
```

**pom.xml 关键依赖**：
```xml
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.26.0</version>
</dependency>
<dependency>
    <groupId>org.bytedeco</groupId>
    <artifactId>javacv-platform</artifactId>
    <version>1.5.10</version>
</dependency>
```

---

### 6.3 yolo-server — 后端推理服务

#### 6.3.1 设计要点

- **一个代码，三种部署**：同一份 `yolo-server.jar`，通过启动参数指定加载哪个模型
- 使用 `@DubboService(group = "${yolo.model-type}")` 注册，三个服务分别以 `group="ship"`、`group="plane"`、`group="car"` 注册到 Nacos
- `YoloInferenceEngine` 作为 Spring Bean 单例，启动时加载模型（98MB 模型只加载一次）
- 引擎是无状态的，并发安全；阈值通过 `DetectionOptions` 参数传入，不存在共享可变状态
- 后端只返回检测坐标列表，**不返回标注图**（标注统一由网关绘制）

#### 6.3.2 核心代码

```java
// YoloServerApplication.java
@SpringBootApplication
@DubboComponentScan
public class YoloServerApplication {
    public static void main(String[] args) {
        System.load("/opt/homebrew/Cellar/onnxruntime/1.26.0_1/lib/libonnxruntime.dylib");
        SpringApplication.run(YoloServerApplication.class, args);
    }

    @Bean
    public YoloInferenceEngine inferenceEngine(
        @Value("${yolo.model-path}") String modelPath) {
        // modelPath 如 "models/best.onnx"，根据文件名判断模型类型
        String modelType = modelPath.contains("ship") ? "ship"
                         : modelPath.contains("plane") ? "plane" : "car";
        return new YoloInferenceEngine(modelPath, YoloClassNames.CLASS_NAMES);
    }
}

// ModelInferenceServiceImpl.java
@DubboService(group = "${yolo.model-type}", version = "1.0.0")
public class ModelInferenceServiceImpl implements ModelInferenceService {
    @Autowired
    private YoloInferenceEngine engine;

    @Override
    public ModelDetectResponse detect(ModelDetectRequest request) {
        long startTime = System.currentTimeMillis();

        // 1. 解码图片
        byte[] imageBytes = request.getImageData().toByteArray();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));

        // 2. 构建检测参数（阈值从请求中获取，每个请求独立，不修改引擎状态）
        float confThreshold = request.getConfThreshold() > 0
            ? request.getConfThreshold() : 0.5f;
        DetectionOptions options = new DetectionOptions(confThreshold);

        // 3. 推理（线程安全，不修改引擎字段）
        List<DetResult> detections = engine.detect(image, options);

        // 4. 构建响应（只返回检测坐标，不返回标注图）
        ModelDetectResponse.Builder builder = ModelDetectResponse.newBuilder()
            .setTotalCount(detections.size())
            .setProcessingTimeMs((int)(System.currentTimeMillis() - startTime));

        for (DetResult det : detections) {
            builder.addDetections(Detection.newBuilder()
                .setClassId(det.classId)
                .setClassName(det.className)
                .setConfidence(det.confidence)
                .setBbox(BoundingBox.newBuilder()
                    .setX1(det.x1).setY1(det.y1)
                    .setX2(det.x2).setY2(det.y2)
                    .setX3(det.x3).setY3(det.y3)
                    .setX4(det.x4).setY4(det.y4)
                    .build())
                .build());
        }

        return builder.build();
    }
}
```

#### 6.3.3 配置文件

```yaml
# application.yml
spring:
  application:
    name: yolo-server

dubbo:
  application:
    name: ${spring.application.name}
  protocol:
    name: tri
    port: 0            # 由启动参数覆盖
  registry:
    address: nacos://127.0.0.1:8848
    register-mode: instance
  scan:
    base-packages: com.yolo.server

yolo:
  model-path: ""       # 由启动参数覆盖
  model-type: ship     # 由启动参数覆盖：ship / plane / car
```

#### 6.3.4 启动方式

```bash
# 舰船检测服务（group="ship"，端口 8001）
java -Djava.library.path=/opt/homebrew/Cellar/onnxruntime/1.26.0_1/lib \
     -jar yolo-server.jar \
     --yolo.model-path=models/best.onnx \
     --yolo.model-type=ship \
     --dubbo.protocol.port=8001

# 飞机检测服务（group="plane"，端口 8002）
java -Djava.library.path=/opt/homebrew/Cellar/onnxruntime/1.26.0_1/lib \
     -jar yolo-server.jar \
     --yolo.model-path=models/best-plane.onnx \
     --yolo.model-type=plane \
     --dubbo.protocol.port=8002

# 车辆检测服务（group="car"，端口 8003）
java -Djava.library.path=/opt/homebrew/Cellar/onnxruntime/1.26.0_1/lib \
     -jar yolo-server.jar \
     --yolo.model-path=models/best-car.onnx \
     --yolo.model-type=car \
     --dubbo.protocol.port=8003
```

---

### 6.4 yolo-gateway — 聚合网关

#### 6.4.1 设计要点

- **无状态网关**：不加载任何 ONNX 模型，只做 RPC 转发和结果聚合
- **双重角色**：对上游暴露 `YoloAggregationService`，对下游消费 `ModelInferenceService`（三个后端用 group 区分）
- **路由在服务发现阶段完成**：`@DubboReference(group = "ship")` 确保请求精确路由到 ship 后端，不依赖请求里的字符串
- **并行扇出**：用 Java 21 虚拟线程同时调用三个后端，总耗时 = 最慢那个后端的耗时
- **优雅降级**：某个后端挂了，不影响其他路，返回部分结果
- **网关负责标注**：后端只返回检测坐标，网关在原始图片上统一绘制所有三路的框

#### 6.4.2 核心代码

```java
// YoloGatewayApplication.java
@SpringBootApplication
@DubboComponentScan
public class YoloGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(YoloGatewayApplication.class, args);
    }

    @Bean
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

// YoloAggregationServiceImpl.java
@DubboService(version = "1.0.0")
public class YoloAggregationServiceImpl implements YoloAggregationService {

    // ★ 三个后端通过不同的 Dubbo group 精确引用
    // 不会出现 "ship 的请求被路由到 plane 实例" 的问题
    @DubboReference(group = "ship", version = "1.0.0", check = false)
    private ModelInferenceService shipService;

    @DubboReference(group = "plane", version = "1.0.0", check = false)
    private ModelInferenceService planeService;

    @DubboReference(group = "car", version = "1.0.0", check = false)
    private ModelInferenceService carService;

    @Autowired
    private ExecutorService executor;

    @Override
    public AggregateDetectResponse detect(AggregateDetectRequest request) {
        long startTime = System.currentTimeMillis();

        // ===== 第1步：并行扇出到三个后端 =====
        float confThreshold = request.getConfThreshold() > 0
            ? request.getConfThreshold() : 0.5f;

        var shipFuture = invokeModel("ship",
            () -> shipService.detect(ModelDetectRequest.newBuilder()
                .setImageData(request.getImageData())
                .setConfThreshold(confThreshold)
                .setDataSource(request.getDataSource())
                .build()));

        var planeFuture = invokeModel("plane",
            () -> planeService.detect(ModelDetectRequest.newBuilder()
                .setImageData(request.getImageData())
                .setConfThreshold(confThreshold)
                .setDataSource(request.getDataSource())
                .build()));

        var carFuture = invokeModel("car",
            () -> carService.detect(ModelDetectRequest.newBuilder()
                .setImageData(request.getImageData())
                .setConfThreshold(confThreshold)
                .setDataSource(request.getDataSource())
                .build()));

        // ===== 第2步：阻塞等待全部完成 =====
        // ★ 关键：必须调用 join()，allOf() 只创建 Future，不会阻塞
        CompletableFuture.allOf(shipFuture, planeFuture, carFuture).join();

        // ===== 第3步：获取结果（超时或失败 = null） =====
        ModelResult shipResult = shipFuture.getNow(null);
        ModelResult planeResult = planeFuture.getNow(null);
        ModelResult carResult = carFuture.getNow(null);

        // ===== 第4步：聚合结果 =====
        List<Detection> allDetections = new ArrayList<>();
        mergeResult(shipResult, "[ship]", allDetections);
        mergeResult(planeResult, "[plane]", allDetections);
        mergeResult(carResult, "[car]", allDetections);

        // ===== 第5步：在原始图片上统一绘制标注 =====
        byte[] annotatedBytes = drawAllDetections(
            request.getImageData().toByteArray(), allDetections);

        return AggregateDetectResponse.newBuilder()
            .setTotalCount(allDetections.size())
            .addAllDetections(allDetections)
            .setAnnotatedImage(ByteString.copyFrom(annotatedBytes))
            .setProcessingTimeMs((int)(System.currentTimeMillis() - startTime))
            .build();
    }

    /**
     * 封装单个模型调用，包含超时和异常处理。
     * 每个模型调用独立超时（5 秒），失败不阻塞其他路。
     */
    private CompletableFuture<ModelResult> invokeModel(
            String modelType, Supplier<ModelDetectResponse> supplier) {
        return CompletableFuture
            .supplyAsync(supplier, executor)
            .thenApply(response -> ModelResult.success(modelType, response))
            .completeOnTimeout(
                ModelResult.timeout(modelType), 5, TimeUnit.SECONDS)
            .exceptionally(ex ->
                ModelResult.failed(modelType, ex.getMessage()));
    }

    /** 合并单路结果，类名加前缀区分来源 */
    private void mergeResult(ModelResult result, String prefix,
                              List<Detection> allDetections) {
        if (result == null || !result.success()) {
            if (result != null) {
                log.warn("{} model failed: {}", result.modelType(), result.error());
            }
            return;
        }
        for (Detection d : result.response().getDetectionsList()) {
            allDetections.add(d.toBuilder()
                .setClassName(prefix + " " + d.getClassName()).build());
        }
    }

    /** 模型调用结果封装 */
    private record ModelResult(
        String modelType,
        ModelDetectResponse response,
        boolean success,
        String error
    ) {
        static ModelResult success(String type, ModelDetectResponse resp) {
            return new ModelResult(type, resp, true, null);
        }
        static ModelResult timeout(String type) {
            return new ModelResult(type, null, false, "timeout");
        }
        static ModelResult failed(String type, String err) {
            return new ModelResult(type, null, false, err);
        }
    }

    /** 在原始图片上绘制所有检测框 */
    private byte[] drawAllDetections(byte[] rawImageBytes, List<Detection> detections) {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(rawImageBytes));
        // ... 将 Detection 转为 DetResult，调用 YoloInferenceEngine.drawDetections ...
    }
}
```

#### 6.4.3 配置文件

```yaml
# application.yml
spring:
  application:
    name: yolo-gateway

dubbo:
  application:
    name: ${spring.application.name}
  protocol:
    name: tri
    port: 9000
  registry:
    address: nacos://127.0.0.1:8848
    register-mode: instance
  consumer:
    timeout: 5000
    retries: 0
    check: false
  scan:
    base-packages: com.yolo.gateway
```

#### 6.4.4 为什么用虚拟线程而不是线程池

网关的主要工作是：发三个 RPC 请求 → 等待网络响应 → 聚合。这是典型的 IO 密集型场景。Java 21 的虚拟线程创建成本极低（约 1KB 栈空间），不需要池化，用完即弃。代码比线程池 `Future` 模式更简洁，也不需要调优 `corePoolSize`/`maxPoolSize`。

#### 6.4.5 为什么每个模型调用需要独立超时

`invokeModel()` 中使用了 `completeOnTimeout(5, TimeUnit.SECONDS)`，这样：
- 如果 ship 后端 5 秒内没响应，ship 的 Future 得到 `ModelResult.timeout("ship")`，不影响 plane 和 car
- `allOf().join()` 不会被某个慢后端永久阻塞
- 网关最终返回 plane + car 的结果，ship 的失败只记一条 warning 日志

---

### 6.5 yolo-web-api — REST API 层

#### 6.5.1 设计要点

这是一个薄层，只做协议转换。浏览器不能直接调 Dubbo3 协议，需要 HTTP REST 接口。

- 接收前端 `multipart/form-data` 上传的图片
- 将请求转为 Protobuf 格式，通过 Dubbo3 调用网关
- 将 Protobuf 响应转为 JSON 返回
- 标注图以 Base64 编码内嵌在 JSON 中（前端可直接用于 `<img src>`）

#### 6.5.2 API 接口

**POST /api/detect** — 图片检测

请求（multipart/form-data）：
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| image | File | 是 | 图片文件（JPEG/PNG） |
| dataSource | String | 否 | 数据源，默认 "satellite" |
| confThreshold | Float | 否 | 置信度阈值，默认 0.5 |

响应（JSON）：
```json
{
  "totalCount": 12,
  "processingTimeMs": 523,
  "annotatedImage": "data:image/jpeg;base64,/9j/4AAQSkZJRg...",
  "detections": [
    {
      "classId": 0,
      "className": "[ship] 航空母舰",
      "confidence": 0.92,
      "bbox": {
        "x1": 100.5, "y1": 200.3,
        "x2": 150.2, "y2": 190.8,
        "x3": 160.0, "y3": 250.1,
        "x4": 110.3, "y4": 260.5
      }
    }
  ]
}
```

**GET /api/health** — 系统健康检查

响应（JSON）：
```json
{
  "status": "UP",
  "gateway": "UP",
  "timestamp": "2026-07-11T14:32:00"
}
```

#### 6.5.3 核心代码

```java
// DetectController.java
@RestController
@RequestMapping("/api")
public class DetectController {

    @DubboReference(version = "1.0.0", check = false)
    private YoloAggregationService yoloGateway;  // ★ 引用网关聚合接口

    @PostMapping("/detect")
    public ResponseEntity<Map<String, Object>> detect(
        @RequestParam("image") MultipartFile image,
        @RequestParam(defaultValue = "satellite") String dataSource,
        @RequestParam(defaultValue = "0.5") float confThreshold) throws Exception {

        // 1. MultipartFile → Protobuf
        AggregateDetectRequest request = AggregateDetectRequest.newBuilder()
            .setImageData(ByteString.copyFrom(image.getBytes()))
            .setDataSource(dataSource)
            .setConfThreshold(confThreshold)
            .build();

        // 2. 调用 Dubbo3 网关
        AggregateDetectResponse response = yoloGateway.detect(request);

        // 3. Protobuf → JSON
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", response.getTotalCount());
        result.put("processingTimeMs", response.getProcessingTimeMs());
        result.put("annotatedImage", "data:image/jpeg;base64,"
            + Base64.getEncoder().encodeToString(response.getAnnotatedImage().toByteArray()));

        List<Map<String, Object>> detections = new ArrayList<>();
        for (Detection d : response.getDetectionsList()) {
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("classId", d.getClassId());
            det.put("className", d.getClassName());
            det.put("confidence", d.getConfidence());
            det.put("bbox", Map.of(
                "x1", d.getBbox().getX1(), "y1", d.getBbox().getY1(),
                "x2", d.getBbox().getX2(), "y2", d.getBbox().getY2(),
                "x3", d.getBbox().getX3(), "y3", d.getBbox().getY3(),
                "x4", d.getBbox().getX4(), "y4", d.getBbox().getY4()
            ));
            detections.add(det);
        }
        result.put("detections", detections);

        return ResponseEntity.ok(result);
    }
}

// HealthController.java
@RestController
@RequestMapping("/api")
public class HealthController {
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "gateway", "UP",
            "timestamp", Instant.now().toString()
        ));
    }
}

// CorsConfig.java
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:5173")
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("*");
    }
}
```

#### 6.5.4 配置文件

```yaml
spring:
  application:
    name: yolo-web-api
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 100MB

server:
  port: 8080

dubbo:
  application:
    name: ${spring.application.name}
  registry:
    address: nacos://127.0.0.1:8848
  consumer:
    timeout: 30000    # 推理可能耗时较长，30 秒超时
    check: false
```

---

### 6.6 yolo-client — 命令行测试客户端

这是一个简单的命令行工具，用于在不启动前端的情况下快速测试整个链路。

```java
@SpringBootApplication
@DubboComponentScan
public class YoloClient implements CommandLineRunner {

    @DubboReference(version = "1.0.0", check = false)
    private YoloAggregationService gatewayService;  // ★ 调用网关聚合接口

    public static void main(String[] args) {
        SpringApplication.run(YoloClient.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: java -jar yolo-client.jar <图片路径> [输出路径] [阈值]");
            return;
        }

        String imagePath = args[0];
        String outputPath = args.length > 1 ? args[1] : "output.jpg";
        float confThreshold = args.length > 2 ? Float.parseFloat(args[2]) : 0.5f;

        byte[] imageBytes = Files.readAllBytes(Path.of(imagePath));

        AggregateDetectRequest request = AggregateDetectRequest.newBuilder()
            .setImageData(ByteString.copyFrom(imageBytes))
            .setConfThreshold(confThreshold)
            .setDataSource("satellite")
            .build();

        System.out.println("正在检测: " + imagePath);
        long start = System.currentTimeMillis();
        AggregateDetectResponse response = gatewayService.detect(request);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("检测到 " + response.getTotalCount() + " 个目标，耗时 " + elapsed + "ms");
        for (Detection d : response.getDetectionsList()) {
            System.out.printf("  %s: %.2f%n", d.getClassName(), d.getConfidence());
        }

        if (!response.getAnnotatedImage().isEmpty()) {
            Files.write(Path.of(outputPath), response.getAnnotatedImage().toByteArray());
            System.out.println("标注图已保存到: " + outputPath);
        }

        System.exit(0);
    }
}
```

---

### 6.7 yolo-web — Vue 3 前端

#### 6.7.1 技术选型

| 用途 | 选型 | 说明 |
|------|------|------|
| 框架 | Vue 3 (Composition API) | 使用 `<script setup>` 语法 |
| 构建工具 | Vite 5 | 开发服务器 + 打包 |
| 类型系统 | TypeScript | 类型安全 |
| UI 组件库 | Element Plus | 中文文档完善，上手快 |
| HTTP 请求 | Axios | 封装 API 调用 |
| 路由 | Vue Router 4 | 两个页面：检测页 + 状态页 |

#### 6.7.2 页面设计

**页面一：检测主页面 (DetectView.vue)**

页面分为上下两部分：
- 上半部分：操作区（图片上传、参数设置、检测按钮）
- 下半部分：结果区（标注图 + 检测结果表格）

布局采用 Element Plus 的 `el-row`/`el-col` 栅格系统，左右分栏。

**交互流程**：
1. 用户拖拽图片到上传区（或点击选择文件）→ 前端本地预览
2. 选择数据源（卫星遥感 / 无人机图像）
3. 拖动滑块调整置信度阈值（0.1 ~ 1.0）
4. 点击"开始检测"按钮 → 按钮变为 loading 状态
5. 前端通过 Axios 发送 POST /api/detect（multipart/form-data）
6. 收到响应 → 左侧显示标注图（Base64 直接渲染），右侧显示检测结果表格
7. 检测历史保存在浏览器 localStorage 中

**页面二：系统状态页面 (StatusView.vue)**

- 三个后端服务的健康状态卡片（绿色 = 正常，红色 = 异常）
- 最近检测记录表格（从 localStorage 读取历史记录）

#### 6.7.3 关键代码

```typescript
// src/api/detect.ts
import axios from 'axios'

const api = axios.create({
  baseURL: 'http://localhost:8080',  // 后端 API 地址
  timeout: 60000,                     // 60 秒超时（推理可能较慢）
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
}

export interface DetectResult {
  totalCount: number
  processingTimeMs: number
  annotatedImage: string       // "data:image/jpeg;base64,..."
  detections: Detection[]
}

export async function detectImage(
  file: File,
  dataSource: string = 'satellite',
  confThreshold: number = 0.5
): Promise<DetectResult> {
  const formData = new FormData()
  formData.append('image', file)
  formData.append('dataSource', dataSource)
  formData.append('confThreshold', String(confThreshold))
  const { data } = await api.post<DetectResult>('/api/detect', formData)
  return data
}

export async function getHealth(): Promise<{ status: string }> {
  const { data } = await api.get('/api/health')
  return data
}
```

```typescript
// src/router/index.ts
import { createRouter, createWebHistory } from 'vue-router'
import DetectView from '../views/DetectView.vue'
import StatusView from '../views/StatusView.vue'

const routes = [
  { path: '/', name: 'Detect', component: DetectView },
  { path: '/status', name: 'Status', component: StatusView },
]

export default createRouter({
  history: createWebHistory(),
  routes,
})
```

```json
// package.json（关键依赖）
{
  "dependencies": {
    "vue": "^3.5.0",
    "vue-router": "^4.4.0",
    "element-plus": "^2.9.0",
    "axios": "^1.7.0",
    "@element-plus/icons-vue": "^2.3.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.1.0",
    "typescript": "^5.5.0",
    "vite": "^5.4.0",
    "vue-tsc": "^2.1.0"
  }
}
```

```typescript
// vite.config.ts — 关键配置
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 开发时代理 /api 到后端，避免跨域问题
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
```

---

## 七、父 POM 完整配置

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.yolo</groupId>
    <artifactId>yolo-dist</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <modules>
        <module>yolo-proto</module>
        <module>yolo-inference</module>
        <module>yolo-server</module>
        <module>yolo-gateway</module>
        <module>yolo-client</module>
        <module>yolo-web-api</module>
    </modules>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.1</version>
        <relativePath/>
    </parent>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <protobuf.version>3.25.5</protobuf.version>
        <dubbo.version>3.3.2</dubbo.version>
        <onnxruntime.version>1.26.0</onnxruntime.version>
        <javacv.version>1.5.10</javacv.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Dubbo3 BOM -->
            <dependency>
                <groupId>org.apache.dubbo</groupId>
                <artifactId>dubbo-bom</artifactId>
                <version>${dubbo.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <!-- 内部模块 -->
            <dependency>
                <groupId>com.yolo</groupId>
                <artifactId>yolo-proto</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.yolo</groupId>
                <artifactId>yolo-inference</artifactId>
                <version>${project.version}</version>
            </dependency>
            <!-- Protobuf -->
            <dependency>
                <groupId>com.google.protobuf</groupId>
                <artifactId>protobuf-java</artifactId>
                <version>${protobuf.version}</version>
            </dependency>
            <dependency>
                <groupId>com.google.protobuf</groupId>
                <artifactId>protobuf-java-util</artifactId>
                <version>${protobuf.version}</version>
            </dependency>
            <!-- ONNX Runtime -->
            <dependency>
                <groupId>com.microsoft.onnxruntime</groupId>
                <artifactId>onnxruntime</artifactId>
                <version>${onnxruntime.version}</version>
            </dependency>
            <!-- JavaCV -->
            <dependency>
                <groupId>org.bytedeco</groupId>
                <artifactId>javacv-platform</artifactId>
                <version>${javacv.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <version>${project.parent.version}</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

---

## 八、各模块 Maven 依赖一览

| 模块 | 需要的依赖 |
|------|-----------|
| **yolo-proto** | protobuf-java, protobuf-java-util, protobuf-maven-plugin |
| **yolo-inference** | onnxruntime, javacv-platform, yolo-proto |
| **yolo-server** | spring-boot-starter, dubbo-spring-boot-starter, dubbo-nacos-spring-boot-starter, yolo-proto, yolo-inference |
| **yolo-gateway** | spring-boot-starter, dubbo-spring-boot-starter, dubbo-nacos-spring-boot-starter, yolo-proto, yolo-inference（仅用于标注绘图） |
| **yolo-web-api** | spring-boot-starter-web, dubbo-spring-boot-starter, dubbo-nacos-spring-boot-starter, yolo-proto |
| **yolo-client** | spring-boot-starter, dubbo-spring-boot-starter, dubbo-nacos-spring-boot-starter, yolo-proto |

---

## 九、关键设计决策

### 9.1 为什么用 Dubbo group 区分三个后端，而不是共用接口 + model_type 字符串

三个后端服务（ship/plane/car）如果都暴露同一个接口 `YoloDetectService`，Dubbo 无法仅凭 Java 字段名区分它们。`@DubboReference private YoloDetectService shipService` 在运行时和 `planeService` 引用的是同一个服务名，三个引用都可能被负载均衡到任意模型实例。另外 yolo-gateway 自己也暴露同一个接口，yolo-web-api 有可能绕过网关直接调用模型服务。

**解决方案**：拆成两个接口。`ModelInferenceService` 由三个后端实现，通过 `@DubboService(group = "ship|plane|car")` 注册。网关用 `@DubboReference(group = "xxx")` 精确引用。`YoloAggregationService` 由网关暴露，客户端只和这个接口打交道。路由在 Dubbo 服务发现阶段完成，不依赖请求里的字符串参数。

### 9.2 为什么用普通 Java 接口而非 Dubbo Triple IDL 代码生成

`protobuf-maven-plugin` 只生成消息类型（`ModelDetectRequest` 等），不会生成 Dubbo Triple 的服务 Stub。如果用 Triple IDL 方式，需要额外配置 `dubbo-maven-plugin` 或手写 `xxxDubbo.java` 包装类，文档和踩坑成本高。

**第一版选择方案 A**：定义普通 Java 接口（`ModelInferenceService`、`YoloAggregationService`），方法参数和返回值使用 Protobuf 生成的消息类型。Dubbo3 Triple 协议会自动检测到 Protobuf 消息并使用 Protobuf 序列化。不影响项目技术含量，代码更可控。

### 9.3 为什么 CompletableFuture.allOf() 后必须显式 join() 或 get()

`allOf()` 返回一个新的 `CompletableFuture<Void>`，它只是"当所有输入的 Future 都完成时，这个 Future 完成"。但 `allOf()` 本身不会阻塞当前线程。如果不调用 `.join()` 或 `.get()`，后续的 `getNow(null)` 大概率立即返回 null。

另外，`orTimeout()` 设置的是一个"自动超时完成"，但如果 `allOf()` 已经正常完成，`orTimeout()` 返回的还是正常完成的结果。更稳妥的做法是对每个模型调用单独设置 `completeOnTimeout()`，这样单个模型超时不影响其他路。

### 9.4 为什么推理引擎不能有可变状态（setConfThreshold）

`YoloInferenceEngine` 是 Spring 单例 Bean，所有并发请求共享同一个实例。如果通过 `setConfThreshold()` 修改引擎内部的 `confThreshold` 字段，请求 A 设置 0.2 后，请求 B 可能立即覆盖为 0.8，导致请求 A 用错了阈值。

**解决方案**：去掉 `setConfThreshold()` 和所有可变字段。阈值改为 `DetectionOptions` 不可变参数，通过 `detect(image, options)` 方法传入。引擎构造后所有字段不可变，天然线程安全。

### 9.5 为什么直接调用 ONNX Runtime Java API，而不是用 DJL

DJL（Deep Java Library）是 AWS 开源的 Java 深度学习库，封装了 ONNX Runtime。但 DJL 内置的 `Translator` 只支持标准 YOLO 输出格式（轴对齐矩形框）。本项目的模型输出 `[cx, cy, w, h, angle_rad, cls_id, raw_conf]` 是非标准格式，DJL 无法直接解析。

直接使用 ONNX Runtime Java API 的好处：对模型输入输出的控制精确，所有预处理和后处理逻辑自己写，Maven 依赖只有一个。

### 9.6 为什么用 JavaCV 而不是纯 Java 图像处理库

旋转框 NMS 的核心是计算两个旋转矩形的真实交集面积。纯 Java 实现需要手写 Sutherland-Hodgman 裁剪算法 + Shoelace 面积公式，代码量大且容易出错。JavaCV 直接调用 OpenCV 的 `cv::rotatedRectangleIntersection` + `cv::contourArea`，性能和正确性都有保障。

### 9.7 网关自己负责标注绘图

后端只返回检测结果列表（坐标 + 类别 + 置信度），不返回标注图。网关在原始图片上统一绘制所有三路的检测框。避免了"三张标注图怎么拼在一起"的问题，也减少了网络传输量。

### 9.8 一个后端进程加载一个模型

当前版本一个 yolo-server 实例只加载一个 ONNX 模型（98MB）。后续扩展到无人机图像时，可以让一个实例加载两个模型（satellite + drone），根据请求中的 `data_source` 字段内部路由。

---

## 十、后续扩展

| 扩展方向 | 需要改动的模块 |
|---------|--------------|
| 无人机图像检测 | yolo-server 增加 drone 模型加载 + 内部路由；proto 已预留 `data_source` 字段 |
| 视频流检测 | yolo-proto 增加 `StreamDetect` RPC；yolo-server 增加流式处理；新增 yolo-stream-client |
| 水平扩展 | 不改代码，启动多个 yolo-server 实例，Dubbo3 自动负载均衡 |
| 数据库存储 | 在 yolo-web-api 或 yolo-gateway 中增加 Repository 层 |
| Docker 部署 | 增加 Dockerfile + docker-compose.yml |

---

## 十一、启动流程

### 11.1 安装 Nacos

```bash
# 下载 Nacos 2.4.x
wget https://github.com/alibaba/nacos/releases/download/2.4.2/nacos-server-2.4.2.zip
unzip nacos-server-2.4.2.zip

# 单机模式启动
cd nacos && sh bin/startup.sh -m standalone

# 验证：访问 http://localhost:8848/nacos，默认用户名密码 nacos/nacos
```

### 11.2 编译项目

```bash
cd /Users/admin/Desktop/0605/yolo-dist
mvn clean package -DskipTests
```

### 11.3 启动服务（按顺序）

```bash
ONNX_LIB=/opt/homebrew/Cellar/onnxruntime/1.26.0_1/lib
JAVA_OPTS="-Djava.library.path=$ONNX_LIB -Xmx2g"

# 1. 启动三个后端推理服务
java $JAVA_OPTS -jar yolo-server/target/yolo-server.jar \
  --yolo.model-path=models/best.onnx --dubbo.protocol.port=8001 &

java $JAVA_OPTS -jar yolo-server/target/yolo-server.jar \
  --yolo.model-path=models/best-plane.onnx --dubbo.protocol.port=8002 &

java $JAVA_OPTS -jar yolo-server/target/yolo-server.jar \
  --yolo.model-path=models/best-car.onnx --dubbo.protocol.port=8003 &

sleep 5  # 等后端启动完毕

# 2. 启动网关
java -jar yolo-gateway/target/yolo-gateway.jar --dubbo.protocol.port=9000 &

sleep 3

# 3. 启动 Web API
java -jar yolo-web-api/target/yolo-web-api.jar --server.port=8080 &

# 4. 启动前端开发服务器
cd yolo-web && npm install && npm run dev
```

### 11.4 验证

```bash
# 命令行测试
java -jar yolo-client/target/yolo-client.jar test.jpg output.jpg 0.5

# 浏览器访问
# 前端：http://localhost:5173
# Nacos 控制台：http://localhost:8848/nacos（查看服务注册情况）
```

---

## 十二、验证方案

| 序号 | 验证项 | 操作 | 预期结果 |
|------|--------|------|---------|
| 1 | 推理引擎 | 对 yolo-inference 写单元测试 | 输出格式正确（7列），sigmoid 后置信度在 [0,1]，NMS 后无重复框 |
| 2 | 单后端 | 启动 yolo-server，yolo-client 直连 `tri://127.0.0.1:8001` | 返回检测结果 + 标注图 |
| 3 | 网关聚合 | 启动 3 个后端 + 网关，yolo-client 调用 `tri://127.0.0.1:9000` | 响应中包含 `[ship]`/`[plane]`/`[car]` 前缀的检测结果 |
| 4 | 前端全链路 | 启动全部服务，浏览器访问 `localhost:5173`，上传图片 | 标注图正确渲染，检测结果表格显示完整 |
| 5 | 故障降级 | 关掉 car 后端，再次检测 | 网关仍返回 ship + plane 的结果，不报错 |
| 6 | 水平扩展 | 启动 2 个 ship 实例（端口 8001, 8004） | 两次请求可能路由到不同实例 |

---

## 十三、常见问题

**Q: ONNX Runtime 加载模型时报 `UnsatisfiedLinkError`**
A: 检查 JVM 参数是否设置了 `-Djava.library.path`，或者是否在 main 方法中调用了 `System.load()`。

**Q: Dubbo3 服务注册失败，Nacos 控制台看不到服务**
A: 检查 `application.yml` 中 `dubbo.registry.address` 是否正确。确认 Nacos 已启动且端口为 8848。

**Q: JavaCV 报 `NoClassDefFoundError`**
A: JavaCV 的 `javacv-platform` 依赖会下载所有平台的本地库（约 1GB），首次编译需要较长时间。确认网络通畅，Maven 能正常下载依赖。

**Q: 推理结果为空（totalCount = 0）**
A: 可能原因：① 图片中确实没有目标；② 置信度阈值设得太高，尝试降低到 0.1；③ 模型路径不对，检查 `--yolo.model-path` 参数。