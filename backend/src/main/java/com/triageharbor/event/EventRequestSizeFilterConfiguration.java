package com.triageharbor.event;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class EventRequestSizeFilterConfiguration {

    @Bean
    FilterRegistrationBean<EventRequestSizeFilter> eventRequestSizeFilter() {
        FilterRegistrationBean<EventRequestSizeFilter> registration =
                new FilterRegistrationBean<>(new EventRequestSizeFilter());

        registration.addUrlPatterns("/events");
        registration.setOrder(
                GlobalIngestRateLimitConfiguration.GLOBAL_FILTER_ORDER + 1);

        return registration;
    }
}
