package com.yolo.inference;

// ai.onnxruntime.*：ONNX Runtime 的 Java API（加载模型、执行推理）
import ai.onnxruntime.*;
// BytePointer/FloatPointer/IntPointer：JavaCV 的指针类（操作底层内存数据）
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
// opencv_core/imgcodecs/imgproc：OpenCV 的核心/编解码/图像处理函数
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
// opencv_core.*：OpenCV 的数据类型（Mat/Point/Size/Scalar 等）
import org.bytedeco.opencv.opencv_core.*;

import javax.imageio.ImageIO;        // Java 图片读写
import java.awt.image.BufferedImage; // Java 图片对象
import java.io.ByteArrayInputStream; // 字节输入流
import java.io.ByteArrayOutputStream;// 字节输出流
import java.io.IOException;          // IO 异常
import java.nio.FloatBuffer;         // 浮点缓冲区（构建 ONNX 输入用）
import java.util.*;                  // 集合工具类
import java.util.logging.Logger;     // 日志

/**
 * YOLO 推理引擎（核心类）。
 *
 * <p>封装了完整的检测流程：
 * <ol>
 *   <li>预处理：图片缩放/归一化/格式转换</li>
 *   <li>推理：调用 ONNX Runtime 执行模型</li>
 *   <li>后处理：解析模型输出为检测框</li>
 *   <li>NMS：去除重复检测框</li>
 * </ol>
 *
 * <p>该类是线程安全的：所有字段构造后不可变，detect() 不修改引擎状态。
 */
public class YoloInferenceEngine implements AutoCloseable {

    private static final Logger log = Logger.getLogger(YoloInferenceEngine.class.getName());

    private static final int INPUT_SIZE = 1024;   // 模型输入尺寸（1024×1024，训练时的尺寸）
    private static final int NUM_CHANNELS = 3;    // 颜色通道数（RGB 三通道）

    /** 中心点距离阈值（像素）：两框中心点小于该值视为同一目标的重复预测（模型会输出大量角度不同的重复框） */
    private static final float CENTER_DUP_DISTANCE = 20f;

    // 检测框的 15 种颜色（BGR 顺序），按类别编号取色
    private static final int[][] COLORS_BGR = {
        {255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 0},
        {255, 0, 255}, {0, 255, 255}, {128, 0, 0}, {0, 128, 0},
        {0, 0, 128}, {128, 128, 0}, {128, 0, 128}, {0, 128, 128},
        {64, 128, 0}, {192, 128, 0}, {64, 0, 128}
    };

    private final OrtEnvironment env;       // ONNX Runtime 环境（全局唯一，加载模型需要）
    private final OrtSession session;       // ONNX 推理会话（真正执行推理的东西）
    private final List<String> classNames;  // 类别名称列表

