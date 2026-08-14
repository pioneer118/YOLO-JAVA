package com.yolo.gateway;

// ImageTiler：推理模块的工具类，负责大图裁切、GSD解析、坐标偏移
import com.yolo.inference.ImageTiler;
// ImageUtils：图片解码工具（支持 TIFF）
import com.yolo.inference.ImageUtils;
// YoloInferenceEngine：推理引擎（这里只用来画检测框）
import com.yolo.inference.YoloInferenceEngine;
// DetResult：检测结果 POJO（四个角点 + 类别 + 置信度）
import com.yolo.inference.DetResult;
// proto.*：Protobuf 生成的消息类（请求/响应/检测结果等）
import com.yolo.proto.*;
// DubboReference：Dubbo 注解，声明"我要调用远程服务"
import org.apache.dubbo.config.annotation.DubboReference;
// DubboService：Dubbo 注解，声明"我提供一个服务"
import org.apache.dubbo.config.annotation.DubboService;
// Logger：日志工具（打印运行信息）
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Autowired：Spring 依赖注入（自动给字段赋值）
import org.springframework.beans.factory.annotation.Autowired;
// Value：从配置文件读值注入
import org.springframework.beans.factory.annotation.Value;

// BufferedImage：Java 的图片对象
import java.awt.image.BufferedImage;
// ArrayList / List：集合
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
// CompletableFuture：异步任务的"快递单号"（能并行、能等、能处理超时）
import java.util.concurrent.CompletableFuture;
// ExecutorService：执行器（管理线程干活的地方）
import java.util.concurrent.ExecutorService;
// Semaphore：信号量（限制并发数量）
import java.util.concurrent.Semaphore;
// TimeUnit：时间单位
import java.util.concurrent.TimeUnit;
// Supplier：函数式接口（提供一个返回值）
import java.util.function.Supplier;

/**
 * 网关聚合服务实现。
 *
 * <p>网关是整个检测系统的"调度中心"，收到 web-api 的请求后：
 * <ol>
 *   <li><b>普通路径</b>：小图（≤自动裁切阈值）→ 直接扇出 3 模型 → 聚合 → 标注</li>
 *   <li><b>裁切路径</b>：大图 → 自适应裁切 → 分批并行 tile 检测 → 坐标偏移 → 跨 tile NMS → 标注</li>
 * </ol>
 */
@DubboService(version = "1.0.0")   // 声明：这是一个 Dubbo 服务（对 web-api 提供聚合服务）
public class YoloAggregationServiceImpl implements YoloAggregationService {

    private static final Logger log = LoggerFactory.getLogger(YoloAggregationServiceImpl.class);  // 日志对象（打印运行信息）

    // ---- Dubbo 引用：三个后端模型服务 ----

    @DubboReference(group = "ship", version = "1.0.0", check = false)   // 引用 group=ship 的远程服务
    private ModelInferenceService shipService;    // 舰船检测服务（远程代理对象，调用即走网络到 8001）

    @DubboReference(group = "plane", version = "1.0.0", check = false)  // 引用 group=plane 的远程服务
    private ModelInferenceService planeService;   // 飞机检测服务（远程代理对象，走网络到 8002）

    @DubboReference(group = "car", version = "1.0.0", check = false)    // 引用 group=car 的远程服务
    private ModelInferenceService carService;     // 车辆检测服务（远程代理对象，走网络到 8003）

    // ---- 注入 ----

    @Autowired                       // Spring 自动注入
    private ExecutorService executor;    // 虚拟线程执行器（用来并行执行任务）

    /** 并发 tile 上限（避免冲垮后端） */
    @Value("${yolo.tiling.max-concurrent-tiles:12}")   // 从配置读值，默认 12
    private int maxConcurrentTiles;    // 同时处理的瓦片数上限

    /** 单批次处理 tile 数（每一批内并行扇出模型） */
    private static final int BATCH_SIZE = 4;   // 每批处理 4 个瓦片（避免并发过多）

    /** 跨 tile NMS 的 IoU 阈值 */
    private static final float CROSS_TILE_IOU_THRESHOLD = 0.4f;   // 重叠超过 40% 就认为是同一个目标

    // ================================================================
    //  入口
    // ================================================================

