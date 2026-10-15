package com.hiki.isup.controller;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.service.MediaUrlSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MediaControllerTest {

    private static final String SESSION_ID = "123e4567-e89b-12d3-a456-426614174000";

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private MediaUrlSigner signer;

    @BeforeEach
    void setUp() throws Exception {
        IsupProperties properties = new IsupProperties();
        properties.setApiKey("test-api-key");
        properties.setMediaDir(tempDir.toString());
        properties.setMediaUrlTtlSeconds(7200);
        signer = new MediaUrlSigner(properties);
        mockMvc = standaloneSetup(new MediaController(properties, signer))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        Path dir = tempDir.resolve("preview").resolve(SESSION_ID);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.m3u8"), "#EXTM3U\nseg_00001.ts\n");
        Files.write(dir.resolve("seg_00001.ts"), new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("合法签名可读取播放列表与分片，并支持 HEAD")
    void servesSignedHlsFiles() throws Exception {
        String playlist = signer.sign("preview", SESSION_ID);
        String segment = playlist.replace("index.m3u8", "seg_00001.ts");

        mockMvc.perform(get(playlist))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentType("application/vnd.apple.mpegurl"));
        mockMvc.perform(get(segment))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp2t"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
        mockMvc.perform(head(playlist))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", Files.size(
                        tempDir.resolve("preview").resolve(SESSION_ID).resolve("index.m3u8"))));
    }

    @Test
    @DisplayName("旧裸地址、篡改签名和非法文件名均不可访问")
    void rejectsUnsignedTamperedAndUnsafePaths() throws Exception {
        String signed = signer.sign("preview", SESSION_ID);
        String tampered = signed.replace("/preview/", "/playback/");

        mockMvc.perform(get("/media/preview/" + SESSION_ID + "/index.m3u8"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(tampered)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(signed.replace("index.m3u8", "secret.txt")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("播放列表尚未生成时保留404，生成后同一签名可读取")
    void missingPlaylistRemainsNotFoundUntilReady() throws Exception {
        String playlist = signer.sign("playback", SESSION_ID);
        mockMvc.perform(get(playlist)).andExpect(status().isNotFound());
        mockMvc.perform(head(playlist)).andExpect(status().isNotFound());

        Path directory = tempDir.resolve("playback").resolve(SESSION_ID);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("index.m3u8"), "#EXTM3U\n");
        mockMvc.perform(get(playlist)).andExpect(status().isOk());
    }
}
