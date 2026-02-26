package com.foodbank.module.dispatch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 调度策略动态权重配置
 * 自动映射 application.yml 中的 dispatch.strategy.weights
 */
@Data
@Component
@ConfigurationProperties(prefix = "dispatch.strategy.weights")
public class DispatchProperties {

    // 距离权重 (默认 0.8)
    private Double distance = 0.8;

    // 库存权重 (默认 0.1)
    private Double stock = 0.1;

    // 紧急度权重 (默认 0.1)
    private Double urgency = 0.1;
}