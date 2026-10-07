package com.github.lisama569.versionedconfigreleasestorejava.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开启 Spring 定时调度，驱动生效时间窗的到期扫描。 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
