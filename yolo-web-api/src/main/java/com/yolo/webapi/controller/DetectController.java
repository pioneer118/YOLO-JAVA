package com.yolo.webapi.controller;

// ImageTiler：推理模块里的工具类，用来读取图片的地理坐标（经纬度）
import com.yolo.inference.ImageTiler;
// proto.*：导入 Protobuf 生成的消息类（DetectRequest/DetectResponse/Detection 等）
import com.yolo.proto.*;
// DubboReference：Dubbo 注解，标注"我要调用一个远程服务"
import org.apache.dubbo.config.annotation.DubboReference;
// ResponseEntity：Spring 的"HTTP 响应"对象，能携带状态码 + 返回数据
import org.springframework.http.ResponseEntity;
// web.bind.annotation.*：Spring MVC 处理请求的注解（RestController/Mapping/RequestParam 等）
import org.springframework.web.bind.annotation.*;
// MultipartFile：Spring 封装"上传文件"的类（接收前端上传的图片）
import org.springframework.web.multipart.MultipartFile;

import java.util.*;   // Java 工具类（Map/List/Base64 等）

@RestController                // 声明：这是一个 HTTP 控制器，处理网页请求
@RequestMapping("/api")        // 这个类里所有方法的网址，都以 /api 开头
public class DetectController {   // 控制器类：接收前端请求，调网关，返回结果

    /**
     * @DubboReference：声明"我要调用远程的网关服务"
     *   version  = "1.0.0"  调用的服务版本
     *   check    = false    启动时不检查服务是否存在（避免网关没起就启动失败）
     * yoloGateway：接口类型字段，运行时是 Dubbo 生成的"远程代理对象"，
     *   调用它的方法 = 通过网络调用真正的网关
     */
    @DubboReference(version = "1.0.0", check = false)
    private YoloAggregationService yoloGateway;

