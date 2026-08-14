# YOLO-RPC 项目从零学习指南（代码详解版）

> 这份文档是给 **Java 小白**（连 Spring Boot 都不懂）写的。
> 和普通教程最大的区别是：**每个概念都直接贴上你项目里的真实代码，逐行讲解**。
> 你不需要去项目里找例子——例子都在下面了。
>
> 建议按顺序学习，每阶段都完成"动手练习"再进入下一阶段。

---

## 📌 这个项目到底做了什么？

**一句话**：用户上传一张卫星图片 → 系统用三个 AI 模型（船/飞机/车）检测图中的目标 → 在三维地球上标出来。

```
前端 Vue (5173)                    ← 浏览器界面
   ↓ HTTP 请求
Web API (8080)                     ← 接收网页请求（本文件叫 DetectController）
   ↓ Dubbo RPC
网关 (9000)                        ← 调度中心，叫三个模型干活
   ↓ Dubbo RPC
三个模型服务 (8001/8002/8003)       ← 真正做 AI 检测
   ↓
Nacos (8848)                       ← "通讯录"，让大家互相找到对方
```

---

## 阶段 0：学习方法论

### 学习原则
1. **先跑起来**：用 `start.bat` 启动项目，看到效果，再学代码
2. **顺着数据流学**：从"图片进来"顺着代码走一遍，而不是乱翻文件
3. **一次只学一个概念**
4. **报错不可怕**：读报错信息是核心竞争力

---

## 阶段 1：Java 基础进阶（1-2 周）

### 1.1 什么是"类"和"对象"？（大白话）

- **类** = 图纸（定义了对象长什么样）
- **对象** = 按图纸造出来的实物

### 1.2 用你项目的代码理解「类」

打开 `yolo-web-api/DetectController.java` 第 12-14 行：

```java
@RestController                                  // 注解：告诉 Spring "我是控制器"
@RequestMapping("/api")                          // 注解：我处理的网址以 /api 开头
public class DetectController {                  // 定义一个类，名字叫 DetectController
    // 类里面可以放"字段"（变量）和"方法"（函数）
}
```

**逐行讲**：
- `public class DetectController` —— 定义了一个**公开的类**，名字叫 `DetectController`。类名首字母大写（Java 规范）
- `{ }` 花括号里是类的内容

### 1.3 什么是「方法」（函数）？用你的代码理解

同一个文件第 19-28 行：

```java
@PostMapping("/detect")                                        // 注解：这个方法处理 POST /detect 请求
public ResponseEntity<Map<String, Object>> detect(             // 方法定义开始
        @RequestParam("image") MultipartFile image,            // 参数1：图片文件
        @RequestParam(defaultValue = "0.5") float confThreshold) // 参数2：置信度阈值，默认0.5
        throws Exception {                                     // 这个方法可能出错
    // 方法体：这里写具体做什么
    return ResponseEntity.ok(result);                          // 返回结果
}
```

**逐行讲**：
- `public` —— 公开的，别人能调用
- `ResponseEntity<...>` —— **返回值类型**（返回 HTTP 响应）
- `detect(...)` —— **方法名**
- `( )` 里是**参数列表**（方法需要什么输入）
- `throws Exception` —— 声明"我这个方法可能出错"
- `{ }` 里是**方法体**（具体逻辑）

### 1.4 什么是「接口」（interface）？用你的代码理解

打开 `yolo-proto/src/main/java/com/yolo/proto/ModelInferenceService.java`（整个文件）：

```java
package com.yolo.proto;

public interface ModelInferenceService {
    ModelDetectResponse detect(ModelDetectRequest request);
}
```

**逐行讲**：
- `interface` —— 定义接口，接口 = **只写"要做什么"，不写"怎么做"**
- `ModelDetectResponse detect(ModelDetectRequest request);` —— 声明一个方法，**只有签名，没有方法体**（没有 `{}`）

**为什么要接口**？因为**实现可以不同**。你的项目里，三个模型服务（ship/plane/car）都"实现"这个接口，但各自用不同的模型：

