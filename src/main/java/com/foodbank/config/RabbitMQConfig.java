package com.foodbank.config;

import org.springframework.amqp.core.*;
// 👇 必须引入下面这两个包
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    public static final String EXCHANGE_NAME = "dispatch.order.exchange";
    public static final String GRAB_ORDER_QUEUE = "dispatch.order.grab.queue";
    public static final String GRAB_ORDER_ROUTING_KEY = "order.grab";

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(EXCHANGE_NAME);
    }

    @Bean
    public Queue grabOrderQueue() {
        return new Queue(GRAB_ORDER_QUEUE, true);
    }

    @Bean
    public Binding grabOrderBinding(Queue grabOrderQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(grabOrderQueue).to(orderExchange).with(GRAB_ORDER_ROUTING_KEY);
    }

    // 👇👇👇 核心补丁：就是缺了下面这 4 行代码！👇👇👇
    // 加上它，Spring 才会把 Map 转成 JSON 格式传输，彻底解决死循环报错！
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}