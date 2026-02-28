package com.foodbank.module.dispatch.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodbank.common.exception.BusinessException;
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

import java.util.List;

/**
 * 调度系统大心脏：后台自动化撮配引擎
 */
@Slf4j
@Component
public class DispatchJob {

    @Autowired
    private IDispatchOrderService orderService;

    @Autowired
    private DispatchEngineServiceImpl dispatchOrderService;

    // 🚨 新增注入 GoodsService 用于扣减库存
    @Autowired
    private IGoodsService goodsService;

    /**
     * 每隔 5 秒执行一次扫描
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional(rollbackFor = Exception.class)
    public void executeMatchEngine() {
        // 1. 扫描所有状态为 0 (待匹配) 的需求订单
        List<DispatchOrder> pendingDispatchOrders = orderService.list(new LambdaQueryWrapper<DispatchOrder>()
                .eq(DispatchOrder::getStatus, 0)
                .eq(DispatchOrder::getOrderType, 2));

        if (pendingDispatchOrders.isEmpty()) {
            return;
        }

        log.info("⚙️ [调度引擎] 发现 {} 个待匹配紧急求助单，开始多因子撮合...", pendingDispatchOrders.size());

        for (DispatchOrder dispatchOrder : pendingDispatchOrders) {
            try {
                // 2. 调用核心算法选出最优解
                List<DispatchCandidateVO> bestCandidates = dispatchOrderService.smartMatchStations(dispatchOrder);

                if (bestCandidates != null && !bestCandidates.isEmpty()) {
                    // 取 Top 1 (得分最高的最优解)
                    DispatchCandidateVO winner = bestCandidates.get(0);

                    // 🚨 核心防线：利用 MySQL 行锁安全扣减库存 (预扣减 1 件)
                    boolean deductSuccess = goodsService.deductStockSafe(winner.getGoods().getGoodsId(), 1);

                    if (!deductSuccess) {
                        log.warn("⚠️ [匹配轮空] 订单:{} | 物资:{} 库存不足或瞬间已被抢占，等待下一轮调度",
                                dispatchOrder.getOrderSn(), winner.getGoods().getGoodsName());
                        continue; // 如果扣减失败（比如瞬间被别的线程抢空），直接跳过本订单，下一轮会重新匹配别的据点
                    }

                    // 3. 扣减成功后，将最优解回写到订单中，并将状态改为 1 (调度中)
                    dispatchOrder.setGoodsId(winner.getGoods().getGoodsId());
                    dispatchOrder.setSourceId(winner.getStation().getStationId());
                    dispatchOrder.setStatus((byte) 1);

                    boolean updated = orderService.updateById(dispatchOrder);
                    if (updated) {
                        log.info("✅ [匹配成功] 订单:{} | 最优据点:{} | 选定物资:{} | 综合得分:{}",
                                dispatchOrder.getOrderSn(),
                                winner.getStation().getStationName(),
                                winner.getGoods().getGoodsName(),
                                String.format("%.4f", winner.getFinalScore()));
                    }
                }
            } catch (BusinessException be) {
                log.warn("⚠️ [匹配轮空] 订单:{} 原因:{}", dispatchOrder.getOrderSn(), be.getMessage());
            } catch (Exception e) {
                log.error("❌ [匹配异常] 订单:{} 发生未知错误: ", dispatchOrder.getOrderSn(), e);
            }
        }
    }
}