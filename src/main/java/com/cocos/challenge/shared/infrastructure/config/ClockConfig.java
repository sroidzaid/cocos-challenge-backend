package com.cocos.challenge.shared.infrastructure.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single source of time for the application. {@code orders.datetime} is a {@code TIMESTAMP} without time zone,
 * so the business zone is made explicit and configurable; tests can replace the bean with a fixed clock.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock(@Value("${app.time-zone}") ZoneId zoneId) {
        return Clock.system(zoneId);
    }
}
