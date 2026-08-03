package com.yolo.inference;

import com.yolo.proto.BoundingBox;
import com.yolo.proto.Detection;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 大图自适应裁切工具。
 *
 * <p>核心功能：
 * <ol>
 *   <li>从 GeoTIFF 元数据中提取 GSD（地面采样距离），据此选择最优裁切策略</li>
 *   <li>将大图切分为重叠瓦片，每片编码为 JPEG 供 Protobuf 传输</li>
 *   <li>将瓦片局部坐标偏移到原图全局坐标</li>
 * </ol>
 *
 * <h3>自适应策略</h3>
 * 根据 GSD 和最小关注目标尺寸（默认 10m），自动选择瓦片大小：
 * <table>
 *   <tr><th>GSD 级别</th><th>10m 目标像素数</th><th>瓦片大小</th><th>缩放比</th><th>说明</th></tr>
 *   <tr><td>&lt; 0.5 m/px（精细）</td><td>&gt; 20px</td><td>1280</td><td>2×</td><td>无人机/低空航拍，可适度缩小</td></tr>
 *   <tr><td>0.5~2 m/px（中等）</td><td>5~20px</td><td>960</td><td>1.5×</td><td>高分卫星，谨慎缩小</td></tr>
 *   <tr><td>&gt; 2 m/px（粗糙）</td><td>&lt; 5px</td><td>640</td><td>1×</td><td>普通卫星，不缩小，告警</td></tr>
 *   <tr><td>无 GSD</td><td>未知</td><td>按像素估算</td><td>—</td><td>普通图片，回退策略</td></tr>
 * </table>
 */
public class ImageTiler {

    private static final Logger log = Logger.getLogger(ImageTiler.class.getName());

    // ---- 裁切常量 ----

    /** JPEG 编码质量 */
    private static final int JPEG_QUALITY = 95;

    /** 重叠比例默认值 */
    private static final float DEFAULT_OVERLAP = 0.20f;

    /** 用户关注的最小目标尺寸（米），用于 GSD 自适应计算 */
    private static final double DEFAULT_MIN_TARGET_METERS = 10.0;

    // ---- TIFF / GeoTIFF 标签 ID ----

    private static final int TAG_IMAGE_WIDTH       = 256;
    private static final int TAG_IMAGE_LENGTH      = 257;
    private static final int TAG_MODEL_PIXEL_SCALE = 33550;
    private static final int TAG_MODEL_TIEPOINT    = 33922;
    private static final int TAG_GEO_KEY_DIRECTORY = 34735;

    private static final int TIFF_TYPE_SHORT  = 3;
    private static final int TIFF_TYPE_DOUBLE = 12;

    // ---- 数据结构 ----

    /**
     * 自适应裁切策略。
     *
     * @param tileSize  瓦片像素尺寸（如 640 / 960 / 1280）
     * @param overlap   重叠比例（如 0.20 = 20%）
     * @param gsd       地面采样距离（米/像素），-1 表示未知
     * @param level     策略级别标识（fine / medium / coarse / fallback）
     * @param warning   用户提示信息，null 表示无警告
     */
    public record TilingStrategy(int tileSize, float overlap, double gsd,
                                 String level, String warning) {

        /** 计算步长 = tileSize × (1 - overlap) */
        public int stride() {
            return Math.max(1, Math.round(tileSize * (1.0f - overlap)));
        }

        /** 自动裁切阈值：图片宽/高超过此像素数时触发裁切 */
        public int autoTileThreshold() {
            return Math.round(tileSize * 1.5f);
        }
    }

    /**
     * 瓦片元数据。
     *
     * @param imageBytes JPEG 编码的瓦片图片数据
     * @param x          瓦片左上角在原图中的 X 偏移（像素）
     * @param y          瓦片左上角在原图中的 Y 偏移（像素）
     * @param width      瓦片有效区域宽度（像素，≤ tileSize）
     * @param height     瓦片有效区域高度（像素，≤ tileSize）
     */
    public record Tile(byte[] imageBytes, int x, int y, int width, int height) {}