    @Override
    public AggregateDetectResponse detect(AggregateDetectRequest request) {   // 主方法：处理检测请求
        long startTime = System.currentTimeMillis();   // 记录开始时间（算耗时）

        try {
            byte[] imageBytes = request.getImageData().toByteArray();   // 取出图片字节
            float confThreshold = request.getConfThreshold() > 0        // 读取置信度阈值
                    ? request.getConfThreshold() : 0.6f;                // 没传或<=0 就用 0.6

            // 1. 解析外部 GSD（从 data_source 字段提取）
            //    格式: "drone:gsd=0.05:lat=30.1:lng=120.2" 或普通的 "satellite"/"drone"
            float externalGsd = parseGsd(request.getDataSource());      // 从 dataSource 解析 GSD

            // 2. 分析图片，获取自适应裁切策略（优先外部 GSD）
            ImageTiler.TilingStrategy strategy = ImageTiler.analyze(imageBytes, externalGsd);   // 得到裁切策略

            // 2. 用 OpenCV 解码图片（支持 TIFF）
            BufferedImage fullImage = ImageUtils.loadImage(imageBytes);  // 图片字节 → Java 图片对象
            int imgW = fullImage.getWidth();   // 图片宽度
            int imgH = fullImage.getHeight();  // 图片高度

            // 3. 判断是否需要裁切
            if (ImageTiler.shouldTile(imgW, imgH, strategy)) {   // 如果图片超过阈值需要裁切
                log.info("Tiling enabled: {}x{} image, strategy={}, tileSize={}",
                        imgW, imgH, strategy.level(), strategy.tileSize());   // 打印裁切信息
                return detectWithTiling(fullImage, request, strategy, startTime);   // 走"裁切路径"
            } else {
                return detectNormal(fullImage, request, confThreshold, startTime);  // 走"普通路径"
            }
        } catch (Exception e) {
            log.error("Detection failed", e);   // 出错打印日志
            return AggregateDetectResponse.newBuilder()   // 返回空结果（不让请求崩溃）
                    .setTotalCount(0)
                    .setProcessingTimeMs((int) (System.currentTimeMillis() - startTime))
                    .setAnnotatedImage(com.google.protobuf.ByteString.copyFrom(
                            request.getImageData().toByteArray()))   // 原图原样返回
                    .build();
        }
    }

    // ================================================================
    //  路径一：普通检测（不裁切）
    // ================================================================

    /**
     * 小图检测路径：图片不裁切，直接并行调用三个模型，聚合结果。
     *
     * @param fullImage     解码后的完整图片
     * @param request       原始请求（含图片字节、阈值等）
     * @param confThreshold 置信度阈值
     * @param startTime     开始时间（算耗时）
     * @return 聚合后的检测响应
     * @throws Exception 可能出错
     */
    private AggregateDetectResponse detectNormal(BufferedImage fullImage,
                                                  AggregateDetectRequest request,
                                                  float confThreshold,
                                                  long startTime) throws Exception {
        // 构建要发给模型服务的请求（图片 + 阈值 + 数据源）
        ModelDetectRequest modelRequest = ModelDetectRequest.newBuilder()
                .setImageData(request.getImageData())       // 图片字节
                .setConfThreshold(confThreshold)            // 阈值
                .setDataSource(request.getDataSource())     // 数据来源
                .build();

        // 解析要调用的目标服务（targets 决定调哪些模型）
        java.util.Set<String> targets = parseTargets(request.getDataSource());   // 从 dataSource 解析要调用的模型
        boolean multiTarget = targets.size() > 1;   // 是否调多个模型（决定要不要加前缀）

        // 用 CompletableFuture 并行发起对三个模型的调用
        List<CompletableFuture<ModelResult>> futures = new ArrayList<>();   // 存每个调用的"快递单号"
        if (targets.contains("ship"))  futures.add(invokeModel("ship",  () -> shipService.detect(modelRequest)));    // 并行调 ship
        if (targets.contains("plane")) futures.add(invokeModel("plane", () -> planeService.detect(modelRequest)));   // 并行调 plane
        if (targets.contains("car"))   futures.add(invokeModel("car",   () -> carService.detect(modelRequest)));     // 并行调 car

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();   // 等所有调用完成

        // 收集结果，合并成一个列表
        List<Detection> allDetections = new ArrayList<>();   // 所有模型的检测结果
        for (CompletableFuture<ModelResult> f : futures) {   // 遍历每个调用
            ModelResult r = f.getNow(null);   // 立即拿结果（已经等完了，一定有了）
            if (r != null && r.success()) {   // 如果调用成功
                String prefix = multiTarget ? "[" + r.modelType() + "]" : "";   // 多个模型就加前缀（如 [ship]）
                mergeResult(r, prefix, allDetections);   // 合并结果
            }
        }

        // 在原图上画所有检测框
        byte[] annotatedBytes = drawAllDetections(fullImage, allDetections);

        // 构建响应返回
        return AggregateDetectResponse.newBuilder()
                .setTotalCount(allDetections.size())                    // 检测总数
                .addAllDetections(allDetections)                        // 检测列表
                .setAnnotatedImage(com.google.protobuf.ByteString.copyFrom(annotatedBytes))  // 标注图
                .setProcessingTimeMs((int) (System.currentTimeMillis() - startTime))  // 耗时
                .build();
    }

