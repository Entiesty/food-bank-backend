package com.foodbank.module.dispatch.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.dispatch.entity.Order;
import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import com.foodbank.module.dispatch.service.IOrderService;
import com.foodbank.module.dispatch.service.impl.DispatchOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 调度系统大心脏：后台自动化撮配引擎
 */
@Slf4j
@Component
public class DispatchJob {

    @Autowired
    private IOrderService orderService;

    @Autowired
    private DispatchOrderServiceImpl dispatchOrderService;

    /**
     * 每隔 5 秒执行一次扫描 (cron 表达式或 fixedDelay 都可以)
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional(rollbackFor = Exception.class)
    public void executeMatchEngine() {
        // 1. 扫描所有状态为 0 (待匹配) 的需求订单
        List<Order> pendingOrders = orderService.list(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, 0)
                .eq(Order::getOrderType, 2)); // 确保是需求单

        if (pendingOrders.isEmpty()) {
            return; // 默默潜伏，没有需求就不打扰系统
        }

        log.info("⚙️ [调度引擎] 发现 {} 个待匹配紧急求助单，开始多因子撮合...", pendingOrders.size());

        for (Order order : pendingOrders) {
            try {
                // 2. 调用核心算法选出最优解
                List<DispatchCandidateVO> bestCandidates = dispatchOrderService.smartMatchStations(order);

                if (bestCandidates != null && !bestCandidates.isEmpty()) {
                    // 取 Top 1 (得分最高的最优解)
                    DispatchCandidateVO winner = bestCandidates.get(0);

                    // 3. 将最优解回写到订单中，并将状态改为 1 (调度中)
                    order.setGoodsId(winner.getGoods().getGoodsId());
                    order.setSourceId(winner.getStation().getStationId());
                    order.setStatus((byte) 1);

                    boolean updated = orderService.updateById(order);
                    if (updated) {
                        log.info("✅ [匹配成功] 订单:{} | 最优据点:{} | 选定物资:{} | 综合得分:{}",
                                order.getOrderSn(),
                                winner.getStation().getStationName(),
                                winner.getGoods().getGoodsName(),
                                String.format("%.4f", winner.getFinalScore()));
                    }
                }
            } catch (BusinessException be) {
                // 业务级异常（比如附近没货），只打印警告，不中断其他订单的匹配
                log.warn("⚠️ [匹配轮空] 订单:{} 原因:{}", order.getOrderSn(), be.getMessage());
            } catch (Exception e) {
                log.error("❌ [匹配异常] 订单:{} 发生未知错误: ", order.getOrderSn(), e);
            }
        }
    }
}