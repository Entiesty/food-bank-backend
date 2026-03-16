package com.foodbank.module.dispatch.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import com.foodbank.module.dispatch.service.impl.DispatchEngineServiceImpl;
import com.foodbank.module.resource.goods.service.IGoodsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 调度系统大心脏：后台自动化撮配引擎 & 异常监控雷达
 */
@Slf4j
@Component
public class DispatchJob {

    @Autowired
    private IDispatchOrderService orderService;

    @Autowired
    private DispatchEngineServiceImpl dispatchOrderService;

    @Autowired
    private IGoodsService goodsService;

    /**
     * 1. 自动撮合引擎：每隔 5 秒执行一次扫描
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional(rollbackFor = Exception.class)
    public void executeMatchEngine() {
        // 🚨 这里加了 isNull 拦截，防止重复扫描已被接管的订单
        List<DispatchOrder> pendingDispatchOrders = orderService.list(new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, 0)
                .eq(DispatchOrder::getOrderType, 2)
                .isNull(DispatchOrder::getSourceId));

        if (pendingDispatchOrders.isEmpty()) return;

        for (DispatchOrder dispatchOrder : pendingDispatchOrders) {
            try {
                List<DispatchCandidateVO> bestCandidates = dispatchOrderService.smartMatchStations(dispatchOrder);
                if (bestCandidates != null && !bestCandidates.isEmpty()) {
                    DispatchCandidateVO winner = bestCandidates.get(0);
                    boolean deductSuccess = goodsService.deductStockSafe(winner.getGoods().getGoodsId(), 1);
                    if (!deductSuccess) continue;

                    dispatchOrder.setGoodsId(winner.getGoods().getGoodsId());
                    dispatchOrder.setSourceId(winner.getStation().getStationId());

                    // 🚨 核心修复：用真实的物资名称和数量，彻底覆盖掉原始的“急需盒饭”描述！
                    dispatchOrder.setGoodsName(winner.getGoods().getGoodsName());
                    dispatchOrder.setGoodsCount(1);

                    orderService.updateById(dispatchOrder);
                }
            } catch (Exception e) {
                log.error("❌ [匹配异常] 订单:{} 发生未知错误: ", dispatchOrder.getOrderSn(), e);
            }
        }
    }

    /**
     * 🚨 2. 异常监控雷达 (全新架构)：每分钟第0秒扫描一次，抓出滞留单！
     */
    @Scheduled(cron = "0 * * * * ?")
    public void monitorExceptionOrders() {
        // 只查还没匹配出去的求助单
        List<DispatchOrder> pendingOrders = orderService.list(new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, 0)
                .eq(DispatchOrder::getOrderType, 2));

        if (pendingOrders.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();

        for (DispatchOrder order : pendingOrders) {
            if (order.getCreateTime() == null) continue;

            long minutes = Duration.between(order.getCreateTime(), now).toMinutes();

            // 核心逻辑：去全局物资库查查，老人家要的东西到底还有没有库存？
            long stockCount = goodsService.count(new LambdaQueryWrapper<Goods>()
                    .eq(Goods::getCategory, order.getRequiredCategory())
                    .eq(Goods::getStatus, 2));

            String currentReason = null;

            // 🚨 如果全城都没货了，0分钟直接触发红色警报！
            if (stockCount == 0) {
                currentReason = "全城据点均无 [" + order.getRequiredCategory() + "] 库存";
            }
            // ⚠️ 如果有货，但卡了超过 3 分钟还没志愿者接，说明运力不足
            else if (minutes >= 3) {
                currentReason = "滞留超过3分钟，周边可能暂无活跃志愿者响应";
            }

            if (currentReason != null) {
                // 如果是新警报，写库并通知大屏！
                if (!currentReason.equals(order.getExceptionReason())) {
                    order.setExceptionReason(currentReason);
                    orderService.updateById(order);
                    log.warn("🚨 [异常雷达触发] 订单 {} 被拦截，原因: {}", order.getOrderSn(), currentReason);
                }
            } else {
                // ✨ 自动自愈：如果有货了且有人接了，自动清空异常！
                if (order.getExceptionReason() != null) {
                    orderService.update(new LambdaUpdateWrapper<DispatchOrder>()
                            .eq(DispatchOrder::getOrderId, order.getOrderId())
                            .set(DispatchOrder::getExceptionReason, null));
                    log.info("✨ [系统自愈] 订单 {} 恢复正常，警报解除！", order.getOrderSn());
                }
            }
        }
    }
}