    /**
     * 处理 POST /api/detect 请求：接收上传的图片和参数，调用网关检测，返回结果。
     *
     * @param image        上传的图片文件（MultipartFile = Spring 封装的"上传文件对象"）
     * @param dataSource   数据来源：satellite（卫星）/ drone（无人机），默认 satellite
     * @param confThreshold 置信度阈值：低于它的检测会被过滤，默认 0.5
     * @param autoTile     是否自动裁切大图，默认 true
     * @param gsd          地面采样距离（米/像素），无人机参数，默认 -1 表示没传
     * @param lat          纬度（无人机拍摄位置），默认 -999 表示没传
     * @param lng          经度（无人机拍摄位置），默认 -999 表示没传
     * @param targets      要检测的目标类型，逗号分隔，默认 "ship,plane,car"（全选）
     * @return HTTP 响应，内容是检测结果（Spring 自动转成 JSON 返回给前端）
     * @throws Exception   方法可能出错（如图片解码失败）
     */
    @PostMapping("/detect")     // 处理 POST /api/detect 请求
    public ResponseEntity<Map<String, Object>> detect(
            // 从请求里取出名为 image 的文件，装进 MultipartFile
            @RequestParam("image") MultipartFile image,
            // 取 dataSource 参数，没传就用默认值 "satellite"
            @RequestParam(defaultValue = "satellite") String dataSource,
            // 取 confThreshold 参数，没传就用默认值 0.5
            @RequestParam(defaultValue = "0.6") float confThreshold,
            // 取 autoTile 参数，没传就用默认值 true
            @RequestParam(defaultValue = "true") boolean autoTile,
            // 取 gsd 参数，没传就用默认值 -1（表示没有）
            @RequestParam(defaultValue = "-1") float gsd,
            // 取 lat 参数，没传就用默认值 -999（表示没有）
            @RequestParam(defaultValue = "-999") float lat,
            // 取 lng 参数，没传就用默认值 -999（表示没有）
            @RequestParam(defaultValue = "-999") float lng,
            // 取 targets 参数，没传就用默认值 "ship,plane,car"（全部检测）
            @RequestParam(defaultValue = "ship,plane,car") String targets) throws Exception {

        // 把上传的图片文件，转成字节数组（图片在电脑里就是一堆字节）
        byte[] imageBytes = image.getBytes();

        // 将无人机参数和目标选择编码到 data_source 中
        // 格式: "drone:gsd=0.05:lat=30.1:lng=120.2:targets=ship,plane"
        StringBuilder sb = new StringBuilder(dataSource);   // 用 dataSource 当开头（如 "satellite"）
        if (gsd > 0) {                    // 如果传了 gsd（无人机地面分辨率）
            sb.append(":gsd=").append(gsd);   // 追加 ":gsd=0.05"
            if (lat > -999) sb.append(":lat=").append(lat);   // 如果传了纬度，追加
            if (lng > -999) sb.append(":lng=").append(lng);   // 如果传了经度，追加
        }
        if (targets != null && !targets.isEmpty() && !"ship,plane,car".equals(targets)) {
            sb.append(":targets=").append(targets);   // 如果目标不是全选，追加 ":targets=..."
        }

        // 用 Protobuf 的"构建器模式"打包请求消息
        AggregateDetectRequest request = AggregateDetectRequest.newBuilder()   // 创建构建器
            .setImageData(com.google.protobuf.ByteString.copyFrom(imageBytes)) // ① 放入图片字节
            .setDataSource(sb.toString())       // ② 放入拼接好的 dataSource 字符串
            .setConfThreshold(confThreshold)    // ③ 放入置信度阈值
            .build();                           // ④ 生成不可变的消息对象

        // 调用网关（远程服务）：yoloGateway 是 Dubbo 生成的代理对象，这行实际走网络
        AggregateDetectResponse response = yoloGateway.detect(request);

        // 提前提取地理坐标信息（循环中需要用到）
        // getGeoBounds：解析图片（如 TIFF）携带的经纬度边界
        ImageTiler.GeoBounds bounds = ImageTiler.getGeoBounds(imageBytes);
        int imgW = bounds.imageWidth();     // 图片宽度（像素）
        int imgH = bounds.imageHeight();    // 图片高度（像素）

        // 用 Map 组装返回给前端的 JSON 数据（LinkedHashMap 保证顺序）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", response.getTotalCount());       // 检测目标总数
        result.put("processingTimeMs", response.getProcessingTimeMs());  // 检测耗时
        // 标注图：转成 base64 字符串（图片是二进制，JSON 是文本，要编码成文本才能传）
        result.put("annotatedImage", "data:image/jpeg;base64,"
            + Base64.getEncoder().encodeToString(response.getAnnotatedImage().toByteArray()));

        // 遍历网关返回的每个检测目标，整理成前端要的格式
        List<Map<String, Object>> detections = new ArrayList<>();   // 检测结果列表
        for (Detection d : response.getDetectionsList()) {          // 逐个检测目标
            // 算检测框中心：两个对角点 (x1,y1) 和 (x3,y3) 的平均值
            float cx = (d.getBbox().getX1() + d.getBbox().getX3()) / 2f;   // 中心 X
            float cy = (d.getBbox().getY1() + d.getBbox().getY3()) / 2f;   // 中心 Y

            // 单个目标的 Map
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("classId", d.getClassId());       // 类别编号
            det.put("className", d.getClassName());   // 类别名（如 "[ship] 驱逐舰"）
            det.put("confidence", d.getConfidence()); // 置信度
            // 旋转框的四个角点坐标
            det.put("bbox", Map.of(
                "x1", d.getBbox().getX1(), "y1", d.getBbox().getY1(),
                "x2", d.getBbox().getX2(), "y2", d.getBbox().getY2(),
                "x3", d.getBbox().getX3(), "y3", d.getBbox().getY3(),
                "x4", d.getBbox().getX4(), "y4", d.getBbox().getY4()
            ));

            // 如果有地理信息，把像素坐标换算成经纬度（三维地球定位用）
            if (bounds.isValid() && imgW > 0 && imgH > 0) {
                // 像素 X → 经度（按图片横向范围等比例映射）
                double detLng = bounds.west() + (cx / imgW) * (bounds.east() - bounds.west());
                // 像素 Y → 纬度（按图片纵向范围等比例映射，注意纬度从北往下）
                double detLat = bounds.north() - (cy / imgH) * (bounds.north() - bounds.south());
                det.put("lng", Math.round(detLng * 1_000_000) / 1_000_000.0);  // 保留6位小数
                det.put("lat", Math.round(detLat * 1_000_000) / 1_000_000.0);
            }
            detections.add(det);    // 把单个目标加入列表
        }
        result.put("detections", detections);   // 把列表放进结果 Map

        // 如果有地理信息，把图片的经纬度边界也返回（前端三维地球定位图片用）
        if (bounds.isValid()) {
            result.put("geo", Map.of(
                "north", bounds.north(),       // 北边界
                "south", bounds.south(),       // 南边界
                "east", bounds.east(),         // 东边界
                "west", bounds.west(),         // 西边界
                "centerLat", bounds.centerLat(),  // 中心纬度
                "centerLng", bounds.centerLng(),  // 中心经度
                "imageWidth", imgW,            // 图片宽
                "imageHeight", imgH            // 图片高
            ));
        }
        // 计算图片/无人机的中心坐标（有地理信息用图片中心，否则用前端传的无人机坐标）
        double centerLat = bounds.isValid() ? bounds.centerLat() : (lat > -999 ? lat : 0);
        double centerLng = bounds.isValid() ? bounds.centerLng() : (lng > -999 ? lng : 0);
        if (centerLat != 0 || centerLng != 0) {          // 如果中心坐标有效
            result.put("centerLat", centerLat);          // 返回中心纬度
            result.put("centerLng", centerLng);          // 返回中心经度
        }

        return ResponseEntity.ok(result);   // 返回 HTTP 200 + 结果 Map（Spring 自动转 JSON）
    }
}
