package com.hiki.isup.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.CameraChannel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 把本地 MP4 传到阿里云 OSS，返回公网 URL。
 *
 * <p>对象 key 与公网 URL 的拼接是纯函数，方便单测；真正的 SDK 调用只发生在 {@link #upload}。</p>
 */
@Service
public class OssService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OssService.class);

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final IsupProperties properties;
    private final ClientFactory clientFactory;
    private final Object clientLock = new Object();
    private OSS client;

    @Autowired
    public OssService(IsupProperties properties) {
        this(properties, (endpoint, accessKeyId, accessKeySecret, configuration) ->
                new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret, configuration));
    }

    OssService(IsupProperties properties, ClientFactory clientFactory) {
        this.properties = properties;
        this.clientFactory = clientFactory;
    }

    public boolean isConfigured() {
        IsupProperties.Oss oss = properties.getOss();
        return isPresent(oss.getEndpoint())
                && isPresent(oss.getBucket())
                && isPresent(oss.getAccessKeyId())
                && isPresent(oss.getAccessKeySecret());
    }

    /**
     * 上传本地文件，返回公网 HTTPS URL。
     */
    public String upload(Path localFile, String objectKey) {
        return uploadWithClient(localFile, objectKey, Long.MAX_VALUE, false);
    }

    /** 每次请求以该行剩余预算配置独立客户端，避免并发修改共享连接池的超时。 */
    public String upload(Path localFile, String objectKey, long deadlineNanos) {
        return uploadWithClient(localFile, objectKey, deadlineNanos, true);
    }

    private String uploadWithClient(Path localFile, String objectKey, long deadlineNanos, boolean dedicated) {
        if (localFile == null || !Files.isRegularFile(localFile)) {
            throw IsupException.failed("OSS上传", null, null, null,
                    "本地文件不存在: " + localFile);
        }
        IsupProperties.Oss oss = properties.getOss();
        if (!isConfigured()) {
            throw IsupException.failed("OSS上传", null, null, null,
                    "OSS 未配置：需要 endpoint / bucket / access-key-id / access-key-secret");
        }
        String bucket = oss.getBucket().trim();
        String endpoint = oss.getEndpoint().trim();
        OSS uploadClient = null;
        try {
            int remaining = (int) Math.min(Integer.MAX_VALUE, RecordingDeadline.remainingMillis(deadlineNanos));
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("OSS上传被中断");
            long size = Files.size(localFile);
            if (size <= 0) throw new java.io.IOException("上传文件为空");
            uploadClient = dedicated ? createClient(clientConfiguration(oss, remaining)) : client();
            RecordingDeadline.check(deadlineNanos);
            uploadClient.putObject(bucket, objectKey, localFile.toFile());
            RecordingDeadline.check(deadlineNanos);
            String url = buildPublicUrl(bucket, endpoint, objectKey, oss.getStaticDomain());
            log.info("OSS 上传成功：bucket={}, key={}, size={}, url={}", bucket, objectKey, size, url);
            return url;
        } catch (OSSException e) {
            throw IsupException.failed("OSS上传", null, null, null,
                    "requestId=" + e.getRequestId() + ", code=" + e.getErrorCode() + ", " + e.getErrorMessage());
        } catch (Exception e) {
            throw IsupException.wrap("OSS上传", null, null, null, e);
        } finally {
            if (dedicated && uploadClient != null) uploadClient.shutdown();
        }
    }

    private OSS client() {
        synchronized (clientLock) {
            if (client == null) {
                IsupProperties.Oss oss = properties.getOss();
                client = createClient(clientConfiguration(oss, oss.getRequestTimeoutMs()));
            }
            return client;
        }
    }

    private OSS createClient(ClientBuilderConfiguration configuration) {
        IsupProperties.Oss oss = properties.getOss();
        return clientFactory.create(oss.getEndpoint().trim(), oss.getAccessKeyId().trim(),
                oss.getAccessKeySecret().trim(), configuration);
    }

    static ClientBuilderConfiguration clientConfiguration(IsupProperties.Oss oss, int remainingMillis) {
        int budget = Math.max(1, remainingMillis);
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setConnectionTimeout(Math.min(oss.getConnectionTimeoutMs(), budget));
        configuration.setSocketTimeout(Math.min(oss.getSocketTimeoutMs(), budget));
        configuration.setConnectionRequestTimeout(Math.min(oss.getConnectionRequestTimeoutMs(), budget));
        configuration.setRequestTimeout(Math.min(oss.getRequestTimeoutMs(), budget));
        configuration.setRequestTimeoutEnabled(true);
        configuration.setMaxErrorRetry(0);
        return configuration;
    }

    @PreDestroy
    @Override
    public void close() {
        synchronized (clientLock) {
            if (client != null) {
                client.shutdown();
                client = null;
            }
        }
    }

    @FunctionalInterface
    interface ClientFactory {
        OSS create(String endpoint, String accessKeyId, String accessKeySecret, ClientBuilderConfiguration configuration);
    }

    public static String buildObjectKey(String prefix, String id, int channel, LocalDateTime time) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("OSS 对象 key 缺少记录 id");
        }
        if (time == null) {
            throw new IllegalArgumentException("OSS 对象 key 缺少时间");
        }
        CameraChannel.fromCode(channel);
        String safeId = id.replaceAll("[^A-Za-z0-9_-]", "_");
        String datePath = DATE_PATH.format(time);
        String base = prefix == null ? "" : prefix.trim();
        if (!base.isEmpty() && !base.endsWith("/")) {
            base = base + "/";
        }
        return base + datePath + "/" + safeId + "_ch" + channel + ".mp4";
    }

    public static String buildPublicUrl(String bucket, String endpoint, String key) {
        return buildPublicUrl(bucket, endpoint, key, null);
    }

    public static String buildPublicUrl(String bucket, String endpoint, String key, String staticDomain) {
        if (!isPresent(key)) {
            throw new IllegalArgumentException("OSS key 不能为空");
        }
        String objectKey = key.startsWith("/") ? key.substring(1) : key;
        if (isPresent(staticDomain)) {
            String domain = staticDomain.trim();
            if (domain.startsWith("https://")) {
                domain = domain.substring("https://".length());
            } else if (domain.startsWith("http://")) {
                domain = domain.substring("http://".length());
            }
            while (domain.endsWith("/")) {
                domain = domain.substring(0, domain.length() - 1);
            }
            return "https://" + domain + "/" + objectKey;
        }
        if (!isPresent(bucket) || !isPresent(endpoint)) {
            throw new IllegalArgumentException("OSS bucket / endpoint / key 不能为空");
        }
        String host = endpoint.trim();
        if (host.startsWith("https://")) {
            host = host.substring("https://".length());
        } else if (host.startsWith("http://")) {
            host = host.substring("http://".length());
        }
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        return "https://" + bucket.trim() + "." + host + "/" + objectKey;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
