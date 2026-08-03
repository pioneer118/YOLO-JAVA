package com.yolo.inference;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * 图片加载 / 编码工具。
 *
 * <p>统一了项目中分散的 ImageIO 调用，核心改进是
 * <b>用 OpenCV imdecode 替代 ImageIO.read</b>，
 * 从而支持 TIFF / GeoTIFF 等 ImageIO 原生不支持的格式。
 */
public final class ImageUtils {

    private static final Logger log = Logger.getLogger(ImageUtils.class.getName());
    private static final int JPEG_QUALITY = 95;

    private ImageUtils() {}

    /**
     * 从字节数组加载图片。
     *
     * <p>加载链：ImageIO → OpenCV imdecode（TIFF 回退）。
     *
     * @param imageBytes JPEG / PNG / TIFF 原始字节
     * @return BufferedImage（RGB）
     * @throws IOException 两种方式都失败时抛出
     */
    public static BufferedImage loadImage(byte[] imageBytes) throws IOException {
        // 优先用 ImageIO（JPEG/PNG 最快路径）
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img != null) {
                return img;
            }
        } catch (IOException e) {
            log.fine("ImageIO failed, falling back to OpenCV: " + e.getMessage());
        }

        // 回退：OpenCV imdecode（支持 TIFF/GeoTIFF）
        Mat mat = opencv_imgcodecs.imdecode(
                new Mat(imageBytes), opencv_imgcodecs.IMREAD_COLOR);
        if (mat == null || mat.empty()) {
            throw new IOException("Failed to decode image: both ImageIO and OpenCV failed");
        }
        BufferedImage img = matToBufferedImage(mat);
        mat.close();
        return img;
    }

    /**
     * OpenCV Mat (BGR) → BufferedImage (RGB)。
     *
     * <p>通过 JPEG 往返实现，复用已验证的编码路径，
     * 避免手写像素字节重排引入的 bug。
     */
    public static BufferedImage matToBufferedImage(Mat mat) throws IOException {
        Mat rgb = new Mat();
        opencv_imgproc.cvtColor(mat, rgb, opencv_imgproc.COLOR_BGR2RGB);

        BytePointer bp = new BytePointer();
        opencv_imgcodecs.imencode(".jpg", rgb, bp,
                new IntPointer(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY));
        byte[] jpegBytes = new byte[(int) bp.limit()];
        bp.get(jpegBytes);
        bp.close();
        rgb.close();

        return ImageIO.read(new ByteArrayInputStream(jpegBytes));
    }

    /**
     * BufferedImage → JPEG 字节数组。
     */
    public static byte[] toJpegBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "JPEG", baos);
        return baos.toByteArray();
    }
}
