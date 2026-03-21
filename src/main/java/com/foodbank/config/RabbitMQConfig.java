package com.foodbank.config;

import org.springframework.amqp.core.*;
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
}