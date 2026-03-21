package com.foodbank.module.trade.task.consumer;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.foodbank.config.RabbitMQConfig;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.trade.task.service.IDeliveryTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@Slf4j
public class OrderTaskConsumer {

    @Autowired
    private IDispatchOrderService orderService;

    @Autowired
    private IDeliveryTaskService taskService;

    @RabbitListener(queues = RabbitMQConfig.GRAB_ORDER_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void processGrabOrder(Map<String, Object> message) {
        try {
            Long orderId = Long.valueOf(message.get("orderId").toString());
            Long volunteerId = Long.valueOf(message.get("volunteerId").toString());

            // 1. 乐观锁兜底扣减：这是你原本写得很棒的代码，放在这里做最终的一致性保证
            boolean isGrabbed = orderService.update(
                    new LambdaUpdateWrapper<DispatchOrder>()
                            .eq(DispatchOrder::getOrderId, orderId)
                            .eq(DispatchOrder::getStatus, 0)
                            .set(DispatchOrder::getStatus, 1)
            );

            if (!isGrabbed) {
                log.warn("⚠️ MQ消费被丢弃：订单 {} 已非待接单状态（可能已被消费）", orderId);
                return;
            }

            // 2. 生成骑士履约任务
            DeliveryTask deliveryTask = new DeliveryTask();
            deliveryTask.setOrderId(orderId);
            deliveryTask.setVolunteerId(volunteerId);
            deliveryTask.setTaskStatus((byte) 1);
            deliveryTask.setVersion(0);
            taskService.save(deliveryTask);

            log.info("✅ MQ异步削峰落库完成：单号 {} 成功指派给骑士 {}", orderId, volunteerId);

            // 3. 通知前端大屏刷新
            DispatchOrder order = orderService.getById(orderId);
            if (order != null) {
                try {
                    String wsMsg = String.format("{\"type\":\"ORDER_TAKEN\", \"orderSn\":\"%s\"}", order.getOrderSn());
                    com.foodbank.module.common.controller.websocket.WebSocketServer.broadcast(wsMsg);
                } catch (Exception ignore) {}
            }

        } catch (Exception e) {
            log.error("❌ 消费抢单消息失败，数据：{}", message, e);
        }
    }
}