package com.github.lisama569.versionedconfigreleasestorejava.web;

import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigReleaseSystem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Web 层装配：默认使用纯内存系统；通过属性 {@code app.journal-file} 指定日志文件路径后，
 * 使用文件型日志以获得跨重启持久化。
 */
@Configuration
public class WebConfig {

    @Bean
    public ConfigReleaseSystem configReleaseSystem(
            @Value("${app.journal-file:}") String journalFile) {
        if (journalFile == null || journalFile.isBlank()) {
            return ConfigReleaseSystem.inMemory();
        }
        return ConfigReleaseSystem.fileBacked(Path.of(journalFile));
    }
}
