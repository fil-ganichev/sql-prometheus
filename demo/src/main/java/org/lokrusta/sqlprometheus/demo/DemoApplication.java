package org.lokrusta.sqlprometheus.demo;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    ActionExecutor actionExecutor(MeterRegistry meterRegistry) {
        return new ActionExecutor(meterRegistry);
    }
}
