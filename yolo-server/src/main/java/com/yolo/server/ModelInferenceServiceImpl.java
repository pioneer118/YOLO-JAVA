package com.yolo.server;

import com.yolo.inference.DetResult;
import com.yolo.inference.DetectionOptions;
import com.yolo.inference.ImageUtils;
import com.yolo.inference.YoloInferenceEngine;
import com.yolo.proto.*;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.awt.image.BufferedImage;
import java.util.List;

@DubboService(group = "${yolo.model-type}", version = "1.0.0")
public class ModelInferenceServiceImpl implements ModelInferenceService {

    private static final Logger log = LoggerFactory.getLogger(ModelInferenceServiceImpl.class);

    @Autowired
    private YoloInferenceEngine engine;

    @Override
    public ModelDetectResponse detect(ModelDetectRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            byte[] imageBytes = request.getImageData().toByteArray();
            BufferedImage image = ImageUtils.loadImage(imageBytes);

            float confThreshold = request.getConfThreshold() > 0
                ? request.getConfThreshold() : 0.5f;
            DetectionOptions options = new DetectionOptions(confThreshold);

            List<DetResult> detections = engine.detect(image, options);

            ModelDetectResponse.Builder builder = ModelDetectResponse.newBuilder()
                .setTotalCount(detections.size())
                .setProcessingTimeMs((int) (System.currentTimeMillis() - startTime));

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
        } catch (Exception e) {
            log.error("Model inference failed", e);
            return ModelDetectResponse.newBuilder()
                .setTotalCount(0)
                .setProcessingTimeMs((int) (System.currentTimeMillis() - startTime))
                .build();
        }
    }
}