```java
// 在 ModelInferenceServiceImpl.java 第 17 行：
public class ModelInferenceServiceImpl implements ModelInferenceService {
    // implements = "我实现这个接口"
    // 这里真正写了 detect() 怎么干活（用 ONNX 模型检测）
}
```

### 1.5 什么是「泛型」？用你的代码理解

看 `DetectController.java` 第 57 行：

```java
Map<String, Object> result = new LinkedHashMap<>();
```

**逐行讲**：
- `Map<K, V>` —— 键值对集合（像字典：一个"键"对应一个"值"）
- `<String, Object>` —— **泛型**，声明"键是 String 类型，值是 Object 类型"
- `new LinkedHashMap<>()` —— 创建具体实现，`<>` 表示"类型和前面一样"
- `result.put("totalCount", 5)` —— 往 map 里放一个键值对："totalCount" → 5

再看 `List<Map<String, Object>> detections = new ArrayList<>();`（第 63 行）：
- `List<X>` —— 一个 X 类型的列表（动态数组）
- 这里是"一个装着 Map 的列表"

### 1.6 什么是 Lambda 表达式？用你的代码理解

打开 `yolo-gateway/YoloAggregationServiceImpl.java`，找到类似这段（invokeModel 方法内）：

```java
CompletableFuture
    .supplyAsync(() -> shipService.detect(modelRequest))   // ← 这是 Lambda
    .completeOnTimeout(...)
```

**逐行讲**：
- `() -> shipService.detect(modelRequest)` —— 这是一个 Lambda（匿名函数）
- `()` —— 参数列表（空）
- `->` —— "转到"
- `shipService.detect(modelRequest)` —— 函数体（要执行的代码）
- **大白话**：相当于"把 `shipService.detect(modelRequest)` 这段代码打包，交给另一个线程去执行"

> 注意：如果 Lambda 只有一行，可以省略 `return` 和 `{}`。

### 1.7 什么是「异常处理」？用你的代码理解

打开 `ModelInferenceServiceImpl.java` 第 25-64 行（精简）：

```java
public ModelDetectResponse detect(ModelDetectRequest request) {
    try {                                        // try：尝试执行（可能出错的代码放这）
        byte[] imageBytes = request.getImageData().toByteArray();
        BufferedImage image = ImageUtils.loadImage(imageBytes);
        List<DetResult> detections = engine.detect(image, options);
        // ... 构建返回结果
        return builder.build();
    } catch (Exception e) {                      // catch：如果出错了，执行这里
        log.error("Model inference failed", e);  // 打印错误日志
        return ModelDetectResponse.newBuilder()  // 返回一个空结果（不至于崩溃）
            .setTotalCount(0)
            .build();
    }
}
```

**逐行讲**：
- `try { }` —— 把"可能出错"的代码放这里
- `catch (Exception e)` —— 如果 try 里出错，跳到 catch，`e` 是错误信息
- **为什么这样写**：如果图片损坏导致加载失败，程序不会崩溃，而是返回"0 个目标"并记日志

---

## 阶段 2：Maven 构建工具（3-5 天）

### 2.1 Maven 是干什么的？（大白话）

Maven = **项目的"管家"**，负责三件事：
1. **依赖管理**：你要用别人写好的库，Maven 帮你下载
2. **编译打包**：把你的代码变成能运行的 jar 包
3. **多模块管理**：一个项目拆多个子项目，Maven 统一管

### 2.2 用你的 pom.xml 理解「依赖」

打开根目录 `pom.xml`，看这一段：

```xml
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>   <!-- 库的"公司名" -->
    <artifactId>onnxruntime</artifactId>            <!-- 库的"名字" -->
    <version>${onnxruntime.version}</version>       <!-- 库的"版本" -->
</dependency>
```

**逐行讲**：
- `<groupId>` —— 谁发布的（组织名）
- `<artifactId>` —— 库叫什么
- `<version>` —— 哪个版本
- 这组信息就是 Maven 找到这个库的"身份证"
- `${onnxruntime.version}` 是一个变量，它的值在 `<properties>` 里定义（比如 `1.26.0`）

