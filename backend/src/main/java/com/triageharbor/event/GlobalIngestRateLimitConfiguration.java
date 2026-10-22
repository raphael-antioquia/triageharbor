package com.triageharbor.event;

import io.github.bucket4j.TimeMeter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GlobalIngestRateLimitProperties.class)
class GlobalIngestRateLimitConfiguration {

    static final int GLOBAL_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    TimeMeter ingestTimeMeter() {
        return TimeMeter.SYSTEM_NANOTIME;
    }

    @Bean
    FilterRegistrationBean<GlobalIngestRateLimitFilter>
            globalIngestRateLimitFilter(
                    GlobalIngestRateLimitProperties properties,
                    TimeMeter ingestTimeMeter) {

        FilterRegistrationBean<GlobalIngestRateLimitFilter> registration =
                new FilterRegistrationBean<>(
                        new GlobalIngestRateLimitFilter(
                                properties, ingestTimeMeter));

        registration.addUrlPatterns("/events");
        registration.setOrder(GLOBAL_FILTER_ORDER);

        return registration;
    }
}
