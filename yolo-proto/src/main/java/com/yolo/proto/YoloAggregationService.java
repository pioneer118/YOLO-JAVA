package com.yolo.proto;

/**
 * 网关聚合服务 RPC 接口。
 * 网关暴露此接口，客户端（yolo-web-api / yolo-client）只和此接口打交道。
 */
public interface YoloAggregationService {
    AggregateDetectResponse detect(AggregateDetectRequest request);
}