再看 pom.xml 里的多模块：

```xml
<modules>
    <module>yolo-proto</module>       <!-- 模块1：定义接口和数据格式 -->
    <module>yolo-inference</module>   <!-- 模块2：AI 推理引擎 -->
    <module>yolo-server</module>      <!-- 模块3：模型服务 -->
    <module>yolo-gateway</module>     <!-- 模块4：网关 -->
    <module>yolo-client</module>      <!-- 模块5：命令行测试 -->
    <module>yolo-web-api</module>     <!-- 模块6：网页接口 -->
</modules>
```

### 2.3 模块之间怎么依赖？看 yolo-server/pom.xml

```xml
<dependency>
    <groupId>com.yolo</groupId>          <!-- 是自己项目的 -->
    <artifactId>yolo-inference</artifactId> <!-- 依赖推理引擎模块 -->
</dependency>
```

**意义**：`yolo-server`（模型服务）要用 `yolo-inference`（推理引擎）里的类，所以声明依赖它。

### 动手练习
1. IDEA 右侧有 Maven 面板，展开看 6 个模块
2. 运行 `mvn clean package -DskipTests`，看它如何按顺序编译所有模块

---

## 阶段 3：Spring Boot 入门（1-2 周）⭐ 最重要

### 3.1 Spring Boot 解决了什么？（必须先懂这个）

**没有 Spring Boot 之前**，Java 程序员要自己写：
- 创建对象的代码（`new Xxx()`）
- 管理对象生命周期的代码
- 读取配置文件的代码
- 启动 HTTP 服务器的代码

**Spring Boot 的承诺**：你只要写**业务逻辑**，其他全帮你搞定。

### 3.2 用你的代码理解「启动类」

打开 `yolo-server/YoloServerApplication.java`（完整）：

```java
package com.yolo.server;

import com.yolo.inference.YoloClassNames;
import com.yolo.inference.YoloInferenceEngine;
import org.apache.dubbo.config.spring.context.annotation.DubboComponentScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication                          // ① 我是程序的入口
@DubboComponentScan                             // ② 帮我扫描 Dubbo 注解
public class YoloServerApplication {            // ③ 启动类

    public static void main(String[] args) {    // ④ main 方法 = 程序起点
        String osName = System.getProperty("os.name").toLowerCase();
        String onnxLibPath = System.getProperty("onnxruntime.lib.path");
        if (onnxLibPath != null && !onnxLibPath.isEmpty()) {
            System.load(onnxLibPath);           // ⑤ 加载 ONNX 本地库
        }
        SpringApplication.run(YoloServerApplication.class, args);  // ⑥ 启动 Spring Boot
    }

    @Bean                                       // ⑦ 声明一个 Bean（对象）
    public YoloInferenceEngine inferenceEngine(
            @Value("${yolo.model-path}") String modelPath,          // ⑧ 从配置读模型路径
            @Value("${yolo.model-type:ship}") String modelType) throws Exception {
        return new YoloInferenceEngine(modelPath, YoloClassNames.forModelType(modelType));
    }
}
```

**逐行讲**：
- ① `@SpringBootApplication` —— **最重要的注解**。它是三个注解的组合：`@SpringBootConfiguration`（配置）+ `@EnableAutoConfiguration`（自动配置）+ `@ComponentScan`（扫描组件）。标记了它，Spring 才知道"从这开始启动"
- ⑥ `SpringApplication.run(...)` —— **真正启动**。它做了一堆事：启动内嵌 Tomcat、加载配置、创建所有 Bean
- ⑦ `@Bean` —— 告诉 Spring"把这个方法返回的对象创建好，放到容器里"。以后任何地方要 `YoloInferenceEngine`，Spring 直接给你
- ⑧ `@Value("${yolo.model-path}")` —— 从 `application.yml` 里读 `yolo.model-path` 的值。启动时你传 `--yolo.model-path=...` 就是覆盖这里的值

### 3.3 用你的代码理解「依赖注入 @Autowired」

打开 `ModelInferenceServiceImpl.java` 第 21-22 行：