    // ================================================================
    //  路径二：裁切检测
    // ================================================================

    /**
     * 大图检测路径：把大图切成重叠瓦片，分批并行检测，合并结果。
     *
     * @param fullImage     解码后的完整大图
     * @param request       原始请求
     * @param strategy      裁切策略（瓦片大小、重叠量）
     * @param startTime     开始时间
     * @return 聚合后的检测响应
     * @throws Exception 可能出错
     */
    private AggregateDetectResponse detectWithTiling(BufferedImage fullImage,
                                                      AggregateDetectRequest request,
                                                      ImageTiler.TilingStrategy strategy,
                                                      long startTime) throws Exception {
        // 1. BufferedImage → OpenCV Mat（通过 JPEG 往返，避免 imdecode 解码 TIFF 失败）
        byte[] jpegBytes = ImageUtils.toJpegBytes(fullImage);   // 图片转成 JPEG 字节
        org.bytedeco.opencv.opencv_core.Mat fullMat =
                org.bytedeco.opencv.global.opencv_imgcodecs.imdecode(
                        new org.bytedeco.opencv.opencv_core.Mat(jpegBytes),
                        org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_COLOR);   // 解码成 OpenCV Mat

        // 2. 裁切：把大图切成重叠瓦片
        List<ImageTiler.Tile> tiles = ImageTiler.tile(fullMat, strategy);   // 得到瓦片列表
        fullMat.close();   // 释放 Mat 资源

        log.info("Image tiled into {} tiles (strategy={}, tileSize={})",
                tiles.size(), strategy.level(), strategy.tileSize());   // 打印瓦片数

        // 3. 快速探活：检测哪些模型服务可用（避免对死服务发请求白等）
        java.util.Set<String> targets = parseTargets(request.getDataSource());   // 解析目标模型
        java.util.Set<String> liveServices = probeLiveServices(targets);   // 探活：只保留活着的模型

        // 4. 分批并行处理所有 tile（只调活着的服务）
        Semaphore semaphore = new Semaphore(maxConcurrentTiles);   // 信号量：限制并发瓦片数
        List<Detection> allDetections = Collections.synchronizedList(new ArrayList<>());   // 线程安全的检测结果集合

        for (int i = 0; i < tiles.size(); i += BATCH_SIZE) {   // 每批 BATCH_SIZE 个瓦片
            int batchEnd = Math.min(i + BATCH_SIZE, tiles.size());   // 这一批的结束位置
            List<CompletableFuture<Void>> batch = new ArrayList<>();   // 这一批的"快递单号"列表

            for (int j = i; j < batchEnd; j++) {   // 遍历这一批的每个瓦片
                ImageTiler.Tile tile = tiles.get(j);   // 取当前瓦片
                batch.add(processOneTile(tile, request, semaphore, liveServices)   // 处理这个瓦片（异步）
                        .thenAccept(tileDets -> {     // 处理完成后
                            if (!tileDets.isEmpty()) {   // 如果有检测结果
                                allDetections.addAll(tileDets);   // 收集到总结果（线程安全）
                            }
                        }));
            }

            CompletableFuture.allOf(batch.toArray(new CompletableFuture[0])).join();   // 等这一批完成

            if (i % (BATCH_SIZE * 5) == 0 || batchEnd == tiles.size()) {   // 定期打印进度
                log.info("Tile progress: {}/{} tiles processed, {} detections so far",
                        batchEnd, tiles.size(), allDetections.size());
            }
        }

        // 4. 跨 tile 全局 NMS 去重（重叠区同一个目标可能被多次检测）
        List<Detection> merged = crossTileNMS(allDetections);

        // 5. 在原图上绘制
        byte[] annotatedBytes = drawAllDetections(fullImage, merged);

        int elapsed = (int) (System.currentTimeMillis() - startTime);   // 计算总耗时
        log.info("Tiling detection complete: {} tiles → {} raw detections → {} after NMS, {}ms",
                tiles.size(), allDetections.size(), merged.size(), elapsed);

        return AggregateDetectResponse.newBuilder()   // 构建响应
                .setTotalCount(merged.size())                    // 总数（去重后）
                .addAllDetections(merged)                        // 检测列表（去重后）
                .setAnnotatedImage(com.google.protobuf.ByteString.copyFrom(annotatedBytes))  // 标注图
                .setProcessingTimeMs(elapsed)                    // 耗时
                .build();
    }

