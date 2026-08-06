package com.yolo.server;

import com.yolo.inference.YoloClassNames;
import com.yolo.inference.YoloInferenceEngine;
import org.apache.dubbo.config.spring.context.annotation.DubboComponentScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import ai.onnxruntime.OrtException;

@SpringBootApplication
@DubboComponentScan
public class YoloServerApplication {

    public static void main(String[] args) {
        String osName = System.getProperty("os.name").toLowerCase();
        String onnxLibPath = System.getProperty("onnxruntime.lib.path");
        if (onnxLibPath != null && !onnxLibPath.isEmpty()) {
            System.load(onnxLibPath);
        }
        SpringApplication.run(YoloServerApplication.class, args);
    }

    @Bean
    public YoloInferenceEngine inferenceEngine(
            @Value("${yolo.model-path}") String modelPath,
            @Value("${yolo.model-type:ship}") String modelType) throws OrtException {
        return new YoloInferenceEngine(modelPath, YoloClassNames.forModelType(modelType));
    }
}