```java
@Autowired
private YoloInferenceEngine engine;
```

**逐行讲**：
- `@Autowired` —— "帮我自动塞进来"
- `private YoloInferenceEngine engine;` —— 声明一个 `YoloInferenceEngine` 类型的字段，**不用自己 new**
- Spring 会在启动时，找到之前 `@Bean` 创建的那个 `YoloInferenceEngine` 对象，自动赋值给这个 `engine`
- **这就是"依赖注入（DI）"**：依赖（engine）由框架注入，而不是自己创建

### 3.4 用你的代码理解「配置文件 application.yml」

打开 `yolo-server/src/main/resources/application.yml`：

```yaml
spring:
  application:
    name: yolo-server          # 服务名叫 yolo-server

dubbo:
  protocol:
    name: tri                  # 用 Dubbo 的 triple 协议
    port: 0                    # 端口 0 = 由启动参数指定
  registry:
    address: nacos://127.0.0.1:8848   # 注册到 Nacos

yolo:
  model-path: ""               # 模型路径，启动时用 --yolo.model-path 指定
  model-type: ship             # 模型类型
```

**逐行讲**：
- YAML 用**缩进**表示层级关系（两个空格一层）
- 冒号后是值
- 程序里 `@Value("${yolo.model-type}")` 就是在读这个文件里的 `yolo.model-type`
- 启动时用 `--yolo.model-path=xxx` 可以**覆盖**文件里的值（命令行参数优先级更高）

### 动手练习（必做）
新建一个 Spring Boot 项目（IDEA 模板），写个 `HelloController`，浏览器访问返回 "Hello"。跑通后你会惊觉：`DetectController` 和它一模一样。

---

## 阶段 4：HTTP 与 REST API（3-5 天）

### 4.1 什么是 HTTP？（大白话）

HTTP = 浏览器和服务器对话的"语言"。浏览器发一个"请求"，服务器回一个"响应"。

### 4.2 用你的代码理解「控制器」

打开 `DetectController.java` 的完整代码（我贴完整版并逐行讲解）：

```java
package com.yolo.webapi.controller;

import com.yolo.inference.ImageTiler;
import com.yolo.proto.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController                       // ① 这是一个"控制器"，处理 HTTP 请求
@RequestMapping("/api")              // ② 这个类的网址都以 /api 开头
public class DetectController {      // ③ 类定义

    @DubboReference(version = "1.0.0", check = false)   // ④ 引用远程服务（网关）
    private YoloAggregationService yoloGateway;          // ⑤ 一个"远程代理对象"

    @PostMapping("/detect")          // ⑥ 处理 POST /api/detect 请求
    public ResponseEntity<Map<String, Object>> detect(   // ⑦ 方法，返回 HTTP 响应
            @RequestParam("image") MultipartFile image,   // ⑧ 接收上传的图片文件
            @RequestParam(defaultValue = "0.5") float confThreshold)  // ⑨ 阈值，默认0.5
            throws Exception {       // ⑩ 可能出错

        byte[] imageBytes = image.getBytes();   // ⑪ 图片文件 → 字节数组

        // ⑫ 把图片和参数打包成 Protobuf 消息
        AggregateDetectRequest request = AggregateDetectRequest.newBuilder()
            .setImageData(com.google.protobuf.ByteString.copyFrom(imageBytes))
            .setConfThreshold(confThreshold)
            .build();

        // ⑬ 调用网关（远程服务！）
        AggregateDetectResponse response = yoloGateway.detect(request);

        // ⑭ 把结果整理成 Map（键值对），Spring 会自动转成 JSON
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", response.getTotalCount());
        result.put("processingTimeMs", response.getProcessingTimeMs());

        // ⑮ 返回 HTTP 200 响应，内容是一个 Map（会自动变成 JSON）
        return ResponseEntity.ok(result);
    }
}
```

