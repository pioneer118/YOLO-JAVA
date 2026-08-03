package com.yolo.inference;

import ai.onnxruntime.*;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.logging.Logger;

public class YoloInferenceEngine implements AutoCloseable {

    private static final Logger log = Logger.getLogger(YoloInferenceEngine.class.getName());

    private static final int INPUT_SIZE = 1024;
    private static final int NUM_CHANNELS = 3;

    private static final int[][] COLORS_BGR = {
        {255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 0},
        {255, 0, 255}, {0, 255, 255}, {128, 0, 0}, {0, 128, 0},
        {0, 0, 128}, {128, 128, 0}, {128, 0, 128}, {0, 128, 128},
        {64, 128, 0}, {192, 128, 0}, {64, 0, 128}
    };

    private final OrtEnvironment env;
    private final OrtSession session;
    private final List<String> classNames;

    public YoloInferenceEngine(String modelPath, List<String> classNames) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        this.session = env.createSession(modelPath, opts);
        this.classNames = Collections.unmodifiableList(new ArrayList<>(classNames));
    }

    public List<DetResult> detect(BufferedImage image, DetectionOptions options) throws OrtException, IOException {
        // Step 1: Preprocess
        float[] inputData = preprocess(image);

        // Step 2: ONNX inference
        float[][][] output = runInference(inputData);

        // Step 3: Postprocess — parse, filter, convert to corners
        List<DetResult> detections = postprocess(output, image.getWidth(), image.getHeight(), options);

        // Step 4: Rotated NMS
        return applyNMS(detections, options.iouThreshold());
    }

    // ---- Preprocessing ----

    private float[] preprocess(BufferedImage image) throws IOException {
        byte[] jpegBytes = bufferedImageToJpegBytes(image);
        Mat bgr = opencv_imgcodecs.imdecode(new Mat(jpegBytes), opencv_imgcodecs.IMREAD_COLOR);
        Mat rgb = new Mat();
        opencv_imgproc.cvtColor(bgr, rgb, opencv_imgproc.COLOR_BGR2RGB);

        Mat resized = new Mat();
        opencv_imgproc.resize(rgb, resized, new Size(INPUT_SIZE, INPUT_SIZE));

        Mat floatMat = new Mat();
        resized.convertTo(floatMat, opencv_core.CV_32FC3, 1.0 / 255.0, 0.0);

        float[] chw = new float[NUM_CHANNELS * INPUT_SIZE * INPUT_SIZE];
        FloatPointer fp = new FloatPointer(floatMat.ptr());
        float[] hwc = new float[INPUT_SIZE * INPUT_SIZE * NUM_CHANNELS];
        fp.get(hwc);

        // HWC → CHW
        for (int c = 0; c < NUM_CHANNELS; c++) {
            for (int h = 0; h < INPUT_SIZE; h++) {
                for (int w = 0; w < INPUT_SIZE; w++) {
                    int hwcIdx = h * INPUT_SIZE * NUM_CHANNELS + w * NUM_CHANNELS + c;
                    int chwIdx = c * INPUT_SIZE * INPUT_SIZE + h * INPUT_SIZE + w;
                    chw[chwIdx] = hwc[hwcIdx];
                }
            }
        }

        bgr.close(); rgb.close(); resized.close(); floatMat.close();
        return chw;
    }

    // ---- ONNX Inference ----

    private float[][][] runInference(float[] inputData) throws OrtException {
        OnnxTensor inputTensor = OnnxTensor.createTensor(env,
            FloatBuffer.wrap(inputData),
            new long[]{1, NUM_CHANNELS, INPUT_SIZE, INPUT_SIZE});

        Map<String, OnnxTensor> inputs = Map.of("images", inputTensor);

        float[][][] output;
        try (OrtSession.Result result = session.run(inputs)) {
            OnnxTensor outputTensor = (OnnxTensor) result.get("output0").get();
            long[] shape = outputTensor.getInfo().getShape();
            log.fine("ONNX output shape: " + java.util.Arrays.toString(shape));

            Object value = outputTensor.getValue();
            if (value instanceof float[][][] raw) {
                output = deepCopy(raw);
            } else {
                log.severe("Unexpected ONNX output type: " + value.getClass().getName());
                return new float[0][0][];
            }
        }
        // session.run() closes input tensors internally in ONNX Runtime ≥1.26;
        // do NOT call inputTensor.close() here — it would double-close.

        return output;
    }

    private static float[][][] deepCopy(float[][][] src) {
        float[][][] dst = new float[src.length][][];
        for (int i = 0; i < src.length; i++) {
            dst[i] = new float[src[i].length][];
            for (int j = 0; j < src[i].length; j++) {
                dst[i][j] = src[i][j].clone();
            }
        }
        return dst;
    }

    // ---- Postprocessing ----

    private List<DetResult> postprocess(float[][][] output, int origW, int origH,
                                         DetectionOptions options) {
        List<DetResult> detections = new ArrayList<>();

        if (output.length == 0 || output[0].length == 0) {
            log.warning("ONNX output is empty: dims=[" + output.length + ","
                + (output.length > 0 ? output[0].length : 0) + "]");
            return detections;
        }

        int N = output[0].length; // number of detections per frame
        int K = output[0][0].length; // values per detection
        log.fine("ONNX detections: N=" + N + ", K=" + K);

        for (int i = 0; i < N; i++) {
            float[] det = output[0][i];
            float rawConf = det[6];
            float confidence = 1.0f / (1.0f + (float) Math.exp(-rawConf));

            if (confidence < options.confidenceThreshold()) continue;

            float cx = det[0], cy = det[1], w = det[2], h = det[3], angle = det[4];
            int clsId = (int) det[5];

            // Scale from 640x640 to original image size
            float scaleX = (float) origW / INPUT_SIZE;
            float scaleY = (float) origH / INPUT_SIZE;
            cx *= scaleX; cy *= scaleY;
            w *= scaleX; h *= scaleY;

            float[] corners = obbToCorners(cx, cy, w, h, angle);

            DetResult r = new DetResult();
            r.classId = clsId;
            r.className = clsId < classNames.size() ? classNames.get(clsId) : "未知";
            r.confidence = confidence;
            r.x1 = corners[0]; r.y1 = corners[1];
            r.x2 = corners[2]; r.y2 = corners[3];
            r.x3 = corners[4]; r.y3 = corners[5];
            r.x4 = corners[6]; r.y4 = corners[7];
            r.cx = cx; r.cy = cy; r.w = w; r.h = h; r.angle = angle;
            detections.add(r);
        }

        return detections;
    }

    static float[] obbToCorners(float cx, float cy, float w, float h, float angle) {
        double cosA = Math.cos(angle);
        double sinA = Math.sin(angle);
        double hw = w * 0.5;
        double hh = h * 0.5;

        return new float[]{
            (float) (cx - hw * cosA + hh * sinA), (float) (cy - hw * sinA - hh * cosA),
            (float) (cx + hw * cosA + hh * sinA), (float) (cy + hw * sinA - hh * cosA),
            (float) (cx + hw * cosA - hh * sinA), (float) (cy + hw * sinA + hh * cosA),
            (float) (cx - hw * cosA - hh * sinA), (float) (cy - hw * sinA + hh * cosA),
        };
    }

    // ---- Rotated NMS (pure Java — avoids OpenCV native crashes in minAreaRect) ----

    private List<DetResult> applyNMS(List<DetResult> detections, float iouThreshold) {
        if (detections.isEmpty()) return detections;

        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        int n = detections.size();
        boolean[] suppressed = new boolean[n];

        // Precompute corner arrays and areas
        float[][][] corners = new float[n][4][2];
        float[] areas = new float[n];
        for (int i = 0; i < n; i++) {
            DetResult d = detections.get(i);
            corners[i] = new float[][]{
                {d.x1, d.y1}, {d.x2, d.y2}, {d.x3, d.y3}, {d.x4, d.y4}};
            areas[i] = polygonArea(corners[i]);
        }

        List<DetResult> kept = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (suppressed[i]) continue;
            DetResult best = detections.get(i);
            kept.add(best);

            for (int j = i + 1; j < n; j++) {
                if (suppressed[j]) continue;
                if (best.classId != detections.get(j).classId) continue;

                float iou = polygonIoU(corners[i], corners[j], areas[i], areas[j]);
                if (iou > iouThreshold) {
                    suppressed[j] = true;
                }
            }
        }

        return kept;
    }

    private static float polygonArea(float[][] poly) {
        int n = poly.length;
        if (n < 3) return 0f;
        float area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += poly[i][0] * poly[j][1];
            area -= poly[j][0] * poly[i][1];
        }
        return Math.abs(area) * 0.5f;
    }

    // Sutherland–Hodgman polygon clipping for convex polygons
    private static float[][] clipPolygon(float[][] subject, float[][] clip) {
        float[][] output = subject;
        for (int e = 0; e < 4; e++) {
            if (output.length == 0) return output;
            float[][] input = output;
            float ex1 = clip[e][0], ey1 = clip[e][1];
            float ex2 = clip[(e + 1) % 4][0], ey2 = clip[(e + 1) % 4][1];
            output = clipAgainstEdge(input, ex1, ey1, ex2, ey2);
        }
        return output;
    }

    private static float[][] clipAgainstEdge(float[][] poly, float ex1, float ey1, float ex2, float ey2) {
        List<float[]> out = new ArrayList<>();
        int n = poly.length;
        if (n == 0) return new float[0][0];

        float edgeX = ex2 - ex1, edgeY = ey2 - ey1;
        for (int i = 0; i < n; i++) {
            float[] cur = poly[i];
            float[] next = poly[(i + 1) % n];
            boolean curInside = isInside(cur[0], cur[1], ex1, ey1, ex2, ey2);
            boolean nextInside = isInside(next[0], next[1], ex1, ey1, ex2, ey2);

            if (curInside) {
                out.add(cur);
                if (!nextInside) {
                    out.add(lineIntersection(cur[0], cur[1], next[0], next[1], ex1, ey1, ex2, ey2));
                }
            } else if (nextInside) {
                out.add(lineIntersection(cur[0], cur[1], next[0], next[1], ex1, ey1, ex2, ey2));
            }
        }
        return out.toArray(new float[out.size()][]);
    }

    // Cross-product test: inside means to the left of the directed clip edge
    private static boolean isInside(float px, float py, float ex1, float ey1, float ex2, float ey2) {
        return (ex2 - ex1) * (py - ey1) - (ey2 - ey1) * (px - ex1) <= 0;
    }

    private static float[] lineIntersection(float x1, float y1, float x2, float y2,
                                             float x3, float y3, float x4, float y4) {
        float d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(d) < 1e-12f) return new float[]{x1, y1};
        float t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / d;
        return new float[]{x1 + t * (x2 - x1), y1 + t * (y2 - y1)};
    }

    private static float polygonIoU(float[][] polyA, float[][] polyB, float areaA, float areaB) {
        float[][] inter = clipPolygon(polyA, polyB);
        float interArea = polygonArea(inter);
        return interArea / (areaA + areaB - interArea + 1e-6f);
    }

    // ---- Drawing ----

    public static BufferedImage drawDetections(BufferedImage image, List<DetResult> results) throws IOException {
        byte[] jpegBytes = bufferedImageToJpegBytes(image);
        Mat bgr = opencv_imgcodecs.imdecode(new Mat(jpegBytes), opencv_imgcodecs.IMREAD_COLOR);

        for (DetResult r : results) {
            int[] color = COLORS_BGR[r.classId % COLORS_BGR.length];
            Scalar scalar = new Scalar(color[0], color[1], color[2], 0);

            // Draw 4 edges individually to avoid polylines+MatVector native instability
            opencv_imgproc.line(bgr, new Point((int) r.x1, (int) r.y1),
                new Point((int) r.x2, (int) r.y2), scalar, 2, opencv_imgproc.LINE_AA, 0);
            opencv_imgproc.line(bgr, new Point((int) r.x2, (int) r.y2),
                new Point((int) r.x3, (int) r.y3), scalar, 2, opencv_imgproc.LINE_AA, 0);
            opencv_imgproc.line(bgr, new Point((int) r.x3, (int) r.y3),
                new Point((int) r.x4, (int) r.y4), scalar, 2, opencv_imgproc.LINE_AA, 0);
            opencv_imgproc.line(bgr, new Point((int) r.x4, (int) r.y4),
                new Point((int) r.x1, (int) r.y1), scalar, 2, opencv_imgproc.LINE_AA, 0);

            // Red vertex marker on first corner
            opencv_imgproc.circle(bgr, new Point((int) r.x1, (int) r.y1), 4,
                new Scalar(0, 0, 255, 0), -1, opencv_imgproc.LINE_AA, 0);

            // Label
            String label = String.format("%s %.2f", r.className, r.confidence);
            int baseline = 0;
            Size textSize = opencv_imgproc.getTextSize(label,
                opencv_imgproc.FONT_HERSHEY_SIMPLEX, 0.5, 2, new int[]{baseline});

            int labelY = (int) (r.y1 - 5);
            if (labelY - textSize.height() < 0) {
                labelY = (int) (Math.max(r.y3, r.y4) + textSize.height() + 5);
            }

            opencv_imgproc.rectangle(bgr,
                new Point((int) r.x1, labelY - textSize.height() - 4),
                new Point((int) r.x1 + textSize.width() + 4, labelY + 2),
                scalar, opencv_imgproc.FILLED, opencv_imgproc.LINE_AA, 0);
            opencv_imgproc.putText(bgr, label,
                new Point((int) r.x1 + 2, labelY),
                opencv_imgproc.FONT_HERSHEY_SIMPLEX, 0.5,
                new Scalar(255, 255, 255, 0), 2, opencv_imgproc.LINE_AA, false);
        }

        Mat rgb = new Mat();
        opencv_imgproc.cvtColor(bgr, rgb, opencv_imgproc.COLOR_BGR2RGB);

        BytePointer bp = new BytePointer();
        opencv_imgcodecs.imencode(".jpg", rgb, bp,
            new IntPointer(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, 95));
        byte[] outBytes = new byte[(int) bp.limit()];
        bp.get(outBytes);

        bgr.close(); rgb.close(); bp.close();
        return ImageIO.read(new ByteArrayInputStream(outBytes));
    }

    private static byte[] bufferedImageToJpegBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "JPEG", baos);
        return baos.toByteArray();
    }

    @Override
    public void close() throws OrtException {
        session.close();
        env.close();
    }
}
