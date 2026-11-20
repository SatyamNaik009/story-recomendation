package com.storystylist.backend.photo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoProcessingPropertiesTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PhotoProcessingProperties.class)
    static class Config {
    }

    @Test
    void bindsSafeDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var settings = context.getBean(PhotoProcessingProperties.class);
            assertThat(settings.maxWidth()).isEqualTo(1568);
            assertThat(settings.maxHeight()).isEqualTo(1568);
            assertThat(settings.jpegQuality()).isEqualTo(0.85);
            assertThat(settings.outputFormat()).isEqualTo("jpeg");
            assertThat(settings.allowUpscaling()).isFalse();
            assertThat(settings.previewEnabled()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"max-width=0", "max-height=-1", "max-width=10001", "jpeg-quality=0",
            "jpeg-quality=1.1", "jpeg-quality=NaN", "output-format=png", "allow-upscaling=true"})
    void rejectsInvalidConfigurationAtStartup(String setting) {
        runner.withPropertyValues("story.photo.processing." + setting).run(context -> assertThat(context).hasFailed());
    }
}
