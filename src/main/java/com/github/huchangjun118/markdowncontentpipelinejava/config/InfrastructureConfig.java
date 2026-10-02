package com.github.huchangjun118.markdowncontentpipelinejava.config;

import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.version.VersionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Singletons for stateful infrastructure components. */
@Configuration
public class InfrastructureConfig {

    @Bean
    public BuildCache buildCache(ContentPipelineProperties properties) {
        return new BuildCache(properties.getCacheSize());
    }

    @Bean
    public VersionStore versionStore(ContentPipelineProperties properties) {
        return new VersionStore(properties.getMaxVersions());
    }
}
