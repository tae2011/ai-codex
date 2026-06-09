package com.example.springaidemo;

import com.example.springaidemo.config.AiProviderProperties;
import com.example.springaidemo.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties({AiProviderProperties.class, RagProperties.class})
@SpringBootApplication
public class SpringaidemoApplication {

    /**
     * Spring Boot 应用入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SpringaidemoApplication.class, args);
    }
}
