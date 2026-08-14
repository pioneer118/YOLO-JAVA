package com.yolo.gateway;

import org.apache.dubbo.config.spring.context.annotation.DubboComponentScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootApplication
@DubboComponentScan
public class YoloGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(YoloGatewayApplication.class, args);
    }

    @Bean  //这个方法创建了一个"虚拟线程执行器"（一个能派活给线程干活的调度器），并放进 Spring 容器，供网关的调度逻辑使用。
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