    /**
     * 原始 TIFF 元数据，供上层使用（如前端展示）。
     */
    public record GeoTiffMeta(double gsd, double coverageKm2, String level,
                              int imageWidth, int imageHeight) {}

    // ================================================================
    //  公开 API：自适应分析
    // ================================================================

    /**
     * 分析图片字节数据，返回最优裁切策略。
     *
     * <p>等价于 {@code analyze(imageBytes, -1)}。
     */
    public static TilingStrategy analyze(byte[] imageBytes) {
        return analyze(imageBytes, -1);
    }

    /**
     * 分析图片字节数据，返回最优裁切策略。
     *
     * <p>GSD 解析优先级：
     * <ol>
     *   <li>外部传入的 gsd（&gt; 0 时直接使用）</li>
     *   <li>GeoTIFF 标签中的 GSD</li>
     *   <li>像素尺寸回退估算</li>
     * </ol>
     *
     * @param imageBytes  原始图片字节
     * @param externalGsd 外部提供的 GSD（米/像素），&le;0 表示自动检测
     * @return 最优裁切策略
     */
    public static TilingStrategy analyze(byte[] imageBytes, float externalGsd) {
        // 优先：外部传入的 GSD
        if (externalGsd > 0) {
            // 先获取图片尺寸
            GeoTiffMeta meta = parseGeoTiffMeta(imageBytes);
            int w, h;
            if (meta != null) {
                w = meta.imageWidth();
                h = meta.imageHeight();
            } else {
                // TIFF 解析失败，用 OpenCV 解码获取尺寸
                int[] dims = getDimensionsFromOpenCV(imageBytes);
                w = dims[0];
                h = dims[1];
            }
            if (w > 0 && h > 0) {
                return strategyFromGSD(externalGsd, w, h);
            }
        }

        // 尝试解析 TIFF 头部
        GeoTiffMeta meta = parseGeoTiffMeta(imageBytes);
        if (meta != null && meta.gsd() > 0) {
            return strategyFromGSD(meta.gsd(), meta.imageWidth(), meta.imageHeight());
        }
        // 回退：用已知的宽高按像素估算
        if (meta != null) {
            return fallbackStrategy(meta.imageWidth(), meta.imageHeight());
        }
        // 完全无法解析 → 用 OpenCV 解码获取尺寸
        return fallbackStrategyFromBytes(imageBytes);
    }

    /**
     * 分析并同时返回策略和 GeoTIFF 元数据。
     */
    public static AnalysisResult analyzeFull(byte[] imageBytes) {
        return analyzeFull(imageBytes, -1);
    }

    /**
     * 分析并同时返回策略和 GeoTIFF 元数据（支持外部 GSD）。
     */
    public static AnalysisResult analyzeFull(byte[] imageBytes, float externalGsd) {
        TilingStrategy strategy = analyze(imageBytes, externalGsd);
        GeoTiffMeta meta = parseGeoTiffMeta(imageBytes);
        return new AnalysisResult(strategy, meta);
    }

    /**
     * 用 OpenCV 快速获取图片宽高（不解码完整像素数据）。
     */
    private static int[] getDimensionsFromOpenCV(byte[] imageBytes) {
        Mat mat = opencv_imgcodecs.imdecode(
                new Mat(imageBytes), opencv_imgcodecs.IMREAD_COLOR);
        if (mat == null || mat.empty()) return new int[]{0, 0};
        int w = mat.cols();
        int h = mat.rows();
        mat.close();
        return new int[]{w, h};
    }

    public record AnalysisResult(TilingStrategy strategy, GeoTiffMeta meta) {}

    /**
     * 图像的 WGS84 地理边界。
     * <p>north/south/east/west 均以度为单位。
     * 如果图片不含地理信息，所有值均为 0。
     */
    public record GeoBounds(double north, double south, double east, double west,
                            double centerLat, double centerLng,
                            int imageWidth, int imageHeight) {
        public boolean isValid() { return north != 0 || south != 0 || east != 0 || west != 0; }
    }

