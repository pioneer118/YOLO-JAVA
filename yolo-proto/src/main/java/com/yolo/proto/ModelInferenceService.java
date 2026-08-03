package com.yolo.proto;

/**
 * 后端模型推理服务 RPC 接口。
 * 三个模型服务（ship/plane/car）各自实现此接口，
 * 通过不同的 Dubbo group 注册到 Nacos。
 */
public interface ModelInferenceService {
    ModelDetectResponse detect(ModelDetectRequest request);
}
