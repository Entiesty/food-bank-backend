package com.foodbank.module.dispatch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.dispatch.entity.Order;
import com.foodbank.module.dispatch.entity.Task;
import com.foodbank.module.dispatch.model.dto.AmapDirectionResponse;
import com.foodbank.module.dispatch.model.dto.DispatchReqDTO;
import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import com.foodbank.module.dispatch.service.AmapClientService;
import com.foodbank.module.dispatch.service.IOrderService;
import com.foodbank.module.dispatch.service.ITaskService;
import com.foodbank.module.dispatch.strategy.MultiFactorDispatchStrategy;
import com.foodbank.module.goods.entity.Goods;
import com.foodbank.module.goods.service.IGoodsService;
import com.foodbank.module.station.entity.Station;
import com.foodbank.module.station.service.IStationService;
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
public class DispatchOrderServiceImpl {

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
    private IOrderService orderService;
    @Autowired
    private ITaskService taskService;

    // ================= 核心业务方法 =================

    /**
     * 核心 1：一键智能匹配最优派发据点 (之前的代码，保持不变)
     */
    public List<DispatchCandidateVO> smartMatchStations(DispatchReqDTO reqDTO) {
        log.info("接收到智能派单请求，坐标:[{},{}], 物资ID:{}, 紧急度:{}",
                reqDTO.getLongitude(), reqDTO.getLatitude(), reqDTO.getGoodsId(), reqDTO.getUrgency());

        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults =
                stationService.searchNearbyStations(reqDTO.getLongitude(), reqDTO.getLatitude(), 5.0);

        if (geoResults == null || geoResults.getContent().isEmpty()) {
            throw new BusinessException("附近 5 公里内暂无可用食物银行据点");
        }

        List<DispatchCandidateVO> candidates = new ArrayList<>();
        String originLonLat = reqDTO.getLongitude() + "," + reqDTO.getLatitude();

        for (var result : geoResults.getContent()) {
            Long stationId = Long.parseLong(result.getContent().getName());
            Goods goods = goodsService.getOne(new LambdaQueryWrapper<Goods>()
                    .eq(Goods::getCurrentStationId, stationId)
                    .eq(Goods::getGoodsId, reqDTO.getGoodsId())
                    .eq(Goods::getStatus, 2));

            int currentStock = (goods != null && goods.getStock() != null) ? goods.getStock() : 0;
            if (currentStock <= 0) continue;

            Station station = stationService.getById(stationId);
            if (station == null) continue;

            String destLonLat = station.getLongitude() + "," + station.getLatitude();
            try {
                AmapDirectionResponse.Path path = amapClientService.getRidingDistance(originLonLat, destLonLat);
                candidates.add(DispatchCandidateVO.builder()
                        .station(station)
                        .distance(path.distance())
                        .duration(path.duration())
                        .currentStock(currentStock)
                        .build());
            } catch (Exception e) {
                log.error("高德路径规划异常，据点ID: {} 暂不参与本次调度。详细报错：", stationId, e);
            }
        }

        if (candidates.isEmpty()) {
            throw new BusinessException("附近的据点均无库存或无法规划到达路线");
        }
        return dispatchStrategy.calculateAndRank(candidates, reqDTO.getUrgency());
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
                new LambdaUpdateWrapper<Order>()
                        .eq(Order::getOrderId, orderId)
                        .eq(Order::getStatus, 0)
                        .set(Order::getStatus, 1)
        );

        if (!isGrabbed) {
            log.warn("抢单失败：订单 [{}] 状态已变更或不存在，竞争者 [{}]", orderId, volunteerId);
            throw new BusinessException("晚了一小步，该任务已有志愿者领取了。感谢你的热心，去看看其他任务吧！");
        }

        // 防线 2：唯一索引兜底
        try {
            Task task = new Task();
            task.setOrderId(orderId);
            task.setVolunteerId(volunteerId);
            // 🚨 修复2：强制转换为 byte 类型，匹配数据库映射
            task.setTaskStatus((byte) 1);
            task.setVersion(0);

            taskService.save(task);
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

        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new BusinessException("找不到对应的派送任务");
        }
        if (task.getTaskStatus() != 1) {
            throw new BusinessException("当前任务状态不支持取货操作，请勿重复点击");
        }

        // 🚨 修复3：强制转换为 byte 类型
        task.setTaskStatus((byte) 2);

        boolean success = taskService.updateById(task);
        if (!success) {
            log.warn("乐观锁拦截：任务 [{}] 状态已被修改，拦截重复操作", taskId);
            throw new BusinessException("操作冲突，请刷新页面获取最新状态");
        }
        log.info("任务 [{}] 状态已更新为：已取货", taskId);
    }
}