**关键逐行讲**：
- ① `@RestController` —— 标记"我是 HTTP 控制器"。Spring 会扫描它，把方法暴露成网址
- ⑥ `@PostMapping("/detect")` —— 这个方法处理"POST /api/detect"请求
- ⑧ `@RequestParam("image") MultipartFile image` —— 从请求里取出名为 `image` 的文件
- ⑨ `@RequestParam(defaultValue = "0.5")` —— 取参数，如果没传就用默认值 0.5
- ⑬ `yoloGateway.detect(request)` —— **这行很关键**：`yoloGateway` 是 Dubbo 生成的"远程代理"，调用它其实走了网络到网关
- ⑭ `Map` —— Spring Boot 会把 `Map` 自动序列化成 JSON 返回给浏览器

### 动手练习
1. 用浏览器访问 `http://localhost:8080/api/health`，看返回的 JSON
2. 用 `curl` 命令上传图片测试：
```bash
curl -X POST http://localhost:8080/api/detect -F "image=@图片路径" -F "confThreshold=0.25"
```

---

## 阶段 5：RPC 与 Dubbo（3-5 天）⭐ 项目灵魂

### 5.1 为什么要 RPC？（必须先懂）

你的电脑上跑着多个 Java 程序（server、gateway、web-api）。**程序 A 想调用程序 B 的方法，但它们在两个不同的进程里，不能直接调**（Java 内存隔离）。

RPC（远程过程调用）= 让 A 调用 B 的方法，**就像调用本地方法一样**，底层帮你走网络。

### 5.2 用你的代码理解「服务提供方 Provider」

`ModelInferenceServiceImpl.java` 第 16 行：

```java
@DubboService(group = "${yolo.model-type}", version = "1.0.0")
public class ModelInferenceServiceImpl implements ModelInferenceService {
```

**逐行讲**：
- `@DubboService` —— 声明"我提供一个 Dubbo 服务"
- `group = "${yolo.model-type}"` —— 分组。三个模型服务分别注册成 `group="ship"`、`group="plane"`、`group="car"`
- 启动时传 `--yolo.model-type=ship`，这里的 `group` 就变成 `ship`

### 5.3 用你的代码理解「服务调用方 Consumer」

`DetectController.java` 第 16-17 行：

```java
@DubboReference(version = "1.0.0", check = false)
private YoloAggregationService yoloGateway;
```

**逐行讲**：
- `@DubboReference` —— 声明"我要调用一个远程服务"
- `private YoloAggregationService yoloGateway;` —— 声明一个**接口类型**的字段
- **这里没有实现类！** `yoloGateway` 是 Dubbo 用**动态代理**生成的一个"会走网络"的假对象
- 你调用 `yoloGateway.detect(xxx)`，Dubbo 帮你：序列化参数 → 通过网络发给网关 → 拿到结果 → 反序列化返回

### 5.4 用你的代码理解「网关：既是 Provider 又是 Consumer」

打开 `YoloAggregationServiceImpl.java`（核心片段）：

```java
@DubboService(version = "1.0.0")                                    // ① 网关也提供服务
public class YoloAggregationServiceImpl implements YoloAggregationService {

    @DubboReference(group = "ship", check = false)                  // ② 调用 ship 服务
    private ModelInferenceService shipService;

    @DubboReference(group = "plane", check = false)                 // ③ 调用 plane 服务
    private ModelInferenceService planeService;

    @DubboReference(group = "car", check = false)                   // ④ 调用 car 服务
    private ModelInferenceService carService;

    @Override
    public AggregateDetectResponse detect(AggregateDetectRequest request) {
        // ⑤ 并行调用三个模型服务
        var shipFuture = CompletableFuture.supplyAsync(() -> shipService.detect(...));
        var planeFuture = CompletableFuture.supplyAsync(() -> planeService.detect(...));
        var carFuture = CompletableFuture.supplyAsync(() -> carService.detect(...));

        CompletableFuture.allOf(shipFuture, planeFuture, carFuture).join();  // ⑥ 等全部完成

        // ⑦ 合并三个结果，返回
        List<Detection> allDetections = new ArrayList<>();
        mergeResult(shipFuture.getNow(null), "[ship]", allDetections);
        mergeResult(planeFuture.getNow(null), "[plane]", allDetections);
        mergeResult(carFuture.getNow(null), "[car]", allDetections);
        // ...
    }
}
```

