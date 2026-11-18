package com.cocos.challenge.shared.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI cocosOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Cocos Challenge API")
                .version("v1")
                .description("""
                        Portfolio, instrument search and order management API.
                        Errors follow RFC 9457 (application/problem+json) and include a stable `code` \
                        and the request `correlationId` (also returned in the `X-Correlation-Id` header)."""));
    }
}
