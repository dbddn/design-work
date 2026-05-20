package com.music.reco;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.music.reco.config")
@EnableScheduling
public class RecoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecoBackendApplication.class, args);
    }
}