**关键逐行讲**：
- ① 网关对 web-api 来说是 Provider（提供服务）
- ②③④ 网关对三个模型来说是 Consumer（调用服务）
- ⑤ **三个调用并行执行**（同时发起，不是一个个等）
- ⑥ `.join()` 等所有调用都返回
- ⑦ 合并时给每个模型的类别加前缀 `[ship]`、`[plane]`、`[car]`，方便知道结果来自哪个模型

### 动手练习
1. 找出代码里所有 `@DubboService` 和 `@DubboReference`，画出"谁服务谁"的图
2. 关掉 car 服务，再检测——你会看到网关降级，只返回 ship+plane 结果（这就是故障隔离）

---

## 阶段 6：Protobuf 序列化（2-3 天）

### 6.1 为什么要序列化？（大白话）

服务之间传数据，不能直接传 Java 对象（不同进程内存隔离）。要先把对象变成**字节**（能通过网络传输），到对方那边再变回对象。这个"变字节"的过程叫**序列化**。

### 6.2 用你的 proto 文件理解

打开 `yolo-proto/src/main/proto/yolo_detect.proto`：

```protobuf
syntax = "proto3";                       // ① 语法版本
package yolo;                            // ② 包名

// ③ 定义一个"检测结果"长什么样
message Detection {
    int32       class_id    = 1;         // ④ 类别编号
    string      class_name  = 2;         // 类别名
    float       confidence  = 3;         // 置信度
    BoundingBox bbox        = 4;         // 位置
}

message BoundingBox {                    // ⑤ 定义"位置"（4个角点）
    float x1 = 1; float y1 = 2;
    float x2 = 3; float y2 = 4;
    float x3 = 5; float y3 = 6;
    float x4 = 7; float y4 = 8;
}
```

**逐行讲**：
- ③ `message Detection` —— 定义一个"数据结构"，叫 Detection
- ④ `int32 class_id = 1` —— 有一个整数字段叫 `class_id`，编号 1
- `= 1` 后面的数字是**字段编号**（序列化时用编号，不是名字，所以省空间）
- 这个文件是**说明书**，编译器根据它**自动生成 Java 类**

### 6.3 生成后的 Java 代码怎么用？（看项目代码）

在 `DetectController.java` 里：

```java
AggregateDetectRequest request = AggregateDetectRequest.newBuilder()  // ① 创建"构建器"
    .setImageData(ByteString.copyFrom(imageBytes))   // ② 设置图片数据
    .setConfThreshold(confThreshold)                 // ③ 设置阈值
    .build();                                        // ④ 真正创建对象
```

**逐行讲**：
- ① `newBuilder()` —— Protobuf 用"构建器模式"创建对象
- ② `setImageData(...)` —— 设置字段（像 `setXxx` 方法）
- ④ `.build()` —— 最终生成不可变的对象

在 `ModelInferenceServiceImpl.java` 里反向操作（读取）：

```java
byte[] imageBytes = request.getImageData().toByteArray();   // 读取图片数据
float conf = request.getConfThreshold();                    // 读取阈值
```

### 动手练习
运行 `mvn compile` 编译 `yolo-proto`，看 `target/generated-sources` 下自动生成的 Java 文件——体会"说明书 → 代码"的过程。

---

## 阶段 7：Nacos 注册中心（2-3 天）

### 7.1 Nacos 是干什么的？（通讯录比喻）

- **注册**：模型服务启动后，告诉 Nacos"我在 8001 端口"
- **发现**：网关启动后，问 Nacos"ship 服务在哪"
- 好处：**服务地址变了也不需要改代码**

### 7.2 用你的配置理解

`yolo-server/application.yml`：

```yaml
dubbo:
  registry:
    address: nacos://127.0.0.1:8848    # 注册到 Nacos，地址是 8848
```

`yolo-gateway/application.yml`：

```yaml
dubbo:
  registry:
    address: nacos://127.0.0.1:8848    # 网关也连同一个 Nacos
```

