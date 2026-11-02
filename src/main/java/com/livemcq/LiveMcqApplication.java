package com.livemcq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LiveMcqApplication {
    public static void main(String[] args) {
        SpringApplication.run(LiveMcqApplication.class, args);
    }
}
