package com.hiki.isup.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：
 * <ul>
 *   <li>/media/** 由 MediaController 校验短期签名后读取</li>
 *   <li>/api/v1/** 走 API Key 鉴权</li>
 *   <li>补充 m3u8 / ts 的 MIME 类型，避免浏览器无法识别</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiKeyInterceptor apiKeyInterceptor;

    public WebConfig(ApiKeyInterceptor apiKeyInterceptor) {
        this.apiKeyInterceptor = apiKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/health");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // HEAD 必须显式放行：HLS 起播前前端会用 HEAD 探测 index.m3u8 是否已生成，
        // 如果 allowedMethods 里没有 HEAD，跨域探测直接被 Spring 拒成 403，
        // 表现为 m3u8 取不到（浏览器只报 CORS/403，看不出是方法没放行）。
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
