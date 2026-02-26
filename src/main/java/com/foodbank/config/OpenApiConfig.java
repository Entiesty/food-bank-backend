package com.foodbank.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("社区“食物银行”及应急物资调度系统 - API接口文档")
                        .version("0.0.1-SNAPSHOT")
                        .description("基于 LBS 与多因子加权决策的应急物资调度系统后端接口")
                        .contact(new Contact().name("Entiesty").email("boogiepop1221@gmail.com")));
    }
}