**流程**：
```
ship 服务启动 → 注册到 Nacos ("ship 在 8001")
gateway 启动  → 问 Nacos "ship 在哪" → 得到 8001
                → 以后调用 ship 就走 8001
```

### 动手练习
1. 启动项目，打开 `http://localhost:8848/nacos`，看服务列表
2. 关掉一个模型服务，观察 Nacos 列表变化（服务变下线）

---

## 阶段 8：ONNX 与图像处理（1 周）

### 8.1 核心概念
| 概念 | 白话 |
|------|------|
| ONNX | AI 模型的通用文件格式（训练好的"大脑"） |
| ONNX Runtime | 加载并运行 ONNX 的引擎 |
| 推理 | 把图片喂给模型，得到检测结果 |
| JavaCV | OpenCV 的 Java 版，做图像处理 |

### 8.2 用你的代码理解「推理引擎」

打开 `yolo-inference/YoloInferenceEngine.java`，看 `detect()` 方法：

```java
public List<DetResult> detect(BufferedImage image, DetectionOptions options) throws Exception {
    // 第 1 步：预处理（把图片变成模型能吃的格式）
    float[] inputData = preprocess(image);

    // 第 2 步：ONNX 推理（把数据喂给模型，得到输出）
    float[][][] output = runInference(inputData);

    // 第 3 步：后处理（把模型的原始输出变成"检测框"）
    List<DetResult> detections = postprocess(output, image.getWidth(), image.getHeight(), options);

    // 第 4 步：NMS（去掉重复的检测框）
    return applyNMS(detections, options.iouThreshold());
}
```

**逐行讲**：
- `BufferedImage image` —— 输入是一张 Java 图片
- `DetectionOptions options` —— 检测参数（置信度阈值等）
- 4 步流程就是整个 AI 检测的核心

### 8.3 用你的代码理解「后处理」（重点，看模型输出怎么解析）

`postprocess` 方法里（核心逻辑）：

```java
int K = output[0][0].length;   // 每个检测框有几个数值
// 模型输出可能是 7 列（旋转框）或 6 列（普通框）

if (K >= 7) {
    // 7 列格式: [cx, cy, w, h, angle, class_id, raw_conf]
    float rawConf = det[6];        // 第 7 个值是"原始置信度"
    // sigmoid 转换：把任意实数变成 0~1 的概率
    float confidence = 1.0f / (1.0f + (float) Math.exp(-rawConf));
    if (confidence < options.confidenceThreshold()) continue;  // 低于阈值就跳过
    float cx = det[0], cy = det[1], w = det[2], h = det[3], angle = det[4];
    int clsId = (int) det[5];      // 类别编号
    // ... 把 (cx,cy,w,h,angle) 转成 4 个角点坐标
}
```

**逐行讲**：
- 模型输出的是"原始数字"，需要**解析**才能变成有用的检测框
- `Math.exp(-rawConf)` 然后 `1/(1+x)` 就是 **sigmoid**，把任意数压到 0~1（概率）
- `confidence < 阈值` 就跳过——这是**过滤低置信度**的检测
- 每个模型的输出列数可能不同（你的 ship/plane 是 7 列旋转框，car 是 6 列普通框），代码要**自适应**判断

### 动手练习
用 `testimage/星载可见光影像.png`，写个最简单 main 方法直接调用 `YoloInferenceEngine`，打印检测结果。会有极大的成就感。

---

## 阶段 9：串联整个系统（1 周）⭐ 把知识串起来

### 完整数据流（对着代码一步步走）

**第 1 站：前端上传**
`yolo-web/src/api/detect.ts`（TypeScript，前端）：
```typescript
formData.append('image', file)          // 把图片放进表单
await api.post('/api/detect', formData) // 发给后端 8080
```

**第 2 站：Web API 接收**（`DetectController.java`）
```java
@PostMapping("/detect")
public ... detect(@RequestParam("image") MultipartFile image, ...) {
    byte[] imageBytes = image.getBytes();          // 拿到图片字节
    AggregateDetectRequest request = ...newBuilder()  // 打包成 Protobuf
        .setImageData(...).build();
    AggregateDetectResponse response = yoloGateway.detect(request);  // 调用网关！
}
```

