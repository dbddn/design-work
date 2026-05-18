package com.music.reco;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.music.reco.config")
public class RecoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecoBackendApplication.class, args);
    }
}
