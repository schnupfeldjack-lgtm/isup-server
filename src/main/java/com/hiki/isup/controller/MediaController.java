package com.hiki.isup.controller;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.service.MediaUrlSigner;
import com.hiki.isup.util.MediaPaths;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

/** 只读 HLS 媒体入口；所有文件必须携带会话范围内的短期签名。 */
@RestController
public class MediaController {

    private static final MediaType M3U8 = MediaType.parseMediaType("application/vnd.apple.mpegurl");
    private static final MediaType MPEG_TS = MediaType.parseMediaType("video/mp2t");

    private final IsupProperties properties;
    private final MediaUrlSigner signer;

    public MediaController(IsupProperties properties, MediaUrlSigner signer) {
        this.properties = properties;
        this.signer = signer;
    }

    @RequestMapping(value = "/media/{expires}/{signature}/{type}/{sessionId}/{fileName:.+}",
            method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Resource> media(@PathVariable String expires,
                                          @PathVariable String signature,
                                          @PathVariable String type,
                                          @PathVariable String sessionId,
                                          @PathVariable String fileName) {
        if (!signer.verify(expires, signature, type, sessionId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "媒体 URL 无效或已过期");
        }
        MediaType contentType = contentType(fileName);
        Path sessionDir = MediaPaths.mediaRoot(properties.getMediaDir())
                .resolve(type).resolve(sessionId).normalize();
        Path file = sessionDir.resolve(fileName).normalize();
        if (!file.startsWith(sessionDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "媒体文件路径非法");
        }
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体文件不存在");
        }
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(contentType)
                    .contentLength(Files.size(file))
                    .body(new FileSystemResource(file));
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取媒体文件失败", e);
        }
    }

    private static MediaType contentType(String fileName) {
        if ("index.m3u8".equals(fileName)) {
            return M3U8;
        }
        if (fileName != null && fileName.matches("^[A-Za-z0-9_-]+\\.ts$")) {
            return MPEG_TS;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只允许读取 HLS 播放列表和 TS 分片");
    }
}
