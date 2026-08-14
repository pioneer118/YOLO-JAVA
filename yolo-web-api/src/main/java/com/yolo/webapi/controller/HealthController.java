package com.yolo.webapi.controller;

// ResponseEntity：Spring 的"HTTP 响应"对象，可以携带状态码 + 数据
import org.springframework.http.ResponseEntity;
// GetMapping：标注方法处理 GET 请求（查询类请求）
import org.springframework.web.bind.annotation.GetMapping;
// RequestMapping：标注类或方法的网址前缀
import org.springframework.web.bind.annotation.RequestMapping;
// RestController：标注"这是一个 HTTP 控制器"，处理网页请求，返回值自动转 JSON
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;      // 时间工具：获取当前时间
import java.util.Map;          // Map：键值对集合（用来组装返回的 JSON）

@RestController                // 声明：我是 HTTP 控制器
@RequestMapping("/api")        // 这个类里所有方法，网址都以 /api 开头
public class HealthController {

    @GetMapping("/health")     // 处理 GET /api/health 请求
    public ResponseEntity<Map<String, Object>> health() {   // 返回类型：HTTP 响应，内容是 Map

        // 返回 HTTP 200，内容是 3 个键值对（Spring 自动转成 JSON）
        return ResponseEntity.ok(Map.of(
            "status", "UP",                     // ① 服务状态：UP=正常
            "gateway", "UP",                    // ② 网关状态：UP=网关也正常
            "timestamp", Instant.now().toString()  // ③ 当前时间（方便排查是什么时候检查的）
        ));
    }
}