    /**
     * 从 TIFF 字节中提取 WGS84 地理边界。
     *
     * <p>读取 ModelTiepoint (投影坐标原点) 和 ModelPixelScale (GSD)，
     * 将 Web Mercator (EPSG:3857) 坐标转为 WGS84 经纬度。
     *
     * @param imageBytes 原始图片字节
     * @return 地理边界；不含地理信息时返回全零值
     */
    public static GeoBounds getGeoBounds(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length < 8) {
            return new GeoBounds(0, 0, 0, 0, 0, 0, 0, 0);
        }
        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(imageBytes);
            byte b0 = buf.get(), b1 = buf.get();
            boolean le = (b0 == 0x49 && b1 == 0x49);
            buf.order(le ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
            if (buf.getShort() != 42) return new GeoBounds(0, 0, 0, 0, 0, 0, 0, 0);
            int ifdOff = buf.getInt();
            if (ifdOff <= 0 || ifdOff >= imageBytes.length) return new GeoBounds(0, 0, 0, 0, 0, 0, 0, 0);

            buf.position(ifdOff);
            short numEntries = buf.getShort();
            int imgW = 0, imgH = 0;
            double tieX = Double.NaN, tieY = Double.NaN, gsdX = -1, gsdY = -1;

            for (int i = 0; i < numEntries; i++) {
                int tagId = buf.getShort() & 0xFFFF;
                int dataType = buf.getShort() & 0xFFFF;
                int count = buf.getInt();
                int valOff = buf.getInt();

                if (tagId == 256) { // ImageWidth
                    imgW = (dataType == 3) ? (valOff & 0xFFFF) : valOff;
                } else if (tagId == 257) { // ImageLength
                    imgH = (dataType == 3) ? (valOff & 0xFFFF) : valOff;
                } else if (tagId == 33550 && count >= 2 && dataType == 12) { // ModelPixelScale
                    int saved = buf.position();
                    buf.position(valOff);
                    gsdX = Math.abs(buf.getDouble());
                    gsdY = Math.abs(buf.getDouble());
                    buf.position(saved);
                } else if (tagId == 33922 && count >= 6 && dataType == 12) { // ModelTiepoint
                    int saved = buf.position();
                    buf.position(valOff);
                    buf.getDouble(); buf.getDouble(); buf.getDouble(); // I,J,K
                    tieX = buf.getDouble();
                    tieY = buf.getDouble();
                    buf.position(saved);
                }
            }

            if (imgW <= 0 || imgH <= 0 || Double.isNaN(tieX) || gsdX <= 0) {
                return new GeoBounds(0, 0, 0, 0, 0, 0, 0, 0);
            }

            // 四角投影坐标 (EPSG:3857 Web Mercator)
            double westM  = tieX;
            double eastM  = tieX + imgW * gsdX;
            double northM = tieY;
            double southM = tieY - imgH * gsdY;

            // Web Mercator → WGS84
            double north = mercatorToLat(northM);
            double south = mercatorToLat(southM);
            double east  = mercatorToLng(eastM);
            double west  = mercatorToLng(westM);

            return new GeoBounds(north, south, east, west,
                    (north + south) / 2.0, (east + west) / 2.0, imgW, imgH);
        } catch (Exception e) {
            return new GeoBounds(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /** Web Mercator Y → 纬度 (度) */
    private static double mercatorToLat(double y) {
        return Math.toDegrees(Math.atan(Math.sinh(y / 6378137.0)));
    }

    /** Web Mercator X → 经度 (度) */
    private static double mercatorToLng(double x) {
        return Math.toDegrees(x / 6378137.0);
    }

    // ================================================================
    //  公开 API：根据 GSD 选择策略
    // ================================================================

    /**
     * 根据地面采样距离（GSD）选择最优裁切策略。
     *
     * @param gsd        地面采样距离（米/像素）
     * @param imageWidth 图片宽度（像素），用于生成警告信息
     * @param imageHeight 图片高度（像素）
     */
    public static TilingStrategy strategyFromGSD(double gsd, int imageWidth, int imageHeight) {
        double targetPixels = DEFAULT_MIN_TARGET_METERS / gsd;
        double coverageKm2 = (imageWidth * gsd * imageHeight * gsd) / 1_000_000.0;

        if (targetPixels > 20) {
            // 精细影像：大 tile，可适度缩小，节省计算
            return new TilingStrategy(1280, DEFAULT_OVERLAP, gsd, "fine", null);
        } else if (targetPixels > 10) {
            // 中等影像：中 tile，谨慎缩小
            return new TilingStrategy(960, DEFAULT_OVERLAP, gsd, "medium", null);
        } else if (targetPixels > 5) {
            // 粗糙影像：小 tile，不缩小
            String warn = String.format(
                    "当前 GSD=%.2f m/px，%.0fm 目标仅 %.0f 像素，"
                            + "接近检测极限。建议使用更高分辨率影像。",
                    gsd, DEFAULT_MIN_TARGET_METERS, targetPixels);
            return new TilingStrategy(640, 0.25f, gsd, "coarse", warn);
        } else {
            // 极粗糙：不裁切，强告警
            String warn = String.format(
                    "当前 GSD=%.2f m/px，%.0fm 目标仅 %.0f 像素，"
                            + "低于模型最小可检测阈值（约 10px）。检测结果可能严重不全。",
                    gsd, DEFAULT_MIN_TARGET_METERS, targetPixels);
            return new TilingStrategy(640, 0.25f, gsd, "coarse_warn", warn);
        }
    }

    /**
     * 无 GSD 时的回退策略：纯按图片像素尺寸估算。
     */
    public static TilingStrategy fallbackStrategy(int imageWidth, int imageHeight) {
        int maxDim = Math.max(imageWidth, imageHeight);

        if (maxDim <= 1280) {
            // 小图，不裁切
            return new TilingStrategy(640, DEFAULT_OVERLAP, -1, "fallback_small", null);
        } else if (maxDim <= 4000) {
            // 中等图
            return new TilingStrategy(960, DEFAULT_OVERLAP, -1, "fallback_medium",
                    "未读取到地理信息(GSD)，按图片尺寸估算策略。建议使用 GeoTIFF 格式。");
        } else {
            // 大图
            return new TilingStrategy(1280, DEFAULT_OVERLAP, -1, "fallback_large",
                    "未读取到地理信息(GSD)，按图片尺寸估算策略。建议使用 GeoTIFF 格式。");
        }
    }

    private static TilingStrategy fallbackStrategyFromBytes(byte[] imageBytes) {
        Mat mat = opencv_imgcodecs.imdecode(new Mat(imageBytes), opencv_imgcodecs.IMREAD_COLOR);
        if (mat == null || mat.empty()) {
            // 彻底失败：返回默认
            return new TilingStrategy(640, DEFAULT_OVERLAP, -1, "fallback_unknown",
                    "无法解析图片，使用默认策略。");
        }
        TilingStrategy s = fallbackStrategy(mat.cols(), mat.rows());
        mat.close();
        return s;
    }

    // ================================================================
    //  公开 API：裁切与坐标变换
    // ================================================================

    /**
     * 判断图片是否需要裁切。
     */
    public static boolean shouldTile(int imageWidth, int imageHeight, TilingStrategy strategy) {
        int threshold = strategy.autoTileThreshold();
        return imageWidth > threshold || imageHeight > threshold;
    }

    /**
     * 将大图裁切为重叠瓦片。
     *
     * @param image    原始图片（BGR 格式 OpenCV Mat）
     * @param strategy 裁切策略（决定 tileSize 和 overlap）
     * @return 瓦片列表
     */
    public static List<Tile> tile(Mat image, TilingStrategy strategy) {
        return tile(image, strategy.tileSize(), strategy.stride(), strategy.tileSize());
    }

    /**
     * 将大图裁切为重叠瓦片（完整参数版本）。
     *
     * @param image    原始图片
     * @param tileSize 瓦片像素尺寸
     * @param stride   步长（= tileSize - overlapPixels）
     * @param overlapPixels 实际重叠像素数（用于日志）
     */
    private static List<Tile> tile(Mat image, int tileSize, int stride, int overlapPixels) {
        int imgW = image.cols();
        int imgH = image.rows();

        List<Tile> tiles = new ArrayList<>();
        int numCols = (int) Math.ceil((double) imgW / stride);
        int numRows = (int) Math.ceil((double) imgH / stride);

        log.info(String.format("Tiling %dx%d image → %dx%d grid (%d tiles, tile=%d, stride=%d)",
                imgW, imgH, numCols, numRows, numCols * numRows, tileSize, stride));

        Scalar black = new Scalar(0, 0, 0, 0);

        for (int row = 0; row < numRows; row++) {
            for (int col = 0; col < numCols; col++) {
                int x = col * stride;
                int y = row * stride;

                // 边缘瓦片不超出边界
                if (x + tileSize > imgW) x = imgW - tileSize;
                if (y + tileSize > imgH) y = imgH - tileSize;

                int validW = Math.min(tileSize, imgW - x);
                int validH = Math.min(tileSize, imgH - y);

                Rect roi = new Rect(x, y, validW, validH);
                Mat submat = new Mat(image, roi);

                Mat tileMat;
                if (validW == tileSize && validH == tileSize) {
                    tileMat = submat.clone();
                } else {
                    tileMat = new Mat(new Size(tileSize, tileSize), submat.type());
                    opencv_core.copyMakeBorder(submat, tileMat,
                            0, tileSize - validH, 0, tileSize - validW,
                            opencv_core.BORDER_CONSTANT, black);
                }

                byte[] jpegBytes = encodeToJpeg(tileMat);
                tiles.add(new Tile(jpegBytes, x, y, validW, validH));

                submat.close();
                if (tileMat != submat) {
                    tileMat.close();
                }
            }
        }

        return tiles;
    }

    /**
     * 将检测结果从瓦片局部坐标偏移到原图全局坐标。
     *
     * @param detections 瓦片局部坐标的检测结果
     * @param offsetX    瓦片在原图中的 X 偏移
     * @param offsetY    瓦片在原图中的 Y 偏移
     * @return 偏移后的检测结果（原地修改列表元素）
     */
    public static List<Detection> offsetToOriginal(List<Detection> detections, int offsetX, int offsetY) {
        for (int i = 0; i < detections.size(); i++) {
            Detection d = detections.get(i);
            BoundingBox oldBbox = d.getBbox();
            BoundingBox newBbox = BoundingBox.newBuilder()
                    .setX1(oldBbox.getX1() + offsetX)
                    .setY1(oldBbox.getY1() + offsetY)
                    .setX2(oldBbox.getX2() + offsetX)
                    .setY2(oldBbox.getY2() + offsetY)
                    .setX3(oldBbox.getX3() + offsetX)
                    .setY3(oldBbox.getY3() + offsetY)
                    .setX4(oldBbox.getX4() + offsetX)
                    .setY4(oldBbox.getY4() + offsetY)
                    .build();
            detections.set(i, d.toBuilder().setBbox(newBbox).build());
        }
        return detections;
    }

    // ================================================================
    //  内部：GeoTIFF 元数据解析
    // ================================================================

    /**
     * 轻量级 TIFF 头部解析器，提取 GSD 和图像尺寸。
     *
     * <p>不依赖任何第三方 TIFF 库，直接读取 TIFF IFD 中我们关心的 3 个标签：
     * 256(ImageWidth)、257(ImageLength)、33550(ModelPixelScale)。
     *
     * @return 解析结果，失败返回 null
     */
    private static GeoTiffMeta parseGeoTiffMeta(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return null;

        try {
            ByteBuffer buf = ByteBuffer.wrap(bytes);

            // ---- 读取 8 字节 TIFF 头部 ----
            byte b0 = buf.get();
            byte b1 = buf.get();
            boolean littleEndian;
            if (b0 == 0x49 && b1 == 0x49) {
                littleEndian = true;
            } else if (b0 == 0x4D && b1 == 0x4D) {
                littleEndian = false;
            } else {
                return null; // 不是 TIFF
            }
            buf.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

            short magic = buf.getShort();
            if (magic != 42) return null; // 不是标准 TIFF

            int ifdOffset = buf.getInt();
            if (ifdOffset <= 0 || ifdOffset >= bytes.length) return null;

            // ---- 解析第一个 IFD ----
            buf.position(ifdOffset);
            short numEntries = buf.getShort();

            int imageWidth = 0, imageLength = 0;
            double gsdX = -1, gsdY = -1;

            for (int i = 0; i < numEntries; i++) {
                int tagId    = buf.getShort() & 0xFFFF;
                int dataType = buf.getShort() & 0xFFFF;
                int count    = buf.getInt();
                int valueOrOffset = buf.getInt();

                switch (tagId) {
                    case TAG_IMAGE_WIDTH:
                        imageWidth = readIntValue(dataType, count, valueOrOffset, buf, bytes);
                        break;
                    case TAG_IMAGE_LENGTH:
                        imageLength = readIntValue(dataType, count, valueOrOffset, buf, bytes);
                        break;
                    case TAG_MODEL_PIXEL_SCALE:
                        // 3 个 double: ScaleX, ScaleY, ScaleZ
                        if (count >= 2 && dataType == TIFF_TYPE_DOUBLE) {
                            double[] scales = readDoubleArray(valueOrOffset, count, buf, bytes);
                            gsdX = Math.abs(scales[0]);
                            gsdY = Math.abs(scales[1]);
                        }
                        break;
                    default:
                        break;
                }
            }

            if (imageWidth > 0 && imageLength > 0) {
                double gsd = (gsdX > 0 && gsdY > 0) ? (gsdX + gsdY) / 2.0 : -1;
                double coverage = (gsd > 0)
                        ? (imageWidth * gsd * imageLength * gsd) / 1_000_000.0
                        : -1;
                String level = classifyGSD(gsd);
                return new GeoTiffMeta(gsd, coverage, level, imageWidth, imageLength);
            }
        } catch (Exception e) {
            log.fine("Failed to parse TIFF metadata: " + e.getMessage());
        }
        return null;
    }

    private static int readIntValue(int dataType, int count, int valueOrOffset,
                                     ByteBuffer buf, byte[] bytes) {
        if (count <= 0) return 0;
        if (dataType == TIFF_TYPE_SHORT) {
            return valueOrOffset & 0xFFFF;
        }
        if (dataType == 4) { // LONG
            return valueOrOffset;
        }
        return 0;
    }

    private static double[] readDoubleArray(int offset, int count,
                                             ByteBuffer buf, byte[] bytes) {
        double[] result = new double[Math.min(count, 6)];
        int savedPos = buf.position();
        buf.position(offset);
        for (int i = 0; i < result.length; i++) {
            result[i] = buf.getDouble();
        }
        buf.position(savedPos);
        return result;
    }

    private static String classifyGSD(double gsd) {
        if (gsd <= 0) return "unknown";
        if (gsd < 0.5) return "fine";
        if (gsd < 2.0) return "medium";
        return "coarse";
    }

    // ================================================================
    //  内部：JPEG 编码
    // ================================================================

    private static byte[] encodeToJpeg(Mat mat) {
        BytePointer bp = new BytePointer();
        opencv_imgcodecs.imencode(".jpg", mat, bp,
                new IntPointer(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY));
        byte[] bytes = new byte[(int) bp.limit()];
        bp.get(bytes);
        bp.close();
        return bytes;
    }
}
