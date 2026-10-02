package com.github.huchangjun118.markdowncontentpipelinejava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wires content pipeline configuration properties. */
@Configuration
@EnableConfigurationProperties(ContentPipelineProperties.class)
public class PipelineConfig {
}
