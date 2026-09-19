package com.hiki.isup.config;

import com.hiki.isup.util.MediaPaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Web 配置：
 * <ul>
 *   <li>/media/** 映射到本地媒体目录，供浏览器直接播放 HLS</li>
 *   <li>/api/v1/** 走 API Key 鉴权</li>
 *   <li>补充 m3u8 / ts 的 MIME 类型，避免浏览器无法识别</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final IsupProperties properties;
    private final ApiKeyInterceptor apiKeyInterceptor;

    @Autowired
    public WebConfig(IsupProperties properties, ApiKeyInterceptor apiKeyInterceptor) {
        this.properties = properties;
        this.apiKeyInterceptor = apiKeyInterceptor;
    }

    /**
     * /media/** 直接映射到本地媒体目录，浏览器可播放 HLS。
     * m3u8 / ts 的 MIME 类型通过 src/main/resources/META-INF/mime.types 补充。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = MediaPaths.mediaRoot(properties.getMediaDir());
        String location = "file:" + MediaPaths.ensureTrailingSlash(root.toString());
        registry.addResourceHandler("/media/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.noStore());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/health");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
