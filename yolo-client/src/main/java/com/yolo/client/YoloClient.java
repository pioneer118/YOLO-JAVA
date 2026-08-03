package com.yolo.client;

import com.yolo.proto.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.context.annotation.DubboComponentScan;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@DubboComponentScan
public class YoloClient implements CommandLineRunner {

    @DubboReference(version = "1.0.0", check = false)
    private YoloAggregationService gatewayService;

    public static void main(String[] args) {
        SpringApplication.run(YoloClient.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: java -jar yolo-client.jar <图片路径> [输出路径] [阈值]");
            return;
        }

        String imagePath = args[0];
        String outputPath = args.length > 1 ? args[1] : "output.jpg";
        float confThreshold = args.length > 2 ? Float.parseFloat(args[2]) : 0.5f;

        byte[] imageBytes = Files.readAllBytes(Path.of(imagePath));

        AggregateDetectRequest request = AggregateDetectRequest.newBuilder()
            .setImageData(com.google.protobuf.ByteString.copyFrom(imageBytes))
            .setConfThreshold(confThreshold)
            .setDataSource("satellite")
            .build();

        System.out.println("正在检测: " + imagePath);
        long start = System.currentTimeMillis();
        AggregateDetectResponse response = gatewayService.detect(request);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("检测到 " + response.getTotalCount() + " 个目标，耗时 " + elapsed + "ms");
        for (Detection d : response.getDetectionsList()) {
            System.out.printf("  %s: %.2f%n", d.getClassName(), d.getConfidence());
        }

        if (!response.getAnnotatedImage().isEmpty()) {
            Files.write(Path.of(outputPath), response.getAnnotatedImage().toByteArray());
            System.out.println("标注图已保存到: " + outputPath);
        }

        System.exit(0);
    }
}
