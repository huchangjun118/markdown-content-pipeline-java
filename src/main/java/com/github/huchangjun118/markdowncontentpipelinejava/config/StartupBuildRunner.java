package com.github.huchangjun118.markdowncontentpipelinejava.config;

import com.github.huchangjun118.markdowncontentpipelinejava.coordinator.BuildCoordinator;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Performs the initial full build once the context is ready, so reads always
 * have a committed version available. The build can be disabled with
 * {@code content.pipeline.build-on-startup=false} (useful for tests that want
 * to drive builds explicitly).
 */
@Component
@Order(0)
public class StartupBuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupBuildRunner.class);

    private final BuildCoordinator coordinator;
    private final ContentPipelineProperties properties;

    public StartupBuildRunner(BuildCoordinator coordinator, ContentPipelineProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isBuildOnStartup()) {
            log.info("startup build disabled via content.pipeline.build-on-startup=false");
            return;
        }
        BuildResult result = coordinator.fullRebuild("startup");
        if (result.success()) {
            log.info("startup full build published version {}", result.version());
        } else {
            log.warn("startup full build had fatal errors; no version published yet");
        }
    }
}
