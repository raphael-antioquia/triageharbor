package com.triageharbor.event;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class EventIngestConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