    /**
     * 构造推理引擎：加载 ONNX 模型。
     *
     * @param modelPath  模型文件路径（如 models/bestship.onnx）
     * @param classNames 类别名称列表
     * @throws OrtException 模型加载失败
     */
    public YoloInferenceEngine(String modelPath, List<String> classNames) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();   // 获取 ONNX Runtime 全局环境
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();   // 会话配置
        this.session = env.createSession(modelPath, opts);   // ★ 加载模型，创建推理会话
        this.classNames = Collections.unmodifiableList(new ArrayList<>(classNames));   // 类别名（不可变）
    }

    /**
     * 对单张图片执行目标检测（4 步：预处理→推理→后处理→NMS）。
     *
     * @param image   输入图片
     * @param options 检测参数（置信度阈值、IoU 阈值）
     * @return 检测结果列表
     * @throws OrtException 推理失败
     * @throws IOException  图片处理失败
     */
    public List<DetResult> detect(BufferedImage image, DetectionOptions options) throws OrtException, IOException {
        // Step 1: 预处理（图片 → 模型能吃的 float 数组）
        float[] inputData = preprocess(image);

        // Step 2: ONNX 推理（喂给模型，得到原始输出 [1, N, 7]）
        float[][][] output = runInference(inputData);

        // Step 3: 后处理（原始输出 → 检测框）
        List<DetResult> detections = postprocess(output, image.getWidth(), image.getHeight(), options);

        // Step 4: NMS（去重）
        return applyNMS(detections, options.iouThreshold());
    }

    // ---- 预处理 ----

    /**
     * 预处理：把 BufferedImage 变成模型能吃的 float 数组。
     *
     * @param image 输入图片
     * @return 模型输入数据（1×3×1024×1024 展开的一维数组）
     * @throws IOException 图片处理失败
     */
    private float[] preprocess(BufferedImage image) throws IOException {
        // ① 图片 → JPEG 字节 → OpenCV Mat（BGR 格式）
        byte[] jpegBytes = bufferedImageToJpegBytes(image);
        Mat bgr = opencv_imgcodecs.imdecode(new Mat(jpegBytes), opencv_imgcodecs.IMREAD_COLOR);

        // ② BGR → RGB（OpenCV 默认 BGR，模型要 RGB）
        Mat rgb = new Mat();
        opencv_imgproc.cvtColor(bgr, rgb, opencv_imgproc.COLOR_BGR2RGB);

        // ③ 缩放到 1024×1024（模型固定输入大小）
        Mat resized = new Mat();
        opencv_imgproc.resize(rgb, resized, new Size(INPUT_SIZE, INPUT_SIZE));

        // ④ 转成浮点数，除以 255（像素值 0-255 → 0.0-1.0，归一化）
        Mat floatMat = new Mat();
        resized.convertTo(floatMat, opencv_core.CV_32FC3, 1.0 / 255.0, 0.0);

        // ⑤ 取出数据，重排成模型要的 CHW 顺序
        float[] chw = new float[NUM_CHANNELS * INPUT_SIZE * INPUT_SIZE];   // 目标数组（CHW）
        FloatPointer fp = new FloatPointer(floatMat.ptr());   // 指向 Mat 数据
        float[] hwc = new float[INPUT_SIZE * INPUT_SIZE * NUM_CHANNELS];   // 原始数据（HWC）
        fp.get(hwc);   // 把 Mat 数据复制到 hwc 数组

        // HWC → CHW（颜色通道维从最后一维换到第一维）
        for (int c = 0; c < NUM_CHANNELS; c++) {        // 遍历通道（R/G/B）
            for (int h = 0; h < INPUT_SIZE; h++) {       // 遍历高
                for (int w = 0; w < INPUT_SIZE; w++) {   // 遍历宽
                    int hwcIdx = h * INPUT_SIZE * NUM_CHANNELS + w * NUM_CHANNELS + c;   // HWC 索引
                    int chwIdx = c * INPUT_SIZE * INPUT_SIZE + h * INPUT_SIZE + w;       // CHW 索引
                    chw[chwIdx] = hwc[hwcIdx];   // 复制数据
                }
            }
        }

        bgr.close(); rgb.close(); resized.close(); floatMat.close();   // 释放资源
        return chw;   // 返回模型输入数据
    }

    // ---- ONNX 推理 ----

    /**
     * 执行 ONNX 推理。
     *
     * @param inputData 预处理后的输入数据
     * @return 模型原始输出 [1, N, 7]
     * @throws OrtException 推理失败
     */
    private float[][][] runInference(float[] inputData) throws OrtException {
        // ① 把一维数组包装成模型要的 [1, 3, 1024, 1024] 张量
        OnnxTensor inputTensor = OnnxTensor.createTensor(env,
            FloatBuffer.wrap(inputData),
            new long[]{1, NUM_CHANNELS, INPUT_SIZE, INPUT_SIZE});

        // ② 输入名是 "images"
        Map<String, OnnxTensor> inputs = Map.of("images", inputTensor);

        float[][][] output;
        try (OrtSession.Result result = session.run(inputs)) {   // ③ 执行推理！
            OnnxTensor outputTensor = (OnnxTensor) result.get("output0").get();   // ④ 取输出（名 "output0"）
            long[] shape = outputTensor.getInfo().getShape();   // 输出形状
            log.fine("ONNX output shape: " + java.util.Arrays.toString(shape));

            Object value = outputTensor.getValue();   // 输出数据
            if (value instanceof float[][][] raw) {   // 如果是三维浮点数组
                output = deepCopy(raw);   // 深拷贝（避免会话关闭后数据失效）
            } else {
                log.severe("Unexpected ONNX output type: " + value.getClass().getName());
                return new float[0][0][];   // 类型不对返回空
            }
        }
        // 注意：ONNX Runtime ≥1.26 会在 session.run 内部关闭输入张量，不要重复 close

        return output;
    }

    /**
     * 深拷贝三维数组（避免原数据被修改/释放）。
     */
    private static float[][][] deepCopy(float[][][] src) {
        float[][][] dst = new float[src.length][][];
        for (int i = 0; i < src.length; i++) {
            dst[i] = new float[src[i].length][];
            for (int j = 0; j < src[i].length; j++) {
                dst[i][j] = src[i][j].clone();   // 克隆最内层数组
            }
        }
        return dst;
    }

    // ---- 后处理 ----

    /**
     * 后处理：把模型原始输出解析成检测框列表。
     *
     * @param output  模型输出 [1, N, 7] 或 [1, N, 6]
     * @param origW   原图宽度
     * @param origH   原图高度
     * @param options 检测参数
     * @return 检测结果列表
     */
    private List<DetResult> postprocess(float[][][] output, int origW, int origH,
                                         DetectionOptions options) {
        List<DetResult> detections = new ArrayList<>();

        if (output.length == 0 || output[0].length == 0) {   // 输出为空
            log.warning("ONNX output is empty: dims=[" + output.length + ","
                + (output.length > 0 ? output[0].length : 0) + "]");
            return detections;
        }

        int N = output[0].length;       // 候选框数量
        int K = output[0][0].length;    // 每个框的数值个数（7 或 6）
        log.fine("ONNX detections: N=" + N + ", K=" + K);

        // 缩放比例：模型输入(1024) → 原图
        float scaleX = (float) origW / INPUT_SIZE;
        float scaleY = (float) origH / INPUT_SIZE;

        for (int i = 0; i < N; i++) {   // 遍历每个候选框
            float[] det = output[0][i];

            DetResult r;
            if (K >= 7) {
                // ---- OBB 格式: [cx, cy, w, h, angle, cls_id, raw_conf] ----
                // 舰船/飞机模型用旋转框，第 7 列是原始 logits，需 sigmoid
                float rawConf = det[6];
                // ★ sigmoid：把任意实数变成 0~1 的概率
                float confidence = 1.0f / (1.0f + (float) Math.exp(-rawConf));
                if (confidence < options.confidenceThreshold()) continue;   // 低于阈值跳过

                float cx = det[0], cy = det[1], w = det[2], h = det[3], angle = det[4];
                int clsId = (int) det[5];   // 类别编号

                cx *= scaleX; cy *= scaleY;   // 坐标从 1024 → 原图
                w *= scaleX; h *= scaleY;

                float[] corners = obbToCorners(cx, cy, w, h, angle);   // ★ 旋转框 → 四角点

                r = new DetResult();
                r.classId = clsId;
                r.confidence = confidence;
                r.x1 = corners[0]; r.y1 = corners[1];   // 四角点
                r.x2 = corners[2]; r.y2 = corners[3];
                r.x3 = corners[4]; r.y3 = corners[5];
                r.x4 = corners[6]; r.y4 = corners[7];
                r.cx = cx; r.cy = cy; r.w = w; r.h = h; r.angle = angle;
            } else if (K >= 6) {
                // ---- 标准框格式: [x1, y1, x2, y2, conf, cls_id] ----
                // 车辆模型用标准框，置信度已是概率
                float confidence = det[4];   // 直接是概率，不用 sigmoid
                if (confidence < options.confidenceThreshold()) continue;

                float x1 = det[0] * scaleX, y1 = det[1] * scaleY;   // 坐标缩放
                float x2 = det[2] * scaleX, y2 = det[3] * scaleY;
                int clsId = (int) det[5];

                r = new DetResult();
                r.classId = clsId;
                r.confidence = confidence;
                r.x1 = x1; r.y1 = y1;   // 标准框（水平）转四角点
                r.x2 = x2; r.y2 = y1;
                r.x3 = x2; r.y3 = y2;
                r.x4 = x1; r.y4 = y2;
                r.cx = (x1 + x2) / 2f; r.cy = (y1 + y2) / 2f;   // 中心点
                r.w = x2 - x1; r.h = y2 - y1; r.angle = 0;      // 宽高、角度=0
            } else {
                continue;   // 不支持的输出格式
            }

            r.className = r.classId < classNames.size() ? classNames.get(r.classId) : "未知";   // 类别名
            detections.add(r);
        }

        return detections;
    }

    /**
     * 旋转框 (cx,cy,w,h,angle) → 四个角点。
     * 用三角函数把"中心+宽高+角度"转成"四个角坐标"。
     */
    static float[] obbToCorners(float cx, float cy, float w, float h, float angle) {
        double cosA = Math.cos(angle);   // 角度的余弦
        double sinA = Math.sin(angle);   // 角度的正弦
        double hw = w * 0.5;   // 半宽
        double hh = h * 0.5;   // 半高

        // 以中心为原点旋转，再平移回 (cx,cy)，得到四个角点
        return new float[]{
            (float) (cx - hw * cosA + hh * sinA), (float) (cy - hw * sinA - hh * cosA),   // 左上
            (float) (cx + hw * cosA + hh * sinA), (float) (cy + hw * sinA - hh * cosA),   // 右上
            (float) (cx + hw * cosA - hh * sinA), (float) (cy + hw * sinA + hh * cosA),   // 右下
            (float) (cx - hw * cosA - hh * sinA), (float) (cy - hw * sinA + hh * cosA),   // 左下
        };
    }

    // ---- NMS（非极大值抑制）----

    /**
     * NMS：去掉重复的检测框，只保留置信度最高的。
     *
     * @param detections   待去重的检测结果
     * @param iouThreshold IoU 阈值（重叠超过它就算重复）
     * @return 去重后的检测结果
     */
    private List<DetResult> applyNMS(List<DetResult> detections, float iouThreshold) {
        if (detections.isEmpty()) return detections;   // 空列表直接返回

        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));   // 按置信度降序

        int n = detections.size();
        boolean[] suppressed = new boolean[n];   // 标记哪些被抑制（去掉）

        // 预计算每个框的角点、面积和中心点（避免重复计算）
        float[][][] corners = new float[n][4][2];
        float[] areas = new float[n];
        float[][] centers = new float[n][2];
        for (int i = 0; i < n; i++) {
            DetResult d = detections.get(i);
            corners[i] = new float[][]{
                {d.x1, d.y1}, {d.x2, d.y2}, {d.x3, d.y3}, {d.x4, d.y4}};   // 四个角点
            areas[i] = polygonArea(corners[i]);   // 面积
            centers[i][0] = (d.x1 + d.x3) / 2f;   // 中心点 X（对角点平均）
            centers[i][1] = (d.y1 + d.y3) / 2f;   // 中心点 Y
        }

        List<DetResult> kept = new ArrayList<>();   // 保留的框
        for (int i = 0; i < n; i++) {   // 遍历（从置信度最高的开始）
            if (suppressed[i]) continue;   // 已被抑制就跳过
            DetResult best = detections.get(i);   // 当前最高置信度的框
            kept.add(best);   // 保留它

            for (int j = i + 1; j < n; j++) {   // 遍历后面置信度更低的框
                if (suppressed[j]) continue;

                float iou = polygonIoU(corners[i], corners[j], areas[i], areas[j]);   // 算重叠
                float dx = centers[i][0] - centers[j][0];
                float dy = centers[i][1] - centers[j][1];
                float centerDist = (float) Math.sqrt(dx * dx + dy * dy);   // 中心点距离
                // 重叠太多，或中心点几乎重合（模型对同一目标输出角度不同的重复框），都抑制
                if (iou > iouThreshold || centerDist < CENTER_DUP_DISTANCE) {
                    suppressed[j] = true;   // 抑制（去掉）低置信度那个
                }
            }
        }

        return kept;   // 返回去重后的框
    }

    /** 计算多边形面积（鞋带公式）。 */
    private static float polygonArea(float[][] poly) {
        int n = poly.length;   // 顶点数
        if (n < 3) return 0f;  // 不足 3 点不是多边形
        float area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;   // 下一个顶点（首尾相连）
            area += poly[i][0] * poly[j][1];   // 鞋带公式
            area -= poly[j][0] * poly[i][1];
        }
        return Math.abs(area) * 0.5f;   // 绝对值的一半
    }

    /** Sutherland–Hodgman 多边形裁剪：求两个凸多边形交集。 */
    private static float[][] clipPolygon(float[][] subject, float[][] clip) {
        float[][] output = subject;
        for (int e = 0; e < 4; e++) {   // 对裁剪窗口的 4 条边
            if (output.length == 0) return output;
            float[][] input = output;
            float ex1 = clip[e][0], ey1 = clip[e][1];   // 边起点
            float ex2 = clip[(e + 1) % 4][0], ey2 = clip[(e + 1) % 4][1];   // 边终点
            output = clipAgainstEdge(input, ex1, ey1, ex2, ey2);   // 用这条边裁剪
        }
        return output;
    }

    /** 用一条边裁剪多边形。 */
    private static float[][] clipAgainstEdge(float[][] poly, float ex1, float ey1, float ex2, float ey2) {
        List<float[]> out = new ArrayList<>();
        int n = poly.length;
        if (n == 0) return new float[0][0];

        float edgeX = ex2 - ex1, edgeY = ey2 - ey1;   // 边方向向量
        for (int i = 0; i < n; i++) {
            float[] cur = poly[i];   // 当前点
            float[] next = poly[(i + 1) % n];   // 下一点
            boolean curInside = isInside(cur[0], cur[1], ex1, ey1, ex2, ey2);   // 当前点在边内？
            boolean nextInside = isInside(next[0], next[1], ex1, ey1, ex2, ey2);   // 下一点在边内？

            if (curInside) {   // 当前在边内
                out.add(cur);
                if (!nextInside) {   // 下一点在边外 → 求交点
                    out.add(lineIntersection(cur[0], cur[1], next[0], next[1], ex1, ey1, ex2, ey2));
                }
            } else if (nextInside) {   // 当前在边外，下一点在边内 → 求交点
                out.add(lineIntersection(cur[0], cur[1], next[0], next[1], ex1, ey1, ex2, ey2));
            }
        }
        return out.toArray(new float[out.size()][]);
    }

    /** 判断点是否在边内侧（叉积 ≤ 0）。 */
    private static boolean isInside(float px, float py, float ex1, float ey1, float ex2, float ey2) {
        return (ex2 - ex1) * (py - ey1) - (ey2 - ey1) * (px - ex1) <= 0;
    }

    /** 求两条线段交点。 */
    private static float[] lineIntersection(float x1, float y1, float x2, float y2,
                                             float x3, float y3, float x4, float y4) {
        float d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);   // 分母（判断平行）
        if (Math.abs(d) < 1e-12f) return new float[]{x1, y1};   // 平行返回起点
        float t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / d;
        return new float[]{x1 + t * (x2 - x1), y1 + t * (y2 - y1)};
    }

    /** 计算两个多边形 IoU。 */
    private static float polygonIoU(float[][] polyA, float[][] polyB, float areaA, float areaB) {
        float[][] inter = clipPolygon(polyA, polyB);   // 交集多边形
        float interArea = polygonArea(inter);   // 交集面积
        return interArea / (areaA + areaB - interArea + 1e-6f);   // IoU = 交集/并集
    }

    // ---- 绘图 ----

    /**
     * 在图片上画检测框和标签。
     *
     * @param image   原图
     * @param results 检测结果
     * @return 画好框的图片
     * @throws IOException 图片处理失败
     */
    public static BufferedImage drawDetections(BufferedImage image, List<DetResult> results) throws IOException {
        byte[] jpegBytes = bufferedImageToJpegBytes(image);   // 图片转 JPEG 字节
        Mat bgr = opencv_imgcodecs.imdecode(new Mat(jpegBytes), opencv_imgcodecs.IMREAD_COLOR);   // 解码成 Mat

        for (DetResult r : results) {   // 遍历每个检测结果
            int[] color = COLORS_BGR[r.classId % COLORS_BGR.length];   // 按类别取颜色
            Scalar scalar = new Scalar(color[0], color[1], color[2], 0);   // OpenCV 颜色

            // 画 4 条边（旋转框的四个边）
            opencv_imgproc.line(bgr, new Point((int) r.x1, (int) r.y1),
                new Point((int) r.x2, (int) r.y2), scalar, 2, opencv_imgproc.LINE_AA, 0);
            opencv_imgproc.line(bgr, new Point((int) r.x2, (int) r.y2),
                new Point((int) r.x3, (int) r.y3), scalar, 2, opencv_imgproc.LINE_AA, 0);
            opencv_imgproc.line(bgr, new Point((int) r.x3, (int) r.y3),
                new Point((int) r.x4, (int) r.y4), scalar, 2, opencv_imgproc.LINE_AA, 0);
            opencv_imgproc.line(bgr, new Point((int) r.x4, (int) r.y4),
                new Point((int) r.x1, (int) r.y1), scalar, 2, opencv_imgproc.LINE_AA, 0);

            // 在第一个角点画红色圆点标记
            opencv_imgproc.circle(bgr, new Point((int) r.x1, (int) r.y1), 4,
                new Scalar(0, 0, 255, 0), -1, opencv_imgproc.LINE_AA, 0);

            // 标签文字（类别名 + 置信度）
            String label = String.format("%s %.2f", r.className, r.confidence);
            int baseline = 0;
            Size textSize = opencv_imgproc.getTextSize(label,
                opencv_imgproc.FONT_HERSHEY_SIMPLEX, 0.5, 2, new int[]{baseline});   // 文字尺寸

            int labelY = (int) (r.y1 - 5);   // 标签位置（角点上方）
            if (labelY - textSize.height() < 0) {   // 如果超出图片顶部
                labelY = (int) (Math.max(r.y3, r.y4) + textSize.height() + 5);   // 放到框下方
            }

            // 画标签背景矩形
            opencv_imgproc.rectangle(bgr,
                new Point((int) r.x1, labelY - textSize.height() - 4),
                new Point((int) r.x1 + textSize.width() + 4, labelY + 2),
                scalar, opencv_imgproc.FILLED, opencv_imgproc.LINE_AA, 0);
            // 画标签文字
            opencv_imgproc.putText(bgr, label,
                new Point((int) r.x1 + 2, labelY),
                opencv_imgproc.FONT_HERSHEY_SIMPLEX, 0.5,
                new Scalar(255, 255, 255, 0), 2, opencv_imgproc.LINE_AA, false);
        }

        Mat rgb = new Mat();
        opencv_imgproc.cvtColor(bgr, rgb, opencv_imgproc.COLOR_BGR2RGB);   // 转回 RGB

        BytePointer bp = new BytePointer();
        opencv_imgcodecs.imencode(".jpg", rgb, bp,
            new IntPointer(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, 95));   // 编码成 JPEG
        byte[] outBytes = new byte[(int) bp.limit()];
        bp.get(outBytes);   // 取出字节

        bgr.close(); rgb.close(); bp.close();   // 释放资源
        return ImageIO.read(new ByteArrayInputStream(outBytes));   // 转回 BufferedImage
    }

    /** BufferedImage → JPEG 字节数组。 */
    private static byte[] bufferedImageToJpegBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "JPEG", baos);
        return baos.toByteArray();
    }

    /** 释放 ONNX Runtime 资源。 */
    @Override
    public void close() throws OrtException {
        session.close();   // 关闭推理会话
        env.close();       // 关闭环境
    }
}
