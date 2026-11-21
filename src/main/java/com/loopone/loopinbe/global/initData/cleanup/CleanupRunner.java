package com.loopone.loopinbe.global.initData.cleanup;

import com.loopone.loopinbe.global.initData.cleanup.service.CleanupService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("cleanup")
public class CleanupRunner {
    @Bean
    public ApplicationRunner cleanupApplicationRunner(CleanupService cleanupService, ApplicationContext ctx) {
        return args -> {
            cleanupService.deleteTestData();
            SpringApplication.exit(ctx, () -> 0);
        };
    }
}
