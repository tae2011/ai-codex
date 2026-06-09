package com.example.springaidemo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "app.ai")
public class AiProviderProperties {

    private String defaultPlatform = "aliyun";

    private Map<String, Provider> providers = new LinkedHashMap<>();

    @Data
    public static class Provider {

        private String baseUrl;

        private String apiKey;

        private String defaultModelType = "chat";

        private Double temperature = 0.8;

        private Map<String, String> models = new LinkedHashMap<>();
    }
}