    /**
     * 处理单个 tile：扇出到 3 个模型，收集结果并偏移坐标。
     *
     * @param tile         当前瓦片（含图片字节和原图偏移）
     * @param request      原始请求
     * @param semaphore    信号量（限制并发）
     * @param liveServices 存活的模型服务集合
     * @return 该瓦片的检测结果（已偏移到原图坐标）
     */
    private CompletableFuture<List<Detection>> processOneTile(ImageTiler.Tile tile,
                                                               AggregateDetectRequest request,
                                                               Semaphore semaphore,
                                                               java.util.Set<String> liveServices) {
        float confThreshold = request.getConfThreshold() > 0   // 读取阈值
                ? request.getConfThreshold() : 0.6f;

        // 把"这一个瓦片"打包成给模型服务的请求
        ModelDetectRequest modelRequest = ModelDetectRequest.newBuilder()
                .setImageData(com.google.protobuf.ByteString.copyFrom(tile.imageBytes()))  // 瓦片图片字节
                .setConfThreshold(confThreshold)   // 阈值
                .setDataSource(request.getDataSource())   // 数据来源
                .build();

        boolean multiTarget = liveServices.size() > 1;   // 是否调多个模型（决定前缀）

        // 对活着的模型，并行发起调用（信号量限制并发）
        // invoke在这里相当于异步+信号量调用
        List<CompletableFuture<ModelResult>> futures = new ArrayList<>();
        if (liveServices.contains("ship"))  futures.add(invokeModelGated("ship",
                () -> callGated(semaphore, () -> shipService.detect(modelRequest))));   // 调 ship（限流）
        if (liveServices.contains("plane")) futures.add(invokeModelGated("plane",
                () -> callGated(semaphore, () -> planeService.detect(modelRequest))));  // 调 plane（限流）
        if (liveServices.contains("car"))   futures.add(invokeModelGated("car",
                () -> callGated(semaphore, () -> carService.detect(modelRequest))));    // 调 car（限流）

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))   // 等所有调用完成
                .thenApply(v -> {   // 完成后处理
                    List<Detection> dets = new ArrayList<>();   // 这个瓦片的检测结果
                    for (CompletableFuture<ModelResult> f : futures) {   // 遍历每个模型调用
                        ModelResult r = f.getNow(null);   // 取结果
                        if (r != null && r.success()) {   // 如果成功
                            String prefix = multiTarget ? "[" + r.modelType() + "]" : "";   // 加前缀
                            mergeResult(r, prefix, dets);   // 合并
                        }
                    }
                    // 坐标从 tile 局部空间偏移到原图全局空间（关键！）
                    return ImageTiler.offsetToOriginal(dets, tile.x(), tile.y());
                });
    }

    /**
     * 带信号量限制地执行一个任务。
     * @param semaphore 信号量
     * @param supplier  要执行的任务
     * @return 任务结果
     */
    private <T> T callGated(Semaphore semaphore, Supplier<T> supplier) {
        try {
            semaphore.acquire();   // 获取一个许可（并发数-1，满了就等）
            return supplier.get();   // 执行任务（调用模型）
        } catch (InterruptedException e) {   // 如果等待被中断
            Thread.currentThread().interrupt();   // 恢复中断标志
            throw new RuntimeException("Interrupted", e);   // 抛异常
        } finally {
            semaphore.release();   // 释放许可（并发数+1）
        }
    }

    // ================================================================
    //  跨 tile NMS
    // ================================================================

    /**
     * 对所有 tile 汇总的检测结果执行全局 NMS（去重）。
     *
     * <p>瓦片间重叠区域中，同一目标可能被相邻两个 tile 都检测到。
     * 坐标已偏移到原图空间，此处直接用标准 NMS 去重即可。
     *
     * @param detections 所有瓦片汇总的检测结果
     * @return 去重后的检测结果
     */
    private List<Detection> crossTileNMS(List<Detection> detections) {
        if (detections.size() <= 1) return detections;   // 只有一个就不用去重

        // 转为 DetResult 用于 NMS 计算（DetResult 更便于处理角点）
        List<DetResult> results = new ArrayList<>();
        for (Detection d : detections) {   // 遍历每个检测
            DetResult r = new DetResult();   // 创建 DetResult
            r.classId = d.getClassId();      // 类别编号
            r.className = d.getClassName();  // 类别名
            r.confidence = d.getConfidence();   // 置信度
            r.x1 = d.getBbox().getX1(); r.y1 = d.getBbox().getY1();   // 四个角点
            r.x2 = d.getBbox().getX2(); r.y2 = d.getBbox().getY2();
            r.x3 = d.getBbox().getX3(); r.y3 = d.getBbox().getY3();
            r.x4 = d.getBbox().getX4(); r.y4 = d.getBbox().getY4();
            r.cx = (r.x1 + r.x3) / 2f;   // 中心 X
            r.cy = (r.y1 + r.y3) / 2f;   // 中心 Y
            results.add(r);   // 加入列表
        }

        // 按置信度降序排序（置信度高的先处理）
        results.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        // 贪心 NMS：从最高的开始，抑制和它重叠的同类别框
        int n = results.size();
        boolean[] suppressed = new boolean[n];   // 标记哪些框被抑制（去掉）
        List<DetResult> kept = new ArrayList<>();   // 保留的框

        for (int i = 0; i < n; i++) {   // 遍历每个框
            if (suppressed[i]) continue;   // 已被抑制就跳过
            DetResult best = results.get(i);   // 当前置信度最高的框
            kept.add(best);   // 保留它

            for (int j = i + 1; j < n; j++) {   // 遍历后面的框
                if (suppressed[j]) continue;   // 已被抑制就跳过

                float iou = computePolygonIoU(   // 计算两个旋转框的 IoU（重叠程度）
                        new float[][]{
                                {best.x1, best.y1}, {best.x2, best.y2},
                                {best.x3, best.y3}, {best.x4, best.y4}},
                        new float[][]{
                                {results.get(j).x1, results.get(j).y1},
                                {results.get(j).x2, results.get(j).y2},
                                {results.get(j).x3, results.get(j).y3},
                                {results.get(j).x4, results.get(j).y4}}
                );
                if (iou > CROSS_TILE_IOU_THRESHOLD) {   // 重叠超过阈值（50%）
                    suppressed[j] = true;   // 抑制（去掉）这个框
                }
            }
        }

        // 转回 Protobuf Detection
        List<Detection> merged = new ArrayList<>();   // 去重后的结果
        for (DetResult r : kept) {   // 遍历保留的框
            merged.add(Detection.newBuilder()   // 转回 Detection
                    .setClassId(r.classId)
                    .setClassName(r.className)
                    .setConfidence(r.confidence)
                    .setBbox(BoundingBox.newBuilder()
                            .setX1(r.x1).setY1(r.y1)
                            .setX2(r.x2).setY2(r.y2)
                            .setX3(r.x3).setY3(r.y3)
                            .setX4(r.x4).setY4(r.y4)
                            .build())
                    .build());
        }
        return merged;   // 返回去重后的结果
    }

    /**
     * 计算两个四边形的 IoU（Sutherland-Hodgman 裁剪 + 鞋带公式面积）。
     *
     * @param polyA 四边形 A（四个角点）
     * @param polyB 四边形 B（四个角点）
     * @return IoU 值（0~1，越大重叠越多）
     */
    private float computePolygonIoU(float[][] polyA, float[][] polyB) {
        float areaA = polygonArea(polyA);   // 计算 A 的面积
        float areaB = polygonArea(polyB);   // 计算 B 的面积
        if (areaA <= 0 || areaB <= 0) return 0;   // 面积无效就返回 0

        float[][] clipped = clipPolygon(polyA, polyB);   // 计算 A 和 B 的交集多边形
        float interArea = polygonArea(clipped);   // 交集面积

        return interArea / (areaA + areaB - interArea + 1e-6f);   // IoU = 交集 / 并集
    }

    /**
     * 计算多边形面积（鞋带公式）。
     * @param poly 多边形角点
     * @return 面积
     */
    private float polygonArea(float[][] poly) {
        int n = poly.length;   // 角点数
        float area = 0;   // 面积累加器
        for (int i = 0; i < n; i++) {   // 遍历每个角点
            int j = (i + 1) % n;   // 下一个角点（首尾相连）
            area += poly[i][0] * poly[j][1];   // 鞋带公式：叉积
            area -= poly[j][0] * poly[i][1];
        }
        return 0.5f * Math.abs(area);   // 取绝对值的一半
    }

    /**
     * 多边形裁剪（Sutherland-Hodgman 算法）：求两个凸多边形的交集。
     * @param subject 被裁剪的多边形
     * @param clip    裁剪窗口多边形
     * @return 交集多边形
     */
    private float[][] clipPolygon(float[][] subject, float[][] clip) {
        float[][] output = subject;   // 初始结果 = subject
        int n = clip.length;   // 裁剪窗口边数
        for (int e = 0; e < n; e++) {   // 对裁剪窗口的每条边做裁剪
            if (output.length == 0) return output;   // 已经没了就返回空
            float[][] input = output;   // 上一步的结果作为输入
            float ex1 = clip[e][0], ey1 = clip[e][1];   // 当前边的起点
            float ex2 = clip[(e + 1) % n][0], ey2 = clip[(e + 1) % n][1];   // 当前边的终点
            output = clipAgainstEdge(input, ex1, ey1, ex2, ey2);   // 用这条边裁剪
        }
        return output;   // 返回交集
    }

    /**
     * 用一条边裁剪多边形（Sutherland-Hodgman 的边裁剪）。
     * @param poly  要裁剪的多边形
     * @param ex1/ey1 边的起点
     * @param ex2/ey2 边的终点
     * @return 裁剪后的多边形
     */
    private float[][] clipAgainstEdge(float[][] poly, float ex1, float ey1, float ex2, float ey2) {
        List<float[]> out = new ArrayList<>();   // 输出角点列表
        int n = poly.length;   // 角点数
        if (n == 0) return new float[0][0];   // 空多边形直接返回

        float edgeX = ex2 - ex1, edgeY = ey2 - ey1;   // 边的方向向量
        for (int i = 0; i < n; i++) {   // 遍历每个角点
            float[] cur = poly[i];   // 当前角点
            float[] next = poly[(i + 1) % n];   // 下一个角点
            boolean curInside = isInside(cur[0], cur[1], ex1, ey1, ex2, ey2);   // 当前点在边内吗
            boolean nextInside = isInside(next[0], next[1], ex1, ey1, ex2, ey2);   // 下个点在边内吗

            if (curInside) {   // 当前点在边内
                out.add(cur);   // 保留当前点
                if (!nextInside) {   // 但下个点在边外 → 边穿出，求交点
                    out.add(lineIntersection(cur[0], cur[1], next[0], next[1], ex1, ey1, ex2, ey2));
                }
            } else if (nextInside) {   // 当前点在边外但下个点在边内 → 边穿入，求交点
                out.add(lineIntersection(cur[0], cur[1], next[0], next[1], ex1, ey1, ex2, ey2));
            }
        }
        return out.toArray(new float[out.size()][]);   // 返回裁剪后的多边形
    }

    // 点在边内侧的判断（叉积 <= 0 表示在边左侧 = 内侧）
    private static boolean isInside(float px, float py, float ex1, float ey1, float ex2, float ey2) {
        return (ex2 - ex1) * (py - ey1) - (ey2 - ey1) * (px - ex1) <= 0;
    }

    /**
     * 求两条线段的交点。
     * @param x1/y1 线段1起点, x2/y2 线段1终点, x3/y3 线段2起点, x4/y4 线段2终点
     * @return 交点坐标
     */
    private static float[] lineIntersection(float x1, float y1, float x2, float y2,
                                             float x3, float y3, float x4, float y4) {
        float d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);   // 计算分母（判断是否平行）
        if (Math.abs(d) < 1e-12f) return new float[]{x1, y1};   // 平行就返回起点
        float t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / d;   // 参数 t
        return new float[]{x1 + t * (x2 - x1), y1 + t * (y2 - y1)};   // 交点坐标
    }

    // ================================================================
    //  模型调用
    // ================================================================

    /**
     * 发起一次模型调用（带超时和异常处理）。
     * @param modelType 模型类型（ship/plane/car）
     * @param supplier  实际的调用逻辑
     * @return 异步结果（ModelResult：成功/超时/失败）
     */
    private CompletableFuture<ModelResult> invokeModel(
            String modelType, Supplier<ModelDetectResponse> supplier) {
        return CompletableFuture
                .supplyAsync(supplier, executor)   // ① 交给虚拟线程执行器执行
                .thenApply(response -> ModelResult.success(modelType, response))   // ② 成功包一层
                .completeOnTimeout(ModelResult.timeout(modelType), 15, TimeUnit.SECONDS)   // ③ 15秒超时
                .exceptionally(ex -> ModelResult.failed(modelType, ex.getMessage()));   // ④ 异常处理
    }

    /**
     * 同 invokeModel，但用于瓦片路径（带信号量限流）。
     */
    private CompletableFuture<ModelResult> invokeModelGated(
            String modelType, Supplier<ModelDetectResponse> supplier) {
        return CompletableFuture
                .supplyAsync(supplier, executor)   // ① 交给虚拟线程执行器
                .thenApply(response -> ModelResult.success(modelType, response))   // ② 成功
                .completeOnTimeout(ModelResult.timeout(modelType), 15, TimeUnit.SECONDS)   // ③ 15秒超时
                .exceptionally(ex -> ModelResult.failed(modelType, ex.getMessage()));   // ④ 异常
    }

    /**
     * 合并单个模型的结果到总列表，给类别加前缀。
     * @param result        单个模型的调用结果
     * @param prefix        前缀（如 "[ship]"）
     * @param allDetections 总结果列表
     */
    private void mergeResult(ModelResult result, String prefix,
                             List<Detection> allDetections) {
        if (result == null || !result.success()) {   // 如果调用失败/超时
            if (result != null) {   // 如果不是完全 null
                log.warn("{} model failed: {}", result.modelType(), result.error());   // 打印警告
            }
            return;   // 跳过（降级）
        }
        for (Detection d : result.response().getDetectionsList()) {   // 遍历该模型的结果
            allDetections.add(d.toBuilder()   // 复制并修改
                    .setClassName(prefix + " " + d.getClassName()).build());   // 类别名加前缀
        }
    }

    // ================================================================
    //  标注绘制
    // ================================================================

    /**
     * 在图片上画所有检测框。
     * @param fullImage  原图
     * @param detections 检测结果
     * @return 画好框的 JPEG 字节
     */
    private byte[] drawAllDetections(BufferedImage fullImage, List<Detection> detections) {
        try {
            List<DetResult> results = new ArrayList<>();   // 转成 DetResult 列表
            for (Detection d : detections) {   // 遍历每个检测
                DetResult r = new DetResult();   // 创建 DetResult
                r.classId = d.getClassId();   // 类别
                r.className = d.getClassName();   // 类别名
                r.confidence = d.getConfidence();   // 置信度
                r.x1 = d.getBbox().getX1(); r.y1 = d.getBbox().getY1();   // 四角点
                r.x2 = d.getBbox().getX2(); r.y2 = d.getBbox().getY2();
                r.x3 = d.getBbox().getX3(); r.y3 = d.getBbox().getY3();
                r.x4 = d.getBbox().getX4(); r.y4 = d.getBbox().getY4();
                results.add(r);   // 加入列表
            }

            BufferedImage annotated = YoloInferenceEngine.drawDetections(fullImage, results);   // 画框
            return ImageUtils.toJpegBytes(annotated);   // 转 JPEG 字节
        } catch (Exception e) {   // 画框失败
            log.error("Failed to draw detections", e);   // 打印错误
            try {
                return ImageUtils.toJpegBytes(fullImage);   // 返回原图（不崩溃）
            } catch (Exception ex) {
                return new byte[0];   // 实在失败返回空
            }
        }
    }

    // ================================================================
    //  服务探活
    // ================================================================

    /**
     * 快速探测哪些模型服务可用（避免裁切时对死服务的大量无效调用）。
     *
     * @param targets 要探测的模型类型
     * @return 存活的模型集合
     */
    // 最小的有效 JPEG（1x1 灰色像素），用于快速探活
    private static final byte[] PROBE_JPEG = new byte[] {
        (byte)0xFF,(byte)0xD8,(byte)0xFF,(byte)0xE0,0x00,0x10,0x4A,0x46,
        0x49,0x46,0x00,0x01,0x01,0x00,0x00,0x01,0x00,0x01,0x00,0x00,
        (byte)0xFF,(byte)0xDB,0x00,0x43,0x00,0x08,0x06,0x06,0x07,0x06,
        0x05,0x08,0x07,0x07,0x07,0x09,0x09,0x08,0x0A,0x0C,0x14,0x0D,
        0x0C,0x0B,0x0B,0x0C,0x19,0x12,0x13,0x0F,0x14,0x1D,0x1A,0x1F,
        0x1E,0x1D,0x1A,0x1C,0x1C,0x20,0x24,0x2E,0x27,0x20,0x22,0x2C,
        0x23,0x1C,0x1C,0x28,0x37,0x29,0x2C,0x30,0x31,0x34,0x34,0x34,
        0x1F,0x27,0x39,0x3D,0x38,0x32,0x3C,0x2E,0x33,0x34,0x32,(byte)0xFF,
        (byte)0xC0,0x00,0x0B,0x08,0x00,0x01,0x00,0x01,0x01,0x01,0x11,
        0x00,(byte)0xFF,(byte)0xC4,0x00,0x1F,0x00,0x00,0x01,0x05,0x01,
        0x01,0x01,0x01,0x01,0x01,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0A,0x0B,
        (byte)0xFF,(byte)0xDA,0x00,0x08,0x01,0x01,0x00,0x00,0x3F,0x00,
        0x37,(byte)0x80,0x01,0x01,(byte)0xFF,(byte)0xD9
    };

    private java.util.Set<String> probeLiveServices(java.util.Set<String> targets) {
        java.util.Set<String> live = new java.util.LinkedHashSet<>();   // 存活的模型集合

        for (String t : targets) {   // 遍历要探测的每个模型
            try {
                ModelDetectRequest probeReq = ModelDetectRequest.newBuilder()   // 构建探活请求
                        .setImageData(com.google.protobuf.ByteString.copyFrom(PROBE_JPEG))   // 用 1x1 灰图
                        .setConfThreshold(0.5f)
                        .build();
                CompletableFuture<ModelDetectResponse> f = CompletableFuture.supplyAsync(() -> {   // 异步调模型
                    return switch (t) {   // 根据类型调对应服务
                        case "ship" -> shipService.detect(probeReq);   // 调 ship
                        case "plane" -> planeService.detect(probeReq);   // 调 plane
                        case "car" -> carService.detect(probeReq);   // 调 car
                        default -> throw new IllegalStateException("Unknown: " + t);
                    };
                }, executor);
                f.get(2, TimeUnit.SECONDS);   // 2秒内能返回就算活着
                live.add(t);   // 加入存活集合
                log.info("Service probe: {} is LIVE", t);   // 打印存活
            } catch (Exception e) {
                log.warn("Service probe: {} is DOWN ({})", t, e.getMessage());   // 探活失败 = 挂了
            }
        }
        if (live.isEmpty() && !targets.isEmpty()) {   // 如果一个都不活（极端情况）
            live.add(targets.iterator().next());   // 至少保留一个，让错误正常传播
        }
        return live;   // 返回存活的模型集合
    }

    // ================================================================
    //  外部 GSD 解析
    // ================================================================

    /**
     * 从 data_source 中解析选中的目标模型。
     * 格式: "...:targets=ship,plane,car:..."
     * 未指定时默认全部选中。
     *
     * @param dataSource data_source 字符串
     * @return 选中的目标集合
     */
    private static java.util.Set<String> parseTargets(String dataSource) {
        java.util.Set<String> targets = new java.util.LinkedHashSet<>();   // 目标集合
        if (dataSource == null || !dataSource.contains(":targets=")) {   // 如果没有 targets 字段
            targets.add("ship");   // 默认全选
            targets.add("plane");
            targets.add("car");
            return targets;   // 返回默认
        }
        try {
            for (String part : dataSource.split(":")) {   // 按冒号分割
                if (part.startsWith("targets=")) {   // 找到 targets 段
                    String val = part.substring(8);   // 取值（跳过 "targets="）
                    for (String t : val.split(",")) {   // 按逗号分割
                        t = t.trim();   // 去掉空格
                        if (!t.isEmpty()) targets.add(t);   // 加入集合
                    }
                }
            }
        } catch (Exception e) {   // 解析异常
            log.warn("Failed to parse targets from data_source: {}", dataSource);
        }
        if (targets.isEmpty()) {   // 解析完为空
            targets.add("ship");   // 至少保留一个
        }
        return targets;   // 返回
    }

    /**
     * 从 data_source 中解析外部 GSD（地面采样距离）。
     * 格式: "drone:gsd=0.05:lat=30.1:lng=120.2"
     *
     * @param dataSource data_source 字符串
     * @return GSD 值，未找到返回 -1
     */
    private static float parseGsd(String dataSource) {
        if (dataSource == null || !dataSource.contains(":gsd=")) return -1;   // 没有 gsd 就返回 -1
        try {
            for (String part : dataSource.split(":")) {   // 按冒号分割
                if (part.startsWith("gsd=")) {   // 找到 gsd 段
                    return Float.parseFloat(part.substring(4));   // 解析数值
                }
            }
        } catch (NumberFormatException e) {   // 数值解析失败
            log.warn("Failed to parse GSD from data_source: {}", dataSource);
        }
        return -1;   // 返回 -1
    }

    // ================================================================
    //  内部类型
    // ================================================================

    /**
     * 模型调用结果封装。
     * @param modelType 模型类型
     * @param response  模型返回的响应
     * @param success   是否成功
     * @param error     错误信息
     */
    private record ModelResult(
            String modelType,   // 模型类型（ship/plane/car）
            ModelDetectResponse response,   // 成功的响应
            boolean success,   // 是否成功
            String error   // 错误信息
    ) {
        static ModelResult success(String type, ModelDetectResponse resp) {   // 成功结果
            return new ModelResult(type, resp, true, null);
        }
        static ModelResult timeout(String type) {   // 超时结果
            return new ModelResult(type, null, false, "timeout");
        }
        static ModelResult failed(String type, String err) {   // 失败结果
            return new ModelResult(type, null, false, err);
        }
    }
}
