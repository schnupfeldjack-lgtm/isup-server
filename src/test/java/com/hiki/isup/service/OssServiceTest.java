package com.hiki.isup.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.sun.net.httpserver.HttpServer;
import com.hiki.isup.config.IsupProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OssServiceTest {

    @Test
    void realSdkUploadStopsWithinRowDeadline(@TempDir Path tempDir) throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch received = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> method = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                method.set(exchange.getRequestMethod());
                received.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        IsupProperties settings = new IsupProperties();
        settings.getOss().setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        settings.getOss().setBucket("test-bucket");
        settings.getOss().setAccessKeyId("test-key");
        settings.getOss().setAccessKeySecret("test-secret");
        try (OssService service = new OssService(settings)) {
            Path file = Files.write(tempDir.resolve("blocked.mp4"), new byte[]{1, 2, 3});
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                    assertThrows(RuntimeException.class, () -> service.upload(file, "blocked.mp4",
                            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1))));
            assertTrue(received.await(1, java.util.concurrent.TimeUnit.SECONDS), "必须确实到达慢响应端点，不能把其他异常当作超时通过");
            assertEquals("PUT", method.get());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void configuresAllSdkTimeoutsAndDisablesImplicitRetries() {
        IsupProperties.Oss properties = new IsupProperties().getOss();
        ClientBuilderConfiguration config = OssService.clientConfiguration(properties, 250);
        assertTrue(config.isRequestTimeoutEnabled());
        assertTrue(config.getConnectionTimeout() > 0 && config.getConnectionTimeout() <= 250);
        assertTrue(config.getSocketTimeout() > 0 && config.getSocketTimeout() <= 250);
        assertTrue(config.getConnectionRequestTimeout() > 0 && config.getConnectionRequestTimeout() <= 250);
        assertTrue(config.getRequestTimeout() > 0 && config.getRequestTimeout() <= 250);
        assertEquals(0, config.getMaxErrorRetry());
    }

    @Test
    void expiredRowCannotStartUpload(@TempDir Path tempDir) throws Exception {
        IsupProperties properties = new IsupProperties();
        properties.getOss().setEndpoint("oss-cn-beijing.aliyuncs.com");
        properties.getOss().setBucket("test-bucket");
        properties.getOss().setAccessKeyId("test-key");
        properties.getOss().setAccessKeySecret("test-secret");
        AtomicInteger builds = new AtomicInteger();
        try (OssService service = new OssService(properties, (endpoint, key, secret, config) -> {
            builds.incrementAndGet();
            return mock(OSS.class);
        })) {
            Path file = Files.write(tempDir.resolve("expired.mp4"), new byte[]{1});
            assertThrows(RuntimeException.class, () -> service.upload(file, "expired.mp4", System.nanoTime() - 1));
            assertEquals(0, builds.get());
        }
    }

    @Test
    @DisplayName("真实 OSS SDK 通过 spring-jcl 向本地测试端点上传")
    void realSdkUploadsWithSpringLoggingBridge(@TempDir Path tempDir) throws Exception {
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                method.set(exchange.getRequestMethod());
                uploaded.set(exchange.getRequestBody().readAllBytes());
                exchange.getResponseHeaders().set("ETag", "\"5289DF737DF57326FCDD22597AFB1FAC\"");
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        IsupProperties properties = new IsupProperties();
        properties.getOss().setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getOss().setBucket("test-bucket");
        properties.getOss().setAccessKeyId("test-key");
        properties.getOss().setAccessKeySecret("test-secret");
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setMaxErrorRetry(0);
        configuration.setConnectionTimeout(2000);
        configuration.setSocketTimeout(2000);
        try (OssService service = new OssService(properties, (endpoint, key, secret, config) ->
                new OSSClientBuilder().build(endpoint, key, secret, configuration))) {
            byte[] content = {1, 2, 3};
            Path file = Files.write(tempDir.resolve("sdk-upload.mp4"), content);
            service.upload(file, "test.mp4");
            assertEquals("PUT", method.get());
            org.junit.jupiter.api.Assertions.assertArrayEquals(content, uploaded.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("对象 key 含日期前缀、记录 id 与通道号")
    void objectKeyIncludesDateIdAndChannel() {
        String key = OssService.buildObjectKey("waybill-video/", "2101588195825704961", 2,
                LocalDateTime.of(2026, 9, 20, 16, 23, 54));
        assertEquals("waybill-video/2026/09/20/2101588195825704961_ch2.mp4", key);
    }

    @Test
    @DisplayName("prefix 缺尾斜杠时自动补上")
    void prefixSlashNormalized() {
        String key = OssService.buildObjectKey("waybill-video", "abc", 1,
                LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        assertEquals("waybill-video/2026/01/02/abc_ch1.mp4", key);
    }

    @Test
    @DisplayName("id 中的路径字符会被去掉，避免对象 key 穿越")
    void objectKeySanitizesId() {
        String key = OssService.buildObjectKey("p/", "../etc/passwd", 1,
                LocalDateTime.of(2026, 9, 20, 0, 0, 0));
        assertTrue(!key.contains(".."), key);
        assertTrue(!key.contains("//etc"), key);
        assertEquals("p/2026/09/20/___etc_passwd_ch1.mp4", key);
    }

    @Test
    @DisplayName("公网 URL 为 https://bucket.endpoint/key")
    void publicUrl() {
        String url = OssService.buildPublicUrl("my-bucket", "oss-cn-hangzhou.aliyuncs.com",
                "waybill-video/a.mp4");
        assertEquals("https://my-bucket.oss-cn-hangzhou.aliyuncs.com/waybill-video/a.mp4", url);
    }

    @Test
    @DisplayName("endpoint 已带协议时不再重复拼接")
    void endpointWithScheme() {
        String url = OssService.buildPublicUrl("bkt", "https://oss-cn-beijing.aliyuncs.com", "k.mp4");
        assertEquals("https://bkt.oss-cn-beijing.aliyuncs.com/k.mp4", url);
    }

    @Test
    @DisplayName("bucket / endpoint / key 为空时拒绝生成 URL")
    void blankPublicUrlRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OssService.buildPublicUrl(" ", "oss-cn-hangzhou.aliyuncs.com", "k"));
    }

    @Test
    @DisplayName("配置了 staticDomain 时公网 URL 走 CDN，不拼 bucket.endpoint")
    void staticDomainOverridesBucketUrl() {
        String url = OssService.buildPublicUrl("zzdy-video", "oss-cn-beijing.aliyuncs.com",
                "waybill-video/a.mp4", "https://dev-img.globaltrade.fashion");
        assertEquals("https://dev-img.globaltrade.fashion/waybill-video/a.mp4", url);
    }

    @Test
    @DisplayName("ERP 字段名 accessKey/secretKey/bucketName 也能被识别为已配置")
    void erpFieldAliasesCountAsConfigured() {
        IsupProperties.Oss oss = new IsupProperties.Oss();
        oss.setEndpoint("oss-cn-beijing.aliyuncs.com");
        oss.setAccessKey("ak");
        oss.setSecretKey("sk");
        oss.setBucketName("zzdy-video");
        IsupProperties properties = new IsupProperties();
        properties.setOss(oss);
        assertTrue(new OssService(properties).isConfigured());
    }

    @Test
    @DisplayName("只填 ERP 字段名之外的无关项时仍视为未配置")
    void blankCanonicalFieldsNotConfigured() {
        assertFalse(new OssService(new IsupProperties()).isConfigured());
    }

    @Test
    @DisplayName("多次上传复用同一个 OSS 客户端，关闭服务时才 shutdown")
    void reusesClientAcrossUploads(@TempDir Path tempDir) throws Exception {
        IsupProperties properties = new IsupProperties();
        properties.getOss().setEndpoint("oss-cn-beijing.aliyuncs.com");
        properties.getOss().setBucket("zzdy-video");
        properties.getOss().setAccessKeyId("ak");
        properties.getOss().setAccessKeySecret("sk");

        OSS client = mock(OSS.class);
        AtomicInteger builds = new AtomicInteger();
        OssService service = new OssService(properties, (endpoint, accessKeyId, accessKeySecret, config) -> {
            builds.incrementAndGet();
            return client;
        });
        Path file = Files.write(tempDir.resolve("a.mp4"), new byte[]{1, 2, 3});

        service.upload(file, "k1.mp4");
        service.upload(file, "k2.mp4");

        assertEquals(1, builds.get());
        verify(client, times(2)).putObject(anyString(), anyString(), any(java.io.File.class));
        verify(client, never()).shutdown();
        service.close();
        verify(client).shutdown();
    }
}