**第 3 站：网关调度**（`YoloAggregationServiceImpl.java`）
```java
var shipFuture = CompletableFuture.supplyAsync(() -> shipService.detect(req));   // 并行
var planeFuture = CompletableFuture.supplyAsync(() -> planeService.detect(req)); // 并行
var carFuture = CompletableFuture.supplyAsync(() -> carService.detect(req));     // 并行
CompletableFuture.allOf(shipFuture, planeFuture, carFuture).join();   // 等全部
```

**第 4 站：模型服务推理**（`ModelInferenceServiceImpl.java` → `YoloInferenceEngine.java`）
```java
BufferedImage image = ImageUtils.loadImage(imageBytes);   // 解码图片
List<DetResult> detections = engine.detect(image, options); // 4步：预处理→推理→后处理→NMS
```

**第 5 站：结果逐层返回**
```
模型服务返回 DetResult 列表
  → 网关合并 3 路，加 [ship]/[plane]/[car] 前缀，画标注图
  → Web API 转成 JSON（含 base64 图片）
  → 前端显示 + 三维地球叠加
```

### 动手练习（强烈推荐）
用 IDEA 的 **Debug（断点）**：
1. 在 `DetectController.detect()` 第 50 行打断点（`yoloGateway.detect(request)`）
2. 前端上传图片
3. 单步跟踪，看它怎么进入网关、网关怎么调三个模型、结果怎么回来
4. **这是最快理解分布式系统的方法**

---

## 阶段 10：进阶主题（按兴趣选学）

| 主题 | 项目里对应 | 学习价值 |
|------|-----------|---------|
| Java 21 虚拟线程 | 网关 `Executors.newVirtualThreadPerTaskExecutor()` | 现代并发 |
| CompletableFuture | 网关 `supplyAsync` / `allOf` | 异步编程 |
| NMS 算法 | `YoloInferenceEngine.applyNMS()` | 算法思维 |
| 大图裁切 | `ImageTiler.java` | 工程优化 |
| GeoTIFF 解析 | `ImageTiler.parseGeoTiffMeta()` | 数据格式处理 |
| 前端 Vue | `yolo-web/src/` | 全栈 |
| 三维地球 | `MapOverlay.vue` (Cesium) | 可视化 |

---

## 📚 学习资源推荐

| 阶段 | 资源 |
|------|------|
| Java 语法 | 菜鸟教程 / B 站"Java 零基础" |
| Spring Boot | 尚硅谷/黑马 B 站 Spring Boot 入门课 |
| Maven | 搜"Maven 入门"，看懂 pom.xml 即可 |
| Dubbo | Dubbo 官网"快速开始" + 本项目源码 |
| Protobuf | 搜"Protobuf 入门"，看懂 message 即可 |
| 代码阅读 | IDEA Debug + 断点，永远比看教程快 |

---

## ⚠️ 常见学习误区

1. **不要试图一次学完**：先跑通 → 学 Spring Boot → 学 Dubbo → 学 ONNX，一次一个
2. **不要只看不写**：每阶段必须动手
3. **不要从最难处开始读**：web-api（简单）→ 网关 → server → 推理引擎（难）
4. **不要怕报错**：读报错是核心竞争力
5. **先 Debug 后看文档**

---

## 🏁 学习路线图总结

```
阶段0 方法论 → 阶段1 Java基础(1-2周) → 阶段2 Maven(3-5天)
→ 阶段3 SpringBoot(1-2周)⭐ → 阶段4 HTTP(3-5天) → 阶段5 Dubbo(3-5天)⭐
→ 阶段6 Protobuf(2-3天) → 阶段7 Nacos(2-3天) → 阶段8 ONNX(1周)
→ 阶段9 串联系统(1周)⭐ → 阶段10 进阶
```

**预估总时长**：每天 2-3 小时，约 2-3 个月。

> 学习过程中随时回到本指南对应阶段，用 Debug 跟踪代码。祝你顺利！🚀
