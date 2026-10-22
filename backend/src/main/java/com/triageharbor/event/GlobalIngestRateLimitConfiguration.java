package com.triageharbor.event;

import io.github.bucket4j.TimeMeter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GlobalIngestRateLimitProperties.class)
class GlobalIngestRateLimitConfiguration {

    @Bean
    TimeMeter ingestTimeMeter() {
        return TimeMeter.SYSTEM_NANOTIME;
    }
}
