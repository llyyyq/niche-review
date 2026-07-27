package com.hmdp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Keeps scheduled production jobs enabled by default while allowing isolated
 * integration tests to disable them explicitly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "hmdp.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
