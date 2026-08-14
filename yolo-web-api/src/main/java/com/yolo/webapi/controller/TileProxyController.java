 package com.yolo.webapi.controller;

import org.springframework.http.MediaType;      // 响应类型（如图片、JSON）
import org.springframework.http.ResponseEntity; // HTTP 响应对象
import org.springframework.web.bind.annotation.*; // 处理请求的注解（RestController等）

import java.io.ByteArrayOutputStream;   // 字节输出流（把抓到的图片字节存起来）
import java.io.InputStream;             // 字节输入流（读网络返回的内容）
import java.net.HttpURLConnection;      // Java 自带的 HTTP 连接（发请求抓瓦片）
import java.net.URL;                    // 网址对象
import java.util.Map;                   // Map 键值对
import java.util.concurrent.ConcurrentHashMap;  // 线程安全的 Map（做缓存）

@RestController                         // 声明：我是 HTTP 控制器
@RequestMapping("/api/tile")           // 这个类处理的网址以 /api/tile 开头
public class TileProxyController {

    // 瓦片源：一个数组，存了多个"地图服务器地址模板"
    private static final String[] TILE_SOURCES = {
        // 主源：Esri World Imagery（全球卫星影像，注意顺序是 {z}/{y}/{x}）
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        // 备源：腾讯矢量瓦片（普通地图，{z}/{x}/{y} 顺序）
        "https://rt0.map.gtimg.com/tile?z={z}&x={x}&y={y}&styleid=1",
    };

    // 缓存上限：最多缓存 500 张瓦片，避免内存膨胀
    private static final int MAX_CACHE = 500;
    // 缓存：ConcurrentHashMap（线程安全）存已抓过的瓦片，key=坐标，value=图片字节
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    /**
     * 处理前端请求：GET /api/tile/{z}/{x}/{y}
     * 参数说明：
     *   z = 缩放级别（越小看的地图越大）
     *   x = 瓦片横坐标
     *   y = 瓦片纵坐标
     */
    @GetMapping("/{z}/{x}/{y}")
    public ResponseEntity<byte[]> tile(@PathVariable int z,   // @PathVariable：从网址里取 z 的值
                                       @PathVariable int x,   // 从网址里取 x 的值
                                       @PathVariable int y) { // 从网址里取 y 的值

        // 用坐标组合成缓存的 key，例如 "5/25/25"
        String key = z + "/" + x + "/" + y;

        // 先从缓存里找：如果之前抓过这张瓦片，直接返回，省一次网络请求
        byte[] cached = cache.get(key);
        if (cached != null) {
            return ResponseEntity.ok()                          // 返回 HTTP 200
                .contentType(MediaType.IMAGE_JPEG)             // 内容是 JPEG 图片
                .body(cached);                                 // 图片字节
        }

        // 缓存没有：依次尝试所有瓦片源，取第一个成功的
        byte[] bytes = null;
        for (String template : TILE_SOURCES) {
            // 把模板里的 {z} {x} {y} 替换成真实的数字
            String url = template
                .replace("{z}", String.valueOf(z))
                .replace("{x}", String.valueOf(x))
                .replace("{y}", String.valueOf(y));
            bytes = fetchSingle(url);     // 去这个网址抓瓦片
            if (bytes != null && bytes.length > 0) {  // 抓到非空内容就成功
                break;
            }
        }

        // 所有源都失败了：返回 404（前端显示空白瓦片，不崩溃）
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }

        // 简单缓存：如果缓存满了（500张），清空重来，避免内存爆炸
        if (cache.size() >= MAX_CACHE) {
            cache.clear();
        }
        cache.put(key, bytes);            // 存进缓存，下次直接取

        return ResponseEntity.ok()                          // 返回 HTTP 200
            .contentType(MediaType.IMAGE_JPEG)             // JPEG 图片
            .body(bytes);                                 // 瓦片字节
    }

    /**
     * 用 Java 自带的 HttpURLConnection 去抓一张瓦片。
     * 设置 UA/Referer 绕过防盗链，超时 5 秒。
     */
    private byte[] fetchSingle(String url) {
        try {
            // ① 建立 HTTP 连接
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);   // 连接超时 5 秒
            conn.setReadTimeout(5000);      // 读取超时 5 秒
            // 模拟浏览器请求头，绕过地图服务器的防盗链
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "http://localhost:8080/");
            conn.setRequestProperty("Accept", "image/webp,image/*,*/*;q=0.8");

            // ② 检查返回状态码
            int code = conn.getResponseCode();
            if (code != 200) {              // 不是 200（成功）就返回 null
                System.out.println("[TileProxy] HTTP " + code + " for " + url);
                return null;
            }

            // ③ 读取返回的图片字节
            try (InputStream in = conn.getInputStream()) {   // 打开输入流（自动关闭）
                ByteArrayOutputStream out = new ByteArrayOutputStream();  // 存字节
                byte[] buf = new byte[8192];   // 缓冲区（一次读 8KB）
                int n;
                while ((n = in.read(buf)) != -1) {   // 循环读，直到读完
                    out.write(buf, 0, n);           // 写入结果
                }
                return out.toByteArray();           // 返回完整图片字节
            }
        } catch (Exception e) {                     // 任何异常都返回 null（前端显示空白瓦片）
            System.out.println("[TileProxy] Error for " + url + ": " + e.getMessage());
            return null;
        }
    }
}
