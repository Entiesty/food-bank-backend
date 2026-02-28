package com.foodbank.module.dispatch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.dispatch.model.dto.AmapDirectionResponse;
import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import com.foodbank.module.dispatch.amap.AmapClientService;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import com.foodbank.module.trade.task.service.IDeliveryTaskService;
import com.foodbank.module.dispatch.strategy.MultiFactorDispatchStrategy;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.service.IGoodsService;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.service.IStationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DispatchEngineServiceImpl {

    // ================= 依赖注入区 =================
    @Autowired
    private IStationService stationService;
    @Autowired
    private IGoodsService goodsService;
    @Autowired
    private AmapClientService amapClientService;
    @Autowired
    private MultiFactorDispatchStrategy dispatchStrategy;

    // 🚨 修复1：补充注入 order 和 task 服务
    @Autowired
    private IDispatchOrderService orderService;
    @Autowired
    private IDeliveryTaskService taskService;

    // ================= 核心业务方法 =================

    /**
     * 核心 1：一键智能匹配最优派发据点 (已适配宽泛类别匹配)
     */
    public List<DispatchCandidateVO> smartMatchStations(DispatchOrder dispatchOrder) {
        log.info("📡 启动智能派单匹配，坐标:[{},{}], 需求物资大类:{}, 紧急度:{}",
                dispatchOrder.getTargetLon(), dispatchOrder.getTargetLat(), dispatchOrder.getRequiredCategory(), dispatchOrder.getUrgencyLevel());

        // 1. Redis Geo 空间初筛 (方圆 5 公里)
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults =
                stationService.searchNearbyStations(dispatchOrder.getTargetLon().doubleValue(), dispatchOrder.getTargetLat().doubleValue(), 5.0);

        if (geoResults == null || geoResults.getContent().isEmpty()) {
            throw new BusinessException("附近 5 公里内暂无可用食物银行据点");
        }

        List<DispatchCandidateVO> candidates = new ArrayList<>();
        String originLonLat = dispatchOrder.getTargetLon() + "," + dispatchOrder.getTargetLat();

        // 2. 遍历附近据点，进行物资复筛与高德路径规划
        for (var result : geoResults.getContent()) {
            Long stationId = Long.parseLong(result.getContent().getName());

            // 🚨 核心修复：按【类别】而非具体 ID 查找，且必须是已入库(2)的物资
            Goods goods = goodsService.getOne(new LambdaQueryWrapper<Goods>()
                    .eq(Goods::getCurrentStationId, stationId)
                    .eq(Goods::getCategory, dispatchOrder.getRequiredCategory())
                    .eq(Goods::getStatus, 2)
                    .last("LIMIT 1")); // 只要该据点有这个类别的物资就行

            if (goods == null || goods.getStock() <= 0) continue;

            Station station = stationService.getById(stationId);
            if (station == null) continue;

            String destLonLat = station.getLongitude() + "," + station.getLatitude();
            try {
                // 调用高德 API 获取真实骑行距离与耗时
                AmapDirectionResponse.Path path = amapClientService.getRidingDistance(originLonLat, destLonLat);
                candidates.add(DispatchCandidateVO.builder()
                        .station(station)
                        .goods(goods) // 🚨 别忘了把查到的具体物资塞进去，后续算法要用它的过期时间！
                        .distance(path.distance())
                        .duration(path.duration())
                        .currentStock(goods.getStock())
                        .build());
            } catch (Exception e) {
                log.error("高德路径规划异常，据点ID: {} 暂不参与本次调度。详细报错：{}", stationId, e.getMessage());
            }
        }

        if (candidates.isEmpty()) {
            throw new BusinessException("附近的据点均无对应类别的库存物资");
        }

        // 3. 丢给核心加权算法算分并排序
        return dispatchStrategy.calculateAndRank(candidates, dispatchOrder.getUrgencyLevel());
    }

    /**
     * 核心 2：高并发志愿者抢单 (防止超卖)
     */
    @Transactional(rollbackFor = Exception.class)
    public void grabOrder(Long orderId, Long volunteerId) {
        if (orderId == null || volunteerId == null) {
            throw new BusinessException("订单ID或志愿者ID不能为空");
        }
        log.info("志愿者 [{}] 正在尝试抢夺订单 [{}]", volunteerId, orderId);

        // 防线 1：状态机 CAS 乐观锁
        boolean isGrabbed = orderService.update(
                new LambdaUpdateWrapper<DispatchOrder>()
                        .eq(DispatchOrder::getOrderId, orderId)
                        .eq(DispatchOrder::getStatus, 0)
                        .set(DispatchOrder::getStatus, 1)
        );

        if (!isGrabbed) {
            log.warn("抢单失败：订单 [{}] 状态已变更或不存在，竞争者 [{}]", orderId, volunteerId);
            throw new BusinessException("晚了一小步，该任务已有志愿者领取了。感谢你的热心，去看看其他任务吧！");
        }

        // 防线 2：唯一索引兜底
        try {
            DeliveryTask deliveryTask = new DeliveryTask();
            deliveryTask.setOrderId(orderId);
            deliveryTask.setVolunteerId(volunteerId);
            // 🚨 修复2：强制转换为 byte 类型，匹配数据库映射
            deliveryTask.setTaskStatus((byte) 1);
            deliveryTask.setVersion(0);

            taskService.save(deliveryTask);
            log.info("抢单成功！已为订单 [{}] 生成执行任务，负责人: [{}]", orderId, volunteerId);

        } catch (Exception e) {
            log.error("插入任务表异常，触发唯一键回滚，订单号: {}", orderId, e);
            throw new BusinessException("系统繁忙，生成派送任务失败，请重试");
        }
    }

    /**
     * 核心 3：志愿者点击“已取货” (测试 @Version 乐观锁)
     */
    public void pickUpGoods(Long taskId) {
        if (taskId == null) {
            throw new BusinessException("任务ID不能为空");
        }

        DeliveryTask deliveryTask = taskService.getById(taskId);
        if (deliveryTask == null) {
            throw new BusinessException("找不到对应的派送任务");
        }
        if (deliveryTask.getTaskStatus() != 1) {
            throw new BusinessException("当前任务状态不支持取货操作，请勿重复点击");
        }

        // 🚨 修复3：强制转换为 byte 类型
        deliveryTask.setTaskStatus((byte) 2);

        boolean success = taskService.updateById(deliveryTask);
        if (!success) {
            log.warn("乐观锁拦截：任务 [{}] 状态已被修改，拦截重复操作", taskId);
            throw new BusinessException("操作冲突，请刷新页面获取最新状态");
        }
        log.info("任务 [{}] 状态已更新为：已取货", taskId);
    }
}