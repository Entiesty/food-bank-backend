package com.foodbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.freemarker.FreeMarkerAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// ⚠️ 关键补丁：排除 Freemarker 自动装配，防止和代码生成器冲突
@EnableScheduling // 🚨 激活定时任务大心脏！
@SpringBootApplication(exclude = {FreeMarkerAutoConfiguration.class})
public class FoodBankBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FoodBankBackendApplication.class, args);
    }
}