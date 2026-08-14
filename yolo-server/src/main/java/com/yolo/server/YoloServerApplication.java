package com.yolo.server;

// YoloClassNames：推理模块的类别名工具（按模型类型返回类别名）
import com.yolo.inference.YoloClassNames;
// YoloInferenceEngine：推理引擎（加载 ONNX 模型，做检测）
import com.yolo.inference.YoloInferenceEngine;
// DubboComponentScan：Dubbo 注解，扫描 Dubbo 注解（@DubboService/@DubboReference）
import org.apache.dubbo.config.spring.context.annotation.DubboComponentScan;
// Value：从配置文件读值注入
import org.springframework.beans.factory.annotation.Value;
// SpringApplication：Spring Boot 启动类
import org.springframework.boot.SpringApplication;
// SpringBootApplication：Spring Boot 主注解（标记启动类）
import org.springframework.boot.autoconfigure.SpringBootApplication;
// Bean：Spring 注解，把方法返回的对象放进容器
import org.springframework.context.annotation.Bean;

// OrtException：ONNX Runtime 的异常（模型加载可能抛这个）
import ai.onnxruntime.OrtException;

@SpringBootApplication       // 声明：这是 Spring Boot 启动类
@DubboComponentScan          // 声明：扫描 com.yolo.server 包下的 Dubbo 注解
public class YoloServerApplication {

    // main 方法：程序的入口（Java 程序从这里开始运行）
    public static void main(String[] args) {
        String osName = System.getProperty("os.name").toLowerCase();   // 读取操作系统名（windows/linux）
        String onnxLibPath = System.getProperty("onnxruntime.lib.path");   // 读启动参数里的 ONNX 库路径
        if (onnxLibPath != null && !onnxLibPath.isEmpty()) {   // 如果指定了 ONNX 库路径
            System.load(onnxLibPath);   // ★ 加载 ONNX 本地库（dll/so），不加载后面推理会报 UnsatisfiedLinkError
        }
        SpringApplication.run(YoloServerApplication.class, args);   // ★ 启动 Spring Boot 应用
    }

    /**
     * 创建"推理引擎"Bean，放进 Spring 容器。
     * 引擎在启动时加载模型（98MB 只加载一次），之后所有请求共用它。
     *
     * @param modelPath  模型文件路径（从配置 yolo.model-path 读，启动参数可覆盖）
     * @param modelType  模型类型：ship/plane/car（从配置 yolo.model-type 读）
     * @return 创建好的推理引擎（已加载模型）
     * @throws OrtException 模型加载可能失败
     */
    @Bean
    public YoloInferenceEngine inferenceEngine(
            @Value("${yolo.model-path}") String modelPath,        // 从配置读模型路径（如 models/bestship.onnx）
            @Value("${yolo.model-type:ship}") String modelType)   // 从配置读模型类型（默认 ship）
            throws OrtException {
        // 创建推理引擎：加载模型 + 用对应类别的类别名
        // YoloClassNames.forModelType(modelType)：根据 modelType 返回对应的类别名列表
        return new YoloInferenceEngine(modelPath, YoloClassNames.forModelType(modelType));
    }
}
