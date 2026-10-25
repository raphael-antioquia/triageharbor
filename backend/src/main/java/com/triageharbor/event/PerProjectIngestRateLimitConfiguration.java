package com.triageharbor.event;

import io.github.bucket4j.TimeMeter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PerProjectIngestRateLimitProperties.class)
class PerProjectIngestRateLimitConfiguration {

    @Bean
    PerProjectIngestRateLimiter perProjectIngestRateLimiter(
            PerProjectIngestRateLimitProperties properties,
            TimeMeter ingestTimeMeter) {
        return new PerProjectIngestRateLimiter(properties, ingestTimeMeter);
    }
}
