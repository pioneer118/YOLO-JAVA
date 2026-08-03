package com.yolo.webapi.controller;

import com.yolo.inference.ImageTiler;
import com.yolo.proto.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api")
public class DetectController {

    @DubboReference(version = "1.0.0", check = false)
    private YoloAggregationService yoloGateway;

    @PostMapping("/detect")
    public ResponseEntity<Map<String, Object>> detect(
            @RequestParam("image") MultipartFile image,
            @RequestParam(defaultValue = "satellite") String dataSource,
            @RequestParam(defaultValue = "0.5") float confThreshold,
            @RequestParam(defaultValue = "true") boolean autoTile,
            @RequestParam(defaultValue = "-1") float gsd,
            @RequestParam(defaultValue = "-999") float lat,
            @RequestParam(defaultValue = "-999") float lng,
            @RequestParam(defaultValue = "ship,plane,car") String targets) throws Exception {

        byte[] imageBytes = image.getBytes();

        // 将无人机参数和目标选择编码到 data_source 中
        // 格式: "drone:gsd=0.05:lat=30.1:lng=120.2:targets=ship,plane"
        StringBuilder sb = new StringBuilder(dataSource);
        if (gsd > 0) {
            sb.append(":gsd=").append(gsd);
            if (lat > -999) sb.append(":lat=").append(lat);
            if (lng > -999) sb.append(":lng=").append(lng);
        }
        if (targets != null && !targets.isEmpty() && !"ship,plane,car".equals(targets)) {
            sb.append(":targets=").append(targets);
        }

        AggregateDetectRequest request = AggregateDetectRequest.newBuilder()
            .setImageData(com.google.protobuf.ByteString.copyFrom(imageBytes))
            .setDataSource(sb.toString())
            .setConfThreshold(confThreshold)
            .build();

        AggregateDetectResponse response = yoloGateway.detect(request);

        // 提前提取地理坐标信息（循环中需要用到）
        ImageTiler.GeoBounds bounds = ImageTiler.getGeoBounds(imageBytes);
        int imgW = bounds.imageWidth();
        int imgH = bounds.imageHeight();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", response.getTotalCount());
        result.put("processingTimeMs", response.getProcessingTimeMs());
        result.put("annotatedImage", "data:image/jpeg;base64,"
            + Base64.getEncoder().encodeToString(response.getAnnotatedImage().toByteArray()));

        List<Map<String, Object>> detections = new ArrayList<>();
        for (Detection d : response.getDetectionsList()) {
            float cx = (d.getBbox().getX1() + d.getBbox().getX3()) / 2f;
            float cy = (d.getBbox().getY1() + d.getBbox().getY3()) / 2f;

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

            if (bounds.isValid() && imgW > 0 && imgH > 0) {
                double detLng = bounds.west() + (cx / imgW) * (bounds.east() - bounds.west());
                double detLat = bounds.north() - (cy / imgH) * (bounds.north() - bounds.south());
                det.put("lng", Math.round(detLng * 1_000_000) / 1_000_000.0);
                det.put("lat", Math.round(detLat * 1_000_000) / 1_000_000.0);
            }
            detections.add(det);
        }
        result.put("detections", detections);

        if (bounds.isValid()) {
            result.put("geo", Map.of(
                "north", bounds.north(),
                "south", bounds.south(),
                "east", bounds.east(),
                "west", bounds.west(),
                "centerLat", bounds.centerLat(),
                "centerLng", bounds.centerLng(),
                "imageWidth", imgW,
                "imageHeight", imgH
            ));
        }
        double centerLat = bounds.isValid() ? bounds.centerLat() : (lat > -999 ? lat : 0);
        double centerLng = bounds.isValid() ? bounds.centerLng() : (lng > -999 ? lng : 0);
        if (centerLat != 0 || centerLng != 0) {
            result.put("centerLat", centerLat);
            result.put("centerLng", centerLng);
        }

        return ResponseEntity.ok(result);
    }
}
