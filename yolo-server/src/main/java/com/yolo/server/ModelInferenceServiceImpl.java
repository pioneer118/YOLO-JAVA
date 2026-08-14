package com.yolo.server;

// DetResult：推理引擎返回的检测结果（四个角点 + 类别 + 置信度）
import com.yolo.inference.DetResult;
// DetectionOptions：检测参数（置信度阈值等）
import com.yolo.inference.DetectionOptions;
// ImageUtils：图片解码工具（支持 TIFF）
import com.yolo.inference.ImageUtils;
// YoloInferenceEngine：推理引擎（做检测）
import com.yolo.inference.YoloInferenceEngine;
// proto.*：Protobuf 生成的消息类（请求/响应/检测结果等）
import com.yolo.proto.*;
// DubboService：Dubbo 注解，声明"我提供一个服务"
import org.apache.dubbo.config.annotation.DubboService;
// Logger：日志工具
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Autowired：Spring 依赖注入（自动给字段赋值）
import org.springframework.beans.factory.annotation.Autowired;

// BufferedImage：Java 的图片对象
import java.awt.image.BufferedImage;
// List：集合
import java.util.List;

@DubboService(group = "${yolo.model-type}", version = "1.0.0")   // 声明：我提供模型检测服务
// group = "${yolo.model-type}"：分组由配置决定（ship/plane/car），三个服务靠它区分
public class ModelInferenceServiceImpl implements ModelInferenceService {

    private static final Logger log = LoggerFactory.getLogger(ModelInferenceServiceImpl.class);   // 日志对象

    @Autowired                    // Spring 自动注入
    private YoloInferenceEngine engine;   // 推理引擎（启动时创建的那个，已加载模型）

    /**
     * 处理检测请求：收到图片字节，解码后调用引擎推理，返回检测结果。
     *
     * @param request 模型检测请求（含图片字节、置信度阈值等）
     * @return 检测响应（检测列表 + 总数 + 耗时）
     */
    @Override
    public ModelDetectResponse detect(ModelDetectRequest request) {
        long startTime = System.currentTimeMillis();   // 记录开始时间（算耗时）

        try {
            byte[] imageBytes = request.getImageData().toByteArray();   // ① 取出图片字节
            BufferedImage image = ImageUtils.loadImage(imageBytes);     // ② 解码成 Java 图片对象

            float confThreshold = request.getConfThreshold() > 0        // ③ 读取置信度阈值
                ? request.getConfThreshold() : 0.6f;                    // 没传或<=0 就用 0.6
            DetectionOptions options = new DetectionOptions(confThreshold);   // ④ 构建检测参数

            List<DetResult> detections = engine.detect(image, options);   // ⑤ ★ 调用引擎推理（4步：预处理→推理→后处理→NMS）

            // ⑥ 构建 Protobuf 响应（把结果转成消息）
            ModelDetectResponse.Builder builder = ModelDetectResponse.newBuilder()
                .setTotalCount(detections.size())                         // 检测总数
                .setProcessingTimeMs((int) (System.currentTimeMillis() - startTime));   // 耗时

            for (DetResult det : detections) {   // ⑦ 遍历每个检测结果，转成 Detection 消息
                builder.addDetections(Detection.newBuilder()
                    .setClassId(det.classId)        // 类别编号
                    .setClassName(det.className)    // 类别名
                    .setConfidence(det.confidence)  // 置信度
                    .setBbox(BoundingBox.newBuilder()   // 旋转框四角点
                        .setX1(det.x1).setY1(det.y1)
                        .setX2(det.x2).setY2(det.y2)
                        .setX3(det.x3).setY3(det.y3)
                        .setX4(det.x4).setY4(det.y4)
                        .build())
                    .build());
            }

            return builder.build();   // ⑧ 返回响应

        } catch (Exception e) {   // ⑨ 出错处理
            log.error("Model inference failed", e);   // 打印错误日志
            return ModelDetectResponse.newBuilder()   // 返回空结果（不崩溃）
                .setTotalCount(0)                     // 0 个目标
                .setProcessingTimeMs((int) (System.currentTimeMillis() - startTime))   // 耗时
                .build();
        }
    }
}
