package com.foodbank.module.trade.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.resource.goods.service.IGoodsService;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.service.IStationService;
import com.foodbank.module.system.user.entity.CreditLog;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.trade.task.mapper.DeliveryTaskMapper;
import com.foodbank.module.system.user.service.ICreditLogService;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import com.foodbank.module.trade.task.model.vo.MyTaskVO;
import com.foodbank.module.trade.task.service.IDeliveryTaskService;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Lazy;

@Slf4j
@Service
public class DeliveryTaskServiceImpl extends ServiceImpl<DeliveryTaskMapper, DeliveryTask> implements IDeliveryTaskService {

    @Autowired
    @Lazy
    private IDispatchOrderService orderService;
    @Autowired
    private IUserService userService;
    @Autowired
    private ICreditLogService creditLogService;
    @Autowired
    private IStationService stationService;

    @Autowired
    private IGoodsService goodsService;

    // 🚨 新增：三段式状态机 - 确认取货节点
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmPickup(Long taskId) {
        // 1. 查出骑士的执行任务单
        DeliveryTask task = this.getById(taskId);
        if (task == null) {
            throw new BusinessException("护航任务不存在");
        }

        // 2. 状态机防越级校验
        if (task.getTaskStatus() != 1) {
            throw new BusinessException("状态异常：当前任务不处于【待取货】状态");
        }

        // 3. 扭转底层任务状态为 2 (已取货)
        task.setTaskStatus((byte) 2);
        this.updateById(task);

        // 4. 级联微调：更新全局订单表的异常/备注字段，让大屏监控更细腻
        DispatchOrder order = orderService.getById(task.getOrderId());
        if (order != null) {
            order.setExceptionReason("物资已离柜，骑士配送中");
            orderService.updateById(order);
        }
    }

    // 🚀 核心核销逻辑：带图片参数、乐观锁防线、平急分流与原生加分
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeTask(Long taskId, Long userId, String proofImage) {
        DeliveryTask deliveryTask = this.getById(taskId);
        if (deliveryTask == null) throw new BusinessException("未找到该配送任务");
        if (!deliveryTask.getVolunteerId().equals(userId)) throw new BusinessException("权限不足");
        if (deliveryTask.getTaskStatus() == 3) throw new BusinessException("该任务已经核销，请勿重复点击");

        // 1. 保存现场核销照片
        if (proofImage != null && !proofImage.isEmpty()) deliveryTask.setProofImage(proofImage);
        deliveryTask.setTaskStatus((byte) 3);
        deliveryTask.setCompleteTime(LocalDateTime.now());

        // 🛡️ 架构师防线 1：拦截乐观锁静默失败！
        boolean updateTaskSuccess = this.updateById(deliveryTask);
        if (!updateTaskSuccess) {
            throw new BusinessException("🚨 任务状态已被其他终端修改，核销终止！");
        }

        // 3. 扭转订单状态与物资状态
        DispatchOrder dispatchOrder = orderService.getById(deliveryTask.getOrderId());
        if (dispatchOrder != null) {
            dispatchOrder.setStatus((byte) 2);
            orderService.updateById(dispatchOrder);

            if (dispatchOrder.getGoodsId() != null) {
                com.foodbank.module.resource.goods.entity.Goods goods = goodsService.getById(dispatchOrder.getGoodsId());
                if (goods != null) {
                    // 🛡️ 架构师防线 2：平急两用物资终点分流！
                    if (dispatchOrder.getOrderType() == 1) {
                        // 【平时态】入库驿站
                        goods.setStatus((byte) 2); // 2-已入库
                        goods.setCurrentStationId(dispatchOrder.getDestId());
                        log.info("📦 平时调度完成：物资 [{}] 已正式入库至驿站 ID:[{}]", goods.getGoodsName(), dispatchOrder.getDestId());
                    } else if (dispatchOrder.getOrderType() == 2) {
                        // 【急时态】直达灾民，物资彻底消耗
                        goods.setStatus((byte) 3); // 3-已发完/消耗
                        goods.setCurrentStationId(null);
                        log.info("🔥 紧急调度完成：物资 [{}] 已直接送达受赠方，物资生命周期结束！", goods.getGoodsName());
                    }
                    goodsService.updateById(goods);
                }
            }
        }

        // 4. 为志愿者发放信誉分
        rewardVolunteerCredit(userId, deliveryTask.getOrderId());
    }

