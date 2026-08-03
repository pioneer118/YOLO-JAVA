package com.yolo.gateway;

import com.yolo.inference.ImageTiler;
import com.yolo.inference.ImageUtils;
import com.yolo.inference.YoloInferenceEngine;
import com.yolo.inference.DetResult;
import com.yolo.proto.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 网关聚合服务实现。
 *
 * <p>两种检测路径：
 * <ol>
 *   <li><b>普通路径</b>：小图（≤自动裁切阈值）→ 直接扇出 3 模型 → 聚合 → 标注</li>
 *   <li><b>裁切路径</b>：大图 → 自适应裁切 → 分批并行 tile 检测 → 坐标偏移 → 跨 tile NMS → 标注</li>
 * </ol>
 */
@DubboService(version = "1.0.0")
public class YoloAggregationServiceImpl implements YoloAggregationService {

    private static final Logger log = LoggerFactory.getLogger(YoloAggregationServiceImpl.class);

    // ---- Dubbo 引用：三个后端模型服务 ----

    @DubboReference(group = "ship", version = "1.0.0", check = false)
    private ModelInferenceService shipService;

    @DubboReference(group = "plane", version = "1.0.0", check = false)
    private ModelInferenceService planeService;

    @DubboReference(group = "car", version = "1.0.0", check = false)
    private ModelInferenceService carService;

    // ---- 注入 ----

    @Autowired
    private ExecutorService executor;

    /** 并发 tile 上限（避免冲垮后端） */
    @Value("${yolo.tiling.max-concurrent-tiles:12}")
    private int maxConcurrentTiles;

    /** 单批次处理 tile 数（每一批内并行扇出模型） */
    private static final int BATCH_SIZE = 8;

    /** 跨 tile NMS 的 IoU 阈值 */
    private static final float CROSS_TILE_IOU_THRESHOLD = 0.5f;

    // ================================================================
    //  入口
    // ================================================================

