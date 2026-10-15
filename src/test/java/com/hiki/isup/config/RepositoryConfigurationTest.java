package com.hiki.isup.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"dev", "dev-test", "prod"})
    void profileCredentialsMustComeFromEnvironmentWithoutEmbeddedDefaults(String profile) {
        Properties config = profile(profile);
        for (String key : List.of("isup.api-key", "isup.ehome-key", "isup.device-auth.password",
                "isup.oss.access-key-id", "isup.oss.access-key-secret", "spring.datasource.password")) {
            String value = config.getProperty(key);
            assertNotNull(value, profile + ": missing " + key);
            assertTrue(value.matches("\\$\\{[A-Z][A-Z0-9_]*:?}"),
                    profile + ": " + key + " must use an environment variable with no secret default");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "dev-test", "prod"})
    void profilesKeepVideoCopyAndDisableAudio(String profile) {
        Properties config = profile(profile);
        for (String key : List.of("isup.ffmpeg.codec-args", "isup.ffmpeg.mp4-codec-args")) {
            String value = config.getProperty(key);
            assertNotNull(value, profile + ": missing " + key);
            assertTrue(value.contains("-c:v copy"), profile + ": video must remain stream copy");
            assertTrue(value.matches(".*(?:^|\\s)-an(?:\\s|$).*"), profile + ": audio must be disabled");
            assertFalse(value.contains("-c:a"), profile + ": audio encoder must not be configured");
        }
    }

    private Properties profile(String profile) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("src/main/resources/application-" + profile + ".yml")));
        return yaml.getObject();
    }
}