    private void rewardVolunteerCredit(Long userId, Long orderId) {
        int rewardPoints = 10;
        User user = userService.getById(userId);
        if (user != null && user.getRole() != null && user.getRole() == 3) {
            userService.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<User>()
                    .eq(User::getUserId, userId)
                    .setSql("credit_score = credit_score + " + rewardPoints));

            CreditLog creditLog = new CreditLog();
            creditLog.setUserId(userId);
            creditLog.setOrderId(orderId);
            creditLog.setChangeValue(rewardPoints);
            creditLog.setReason("完成订单送达，发放护航信誉分");
            creditLogService.save(creditLog);
        }
    }

    @Override
    public Page<MyTaskVO> getMyTasksPage(Long volunteerId, Byte status, int pageNum, int pageSize) {
        LambdaQueryWrapper<DeliveryTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DeliveryTask::getVolunteerId, volunteerId);

        // 🚨 修复状态查询：将 1(待取货) 和 2(已取货) 统一视为“正在护送”
        if (status != null) {
            if (status == 1) {
                queryWrapper.in(DeliveryTask::getTaskStatus, java.util.Arrays.asList(1, 2));
            } else {
                queryWrapper.eq(DeliveryTask::getTaskStatus, status);
            }
        }

        queryWrapper.orderByDesc(DeliveryTask::getAcceptTime);

        Page<DeliveryTask> taskPage = this.page(new Page<>(pageNum, pageSize), queryWrapper);

        List<MyTaskVO> voList = taskPage.getRecords().stream().map(task -> {
            DispatchOrder order = orderService.getById(task.getOrderId());

            String sourceName = "未知起点";
            String sourceAddress = "位置待确认";
            BigDecimal sourceLon = null;
            BigDecimal sourceLat = null;

            String targetName = "未知终点";
            String targetAddress = "位置待确认";
            BigDecimal targetLon = null;
            BigDecimal targetLat = null;

            if (order != null) {
                targetLon = order.getTargetLon();
                targetLat = order.getTargetLat();

                if (order.getOrderType() != null && order.getOrderType() == 1) {
                    User merchant = userService.getById(order.getSourceId());
                    if (merchant != null) {
                        sourceName = merchant.getUsername() + " (爱心商铺)";
                        sourceAddress = "联系电话: " + merchant.getPhone();
                        sourceLon = merchant.getCurrentLon();
                        sourceLat = merchant.getCurrentLat();
                    }

                    Station station = stationService.getById(order.getDestId());
                    if (station != null) {
                        targetName = station.getStationName();
                        targetAddress = station.getAddress();
                        targetLon = station.getLongitude();
                        targetLat = station.getLatitude();
                    }
                } else {
                    Long sId = order.getSourceId();
                    if (sId != null && sId < 0) {
                        User merchant = userService.getById(-sId);
                        if (merchant != null) {
                            sourceName = merchant.getUsername() + " (爱心商铺直发)";
                            sourceAddress = "联系电话: " + merchant.getPhone();
                            sourceLon = merchant.getCurrentLon();
                            sourceLat = merchant.getCurrentLat();
                        }
                    } else if (sId != null) {
                        Station station = stationService.getById(sId);
                        if (station != null) {
                            sourceName = station.getStationName();
                            sourceAddress = station.getAddress();
                            sourceLon = station.getLongitude();
                            sourceLat = station.getLatitude();
                        }
                    }

                    User recipient = userService.getById(order.getDestId());
                    if (recipient != null) {
                        targetName = recipient.getUsername() + " (求助市民)";
                        targetAddress = "联系电话: " + recipient.getPhone();
                        if (targetLon == null) targetLon = recipient.getCurrentLon();
                        if (targetLat == null) targetLat = recipient.getCurrentLat();
                    }
                }
            }

            return MyTaskVO.builder()
                    .taskId(task.getTaskId())
                    .taskStatus(task.getTaskStatus())
                    .acceptTime(task.getAcceptTime())
                    .orderId(task.getOrderId())
                    .orderSn(order != null ? order.getOrderSn() : null)
                    .goodsName(order != null ? order.getGoodsName() : null)
                    .goodsCount(order != null ? order.getGoodsCount() : null)
                    .urgencyLevel(order != null ? order.getUrgencyLevel() : null)
                    .requiredCategory(order != null ? order.getRequiredCategory() : "未知")
                    .sourceName(sourceName)
                    .sourceAddress(sourceAddress)
                    .sourceLon(sourceLon)
                    .sourceLat(sourceLat)
                    .targetName(targetName)
                    .targetAddress(targetAddress)
                    .targetLon(targetLon)
                    .targetLat(targetLat)
                    .build();
        }).collect(Collectors.toList());

        Page<MyTaskVO> resultPage = new Page<>(pageNum, pageSize, taskPage.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }
}