    @Override
    public AggregateDetectResponse detect(AggregateDetectRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            byte[] imageBytes = request.getImageData().toByteArray();
            float confThreshold = request.getConfThreshold() > 0
                    ? request.getConfThreshold() : 0.5f;

            // 1. 解析外部 GSD（从 data_source 字段提取）
            //    格式: "drone:gsd=0.05:lat=30.1:lng=120.2" 或普通的 "satellite"/"drone"
            float externalGsd = parseGsd(request.getDataSource());

            // 2. 分析图片，获取自适应裁切策略（优先外部 GSD）
            ImageTiler.TilingStrategy strategy = ImageTiler.analyze(imageBytes, externalGsd);

            // 2. 用 OpenCV 解码图片（支持 TIFF）
            BufferedImage fullImage = ImageUtils.loadImage(imageBytes);
            int imgW = fullImage.getWidth();
            int imgH = fullImage.getHeight();

            // 3. 判断是否需要裁切
            if (ImageTiler.shouldTile(imgW, imgH, strategy)) {
                log.info("Tiling enabled: {}x{} image, strategy={}, tileSize={}",
                        imgW, imgH, strategy.level(), strategy.tileSize());
                return detectWithTiling(fullImage, request, strategy, startTime);
            } else {
                return detectNormal(fullImage, request, confThreshold, startTime);
            }
        } catch (Exception e) {
            log.error("Detection failed", e);
            return AggregateDetectResponse.newBuilder()
                    .setTotalCount(0)
                    .setProcessingTimeMs((int) (System.currentTimeMillis() - startTime))
                    .setAnnotatedImage(com.google.protobuf.ByteString.copyFrom(
                            request.getImageData().toByteArray()))
                    .build();
        }
    }

    // ================================================================
    //  路径一：普通检测（不裁切）
    // ================================================================

    private AggregateDetectResponse detectNormal(BufferedImage fullImage,
                                                  AggregateDetectRequest request,
                                                  float confThreshold,
                                                  long startTime) throws Exception {
        ModelDetectRequest modelRequest = ModelDetectRequest.newBuilder()
                .setImageData(request.getImageData())
                .setConfThreshold(confThreshold)
                .setDataSource(request.getDataSource())
                .build();

        // 解析要调用的目标服务
        java.util.Set<String> targets = parseTargets(request.getDataSource());
        boolean multiTarget = targets.size() > 1;

        List<CompletableFuture<ModelResult>> futures = new ArrayList<>();
        if (targets.contains("ship"))  futures.add(invokeModel("ship",  () -> shipService.detect(modelRequest)));
        if (targets.contains("plane")) futures.add(invokeModel("plane", () -> planeService.detect(modelRequest)));
        if (targets.contains("car"))   futures.add(invokeModel("car",   () -> carService.detect(modelRequest)));

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<Detection> allDetections = new ArrayList<>();
        for (CompletableFuture<ModelResult> f : futures) {
            ModelResult r = f.getNow(null);
            if (r != null && r.success()) {
                String prefix = multiTarget ? "[" + r.modelType() + "]" : "";
                mergeResult(r, prefix, allDetections);
            }
        }

        byte[] annotatedBytes = drawAllDetections(fullImage, allDetections);

        return AggregateDetectResponse.newBuilder()
                .setTotalCount(allDetections.size())
                .addAllDetections(allDetections)
                .setAnnotatedImage(com.google.protobuf.ByteString.copyFrom(annotatedBytes))
                .setProcessingTimeMs((int) (System.currentTimeMillis() - startTime))
                .build();
    }

    // ================================================================
    //  路径二：裁切检测
    // ================================================================

    private AggregateDetectResponse detectWithTiling(BufferedImage fullImage,
                                                      AggregateDetectRequest request,
                                                      ImageTiler.TilingStrategy strategy,
                                                      long startTime) throws Exception {
        // 1. BufferedImage → OpenCV Mat（通过 JPEG 往返，避免 imdecode 解码 TIFF 失败）
        byte[] jpegBytes = ImageUtils.toJpegBytes(fullImage);
        org.bytedeco.opencv.opencv_core.Mat fullMat =
                org.bytedeco.opencv.global.opencv_imgcodecs.imdecode(
                        new org.bytedeco.opencv.opencv_core.Mat(jpegBytes),
                        org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_COLOR);

        // 2. 裁切
        List<ImageTiler.Tile> tiles = ImageTiler.tile(fullMat, strategy);
        fullMat.close();

        log.info("Image tiled into {} tiles (strategy={}, tileSize={})",
                tiles.size(), strategy.level(), strategy.tileSize());

        // 3. 快速探活：检测哪些模型服务可用（1s 超时，避免死服务拖慢整个流程）
        java.util.Set<String> targets = parseTargets(request.getDataSource());
        java.util.Set<String> liveServices = probeLiveServices(targets);

        // 4. 分批并行处理所有 tile（只调活着的服务）
        Semaphore semaphore = new Semaphore(maxConcurrentTiles);
        List<Detection> allDetections = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < tiles.size(); i += BATCH_SIZE) {
            int batchEnd = Math.min(i + BATCH_SIZE, tiles.size());
            List<CompletableFuture<Void>> batch = new ArrayList<>();

            for (int j = i; j < batchEnd; j++) {
                ImageTiler.Tile tile = tiles.get(j);
                batch.add(processOneTile(tile, request, semaphore, liveServices)
                        .thenAccept(tileDets -> {
                            if (!tileDets.isEmpty()) {
                                allDetections.addAll(tileDets);
                            }
                        }));
            }

            CompletableFuture.allOf(batch.toArray(new CompletableFuture[0])).join();

            if (i % (BATCH_SIZE * 5) == 0 || batchEnd == tiles.size()) {
                log.info("Tile progress: {}/{} tiles processed, {} detections so far",
                        batchEnd, tiles.size(), allDetections.size());
            }
        }

        // 4. 跨 tile 全局 NMS 去重
        List<Detection> merged = crossTileNMS(allDetections);

        // 5. 在原图上绘制
        byte[] annotatedBytes = drawAllDetections(fullImage, merged);

        int elapsed = (int) (System.currentTimeMillis() - startTime);
        log.info("Tiling detection complete: {} tiles → {} raw detections → {} after NMS, {}ms",
                tiles.size(), allDetections.size(), merged.size(), elapsed);

        return AggregateDetectResponse.newBuilder()
                .setTotalCount(merged.size())
                .addAllDetections(merged)
                .setAnnotatedImage(com.google.protobuf.ByteString.copyFrom(annotatedBytes))
                .setProcessingTimeMs(elapsed)
                .build();
    }

    /**
     * 处理单个 tile：扇出到 3 个模型，收集结果并偏移坐标。
     */
    private CompletableFuture<List<Detection>> processOneTile(ImageTiler.Tile tile,
                                                               AggregateDetectRequest request,
                                                               Semaphore semaphore,
                                                               java.util.Set<String> liveServices) {
        float confThreshold = request.getConfThreshold() > 0
                ? request.getConfThreshold() : 0.5f;

        ModelDetectRequest modelRequest = ModelDetectRequest.newBuilder()
                .setImageData(com.google.protobuf.ByteString.copyFrom(tile.imageBytes()))
                .setConfThreshold(confThreshold)
                .setDataSource(request.getDataSource())
                .build();

        boolean multiTarget = liveServices.size() > 1;

        List<CompletableFuture<ModelResult>> futures = new ArrayList<>();
        if (liveServices.contains("ship"))  futures.add(invokeModelGated("ship",
                () -> callGated(semaphore, () -> shipService.detect(modelRequest))));
        if (liveServices.contains("plane")) futures.add(invokeModelGated("plane",
                () -> callGated(semaphore, () -> planeService.detect(modelRequest))));
        if (liveServices.contains("car"))   futures.add(invokeModelGated("car",
                () -> callGated(semaphore, () -> carService.detect(modelRequest))));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Detection> dets = new ArrayList<>();
                    for (CompletableFuture<ModelResult> f : futures) {
                        ModelResult r = f.getNow(null);
                        if (r != null && r.success()) {
                            String prefix = multiTarget ? "[" + r.modelType() + "]" : "";
                            mergeResult(r, prefix, dets);
                        }
                    }
                    // 坐标从 tile 局部空间偏移到原图全局空间
                    return ImageTiler.offsetToOriginal(dets, tile.x(), tile.y());
                });
    }

    private <T> T callGated(Semaphore semaphore, Supplier<T> supplier) {
        try {
            semaphore.acquire();
            return supplier.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted", e);
        } finally {
            semaphore.release();
        }
    }

    // ================================================================
    //  跨 tile NMS
    // ================================================================

    /**
     * 对所有 tile 汇总的检测结果执行全局 NMS。
     *
     * <p>瓦片间重叠区域中，同一目标可能被相邻两个 tile 都检测到。
     * 坐标已偏移到原图空间，此处直接用标准 NMS 去重即可。
     *
     * <p>实现复用了 YoloInferenceEngine 内置的 NMS 逻辑
     * （间接通过 drawDetections 调用，此处做预过滤）。
     */
    private List<Detection> crossTileNMS(List<Detection> detections) {
        if (detections.size() <= 1) return detections;

        // 转为 DetResult 用于 NMS 计算
        List<DetResult> results = new ArrayList<>();
        for (Detection d : detections) {
            DetResult r = new DetResult();
            r.classId = d.getClassId();
            r.className = d.getClassName();
            r.confidence = d.getConfidence();
            r.x1 = d.getBbox().getX1(); r.y1 = d.getBbox().getY1();
            r.x2 = d.getBbox().getX2(); r.y2 = d.getBbox().getY2();
            r.x3 = d.getBbox().getX3(); r.y3 = d.getBbox().getY3();
            r.x4 = d.getBbox().getX4(); r.y4 = d.getBbox().getY4();
            r.cx = (r.x1 + r.x3) / 2f;
            r.cy = (r.y1 + r.y3) / 2f;
            results.add(r);
        }

        // 按置信度降序排序
        results.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        // 贪心 NMS
        int n = results.size();
        boolean[] suppressed = new boolean[n];
        List<DetResult> kept = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (suppressed[i]) continue;
            DetResult best = results.get(i);
            kept.add(best);

            for (int j = i + 1; j < n; j++) {
                if (suppressed[j]) continue;
                if (best.classId != results.get(j).classId) continue;

                float iou = computePolygonIoU(
                        new float[][]{
                                {best.x1, best.y1}, {best.x2, best.y2},
                                {best.x3, best.y3}, {best.x4, best.y4}},
                        new float[][]{
                                {results.get(j).x1, results.get(j).y1},
                                {results.get(j).x2, results.get(j).y2},
                                {results.get(j).x3, results.get(j).y3},
                                {results.get(j).x4, results.get(j).y4}}
                );
                if (iou > CROSS_TILE_IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }

        // 转回 Protobuf Detection
        List<Detection> merged = new ArrayList<>();
        for (DetResult r : kept) {
            merged.add(Detection.newBuilder()
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
        return merged;
    }

    /**
     * 计算两个四边形的 IoU（Sutherland-Hodgman 裁剪 + 鞋带公式面积）。
     */
    private float computePolygonIoU(float[][] polyA, float[][] polyB) {
        float areaA = polygonArea(polyA);
        float areaB = polygonArea(polyB);
        if (areaA <= 0 || areaB <= 0) return 0;

        float[][] clipped = clipPolygon(polyA, polyB);
        float interArea = polygonArea(clipped);

        return interArea / (areaA + areaB - interArea + 1e-6f);
    }

    private float polygonArea(float[][] poly) {
        float area = 0;
        int n = poly.length;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += poly[i][0] * poly[j][1];
            area -= poly[j][0] * poly[i][1];
        }
        return 0.5f * Math.abs(area);
    }

    private float[][] clipPolygon(float[][] subject, float[][] clip) {
        float[][] output = subject;
        int n = clip.length;
        for (int i = 0; i < n; i++) {
            if (output.length == 0) return output;
            int j = (i + 1) % n;
            output = clipAgainstEdge(output, clip[i], clip[j]);
        }
        return output;
    }

    private float[][] clipAgainstEdge(float[][] poly, float[] edgeA, float[] edgeB) {
        if (poly.length == 0) return poly;
        List<float[]> out = new ArrayList<>();
        float edgeDx = edgeB[0] - edgeA[0];
        float edgeDy = edgeB[1] - edgeA[1];

        for (int i = 0; i < poly.length; i++) {
            float[] cur = poly[i];
            float[] prev = poly[(i + poly.length - 1) % poly.length];

            float curCross  = edgeDx * (cur[1]  - edgeA[1]) - edgeDy * (cur[0]  - edgeA[0]);
            float prevCross = edgeDx * (prev[1] - edgeA[1]) - edgeDy * (prev[0] - edgeA[0]);

            if (prevCross >= 0) {
                if (curCross < 0) {
                    out.add(lineIntersection(prev, cur, edgeA, edgeB));
                }
            } else if (curCross >= 0) {
                out.add(lineIntersection(prev, cur, edgeA, edgeB));
                out.add(cur);
            }
        }
        return out.toArray(new float[0][]);
    }

    private float[] lineIntersection(float[] p1, float[] p2, float[] p3, float[] p4) {
        float x1 = p1[0], y1 = p1[1], x2 = p2[0], y2 = p2[1];
        float x3 = p3[0], y3 = p3[1], x4 = p4[0], y4 = p4[1];

        float den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(den) < 1e-10f) return new float[]{x2, y2};

        float t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den;
        return new float[]{x1 + t * (x2 - x1), y1 + t * (y2 - y1)};
    }

    // ================================================================
    //  模型调用
    // ================================================================

    private CompletableFuture<ModelResult> invokeModel(
            String modelType, Supplier<ModelDetectResponse> supplier) {
        return CompletableFuture
                .supplyAsync(supplier, executor)
                .thenApply(response -> ModelResult.success(modelType, response))
                .completeOnTimeout(ModelResult.timeout(modelType), 5, TimeUnit.SECONDS)
                .exceptionally(ex -> ModelResult.failed(modelType, ex.getMessage()));
    }

    private CompletableFuture<ModelResult> invokeModelGated(
            String modelType, Supplier<ModelDetectResponse> supplier) {
        return CompletableFuture
                .supplyAsync(supplier, executor)
                .thenApply(response -> ModelResult.success(modelType, response))
                .completeOnTimeout(ModelResult.timeout(modelType), 10, TimeUnit.SECONDS)
                .exceptionally(ex -> ModelResult.failed(modelType, ex.getMessage()));
    }

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

    // ================================================================
    //  标注绘制
    // ================================================================

    private byte[] drawAllDetections(BufferedImage fullImage, List<Detection> detections) {
        try {
            List<DetResult> results = new ArrayList<>();
            for (Detection d : detections) {
                DetResult r = new DetResult();
                r.classId = d.getClassId();
                r.className = d.getClassName();
                r.confidence = d.getConfidence();
                r.x1 = d.getBbox().getX1(); r.y1 = d.getBbox().getY1();
                r.x2 = d.getBbox().getX2(); r.y2 = d.getBbox().getY2();
                r.x3 = d.getBbox().getX3(); r.y3 = d.getBbox().getY3();
                r.x4 = d.getBbox().getX4(); r.y4 = d.getBbox().getY4();
                results.add(r);
            }

            BufferedImage annotated = YoloInferenceEngine.drawDetections(fullImage, results);
            return ImageUtils.toJpegBytes(annotated);
        } catch (Exception e) {
            log.error("Failed to draw detections", e);
            try {
                return ImageUtils.toJpegBytes(fullImage);
            } catch (Exception ex) {
                return new byte[0];
            }
        }
    }

    // ================================================================
    //  服务探活
    // ================================================================

    /**
     * 快速探测哪些模型服务可用（1s 超时）。
     * 避免裁切时对死服务的大量无效调用。
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
        java.util.Set<String> live = new java.util.LinkedHashSet<>();

        for (String t : targets) {
            try {
                ModelDetectRequest probeReq = ModelDetectRequest.newBuilder()
                        .setImageData(com.google.protobuf.ByteString.copyFrom(PROBE_JPEG))
                        .setConfThreshold(0.5f)
                        .build();
                CompletableFuture<ModelDetectResponse> f = CompletableFuture.supplyAsync(() -> {
                    return switch (t) {
                        case "ship" -> shipService.detect(probeReq);
                        case "plane" -> planeService.detect(probeReq);
                        case "car" -> carService.detect(probeReq);
                        default -> throw new IllegalStateException("Unknown: " + t);
                    };
                }, executor);
                f.get(2, TimeUnit.SECONDS);
                live.add(t);
                log.info("Service probe: {} is LIVE", t);
            } catch (Exception e) {
                log.warn("Service probe: {} is DOWN ({})", t, e.getMessage());
            }
        }
        if (live.isEmpty() && !targets.isEmpty()) {
            live.add(targets.iterator().next()); // 至少保留一个，让错误正常传播
        }
        return live;
    }

    // ================================================================
    //  外部 GSD 解析
    // ================================================================

    /**
     * 从 data_source 字段中提取外部 GSD。
     *
     * <p>格式：前端在 data_source 后追加 ":gsd=X:lat=Y:lng=Z"
     * 例如 "drone:gsd=0.05:lat=30.1:lng=120.2"
     *
     * @return GSD 值（米/像素），未找到返回 -1
     */
    /**
     * 从 data_source 中解析选中的目标模型。
     * 格式: "...:targets=ship,plane,car:..."
     * 未指定时默认全部选中。
     */
    private static java.util.Set<String> parseTargets(String dataSource) {
        java.util.Set<String> targets = new java.util.LinkedHashSet<>();
        if (dataSource == null || !dataSource.contains(":targets=")) {
            targets.add("ship");
            targets.add("plane");
            targets.add("car");
            return targets;
        }
        try {
            for (String part : dataSource.split(":")) {
                if (part.startsWith("targets=")) {
                    String val = part.substring(8);
                    for (String t : val.split(",")) {
                        t = t.trim();
                        if (!t.isEmpty()) targets.add(t);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse targets from data_source: {}", dataSource);
        }
        // 至少保留一个
        if (targets.isEmpty()) {
            targets.add("ship");
        }
        return targets;
    }

    private static float parseGsd(String dataSource) {
        if (dataSource == null || !dataSource.contains(":gsd=")) return -1;
        try {
            for (String part : dataSource.split(":")) {
                if (part.startsWith("gsd=")) {
                    return Float.parseFloat(part.substring(4));
                }
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse GSD from data_source: {}", dataSource);
        }
        return -1;
    }

    // ================================================================
    //  内部类型
    // ================================================================

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
}
