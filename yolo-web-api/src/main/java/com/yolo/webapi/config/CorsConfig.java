package com.yolo.webapi.config;

// @Configuration：告诉 Spring"这是一个配置类"，Spring 启动时会处理它
import org.springframework.context.annotation.Configuration;
// CorsRegistry：Spring 提供的"跨域规则注册器"，用来登记允许哪些跨域请求
import org.springframework.web.servlet.config.annotation.CorsRegistry;
// WebMvcConfigurer：Spring MVC 的配置接口，实现它可以自定义 MVC 行为（如跨域）
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration                       // 声明：这是一个配置类，Spring 启动时会加载它
public class CorsConfig implements WebMvcConfigurer {   // 实现 WebMvcConfigurer 接口，自定义 MVC 配置

    @Override                      // 覆盖父接口的方法：添加跨域规则
    public void addCorsMappings(CorsRegistry registry) {   // registry = 跨域规则登记器

        registry.addMapping("/api/**")                     // ① 对哪些网址生效：所有 /api 开头的请求
            .allowedOrigins("http://localhost:5173")       // ② 允许哪个来源（前端页面地址）跨域访问
            .allowedMethods("GET", "POST", "OPTIONS")      // ③ 允许哪些 HTTP 方法（GET=查询, POST=提交, OPTIONS=预检）
            .allowedHeaders("*");                          // ④ 允许携带任意请求头（如 Content-Type）
    }
}
