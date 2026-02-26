package com.foodbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.freemarker.FreeMarkerAutoConfiguration;

// ⚠️ 关键补丁：排除 Freemarker 自动装配，防止和代码生成器冲突
@SpringBootApplication(exclude = {FreeMarkerAutoConfiguration.class})
public class FoodBankBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FoodBankBackendApplication.class, args);
    }
}