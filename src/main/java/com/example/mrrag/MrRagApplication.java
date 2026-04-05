package com.example.mrrag;

import com.example.mrrag.config.GraphCacheProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GraphCacheProperties.class)
public class MrRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(MrRagApplication.class, args);
    }
}
