package com.yolo.webapi.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地图瓦片代理。
 *
 * <p>浏览器在某些网络环境下无法直接访问第三方瓦片服务（系统代理/网络限制），
 * 但后端 JVM 网络栈可以。本控制器在前端与瓦片服务之间做代理：
 * 前端请求 {@code /api/tile/{z}/{x}/{y}}，后端转发到瓦片源并返回图片字节。
 *
 * <p>主源使用 Esri World Imagery（WGS84 卫星影像，与 GeoTIFF 坐标匹配），
 * 备源为腾讯矢量瓦片。
 */
@RestController
@RequestMapping("/api/tile")
public class TileProxyController {

    /** 瓦片源：Esri World Imagery（WGS84 卫星影像） */
    private static final String[] TILE_SOURCES = {
        // 主源：Esri World Imagery（注意 Esri 是 {z}/{y}/{x} 顺序）
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        // 备源：腾讯矢量瓦片（styleid=1，普通地图）
        "https://rt0.map.gtimg.com/tile?z={z}&x={x}&y={y}&styleid=1",
    };

    /** 简单 LRU 缓存，避免重复抓取热门瓦片 */
    private static final int MAX_CACHE = 500;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    @GetMapping("/{z}/{x}/{y}")
    public ResponseEntity<byte[]> tile(@PathVariable int z,
                                       @PathVariable int x,
                                       @PathVariable int y) {
        String key = z + "/" + x + "/" + y;

        byte[] cached = cache.get(key);
        if (cached != null) {
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(cached);
        }

        // 依次尝试所有源，取第一个非空响应
        byte[] bytes = null;
        for (String template : TILE_SOURCES) {
            String url = template
                .replace("{z}", String.valueOf(z))
                .replace("{x}", String.valueOf(x))
                .replace("{y}", String.valueOf(y));
            bytes = fetchSingle(url);
            if (bytes != null && bytes.length > 0) {
                break;
            }
        }

        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }

        // 简单缓存（达到上限时清空，避免内存膨胀）
        if (cache.size() >= MAX_CACHE) {
            cache.clear();
        }
        cache.put(key, bytes);

        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes);
    }

    /**
     * 用 JVM HttpURLConnection 抓取瓦片。
     * 设置 UA/Referer 绕过防盗链，超时 5 秒。
     */
    private byte[] fetchSingle(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "http://localhost:8080/");
            conn.setRequestProperty("Accept", "image/webp,image/*,*/*;q=0.8");

            int code = conn.getResponseCode();
            if (code != 200) {
                System.out.println("[TileProxy] HTTP " + code + " for " + url);
                return null;
            }

            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            System.out.println("[TileProxy] Error for " + url + ": " + e.getMessage());
            return null;
        }
    }
}
