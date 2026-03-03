package com.foodbank.module.system.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.mapper.UserMapper;
import com.foodbank.module.system.user.model.vo.UserDashboardVO;
import com.foodbank.module.system.user.service.IUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// 引入跨模块的 Entity 和 Mapper (严格按你的目录树路径)
import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.trade.task.mapper.DeliveryTaskMapper;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.mapper.GoodsMapper;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.order.mapper.DispatchOrderMapper;

import java.math.BigDecimal;
import java.util.Arrays;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    // 注入跨模块 Mapper
    @Autowired
    private DeliveryTaskMapper deliveryTaskMapper;
    @Autowired
    private GoodsMapper goodsMapper;
    @Autowired
    private DispatchOrderMapper dispatchOrderMapper;

    @Override
    public Page<User> getAuditPage(int pageNum, int pageSize, Byte role, Byte isVerified) {
        Page<User> pageReq = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();

        wrapper.isNotNull(User::getIdentityProofUrl)
                .ne(User::getIdentityProofUrl, "");

        if (role != null) {
            wrapper.eq(User::getRole, role);
        } else {
            wrapper.ne(User::getRole, (byte) 4); // 默认排除管理员
        }

        if (isVerified != null) {
            wrapper.eq(User::getIsVerified, isVerified);
        } else {
            wrapper.eq(User::getIsVerified, (byte) 0); // 默认查未核实的
        }

        wrapper.orderByDesc(User::getCreateTime);
        return this.page(pageReq, wrapper);
    }

    @Override
    public boolean auditUser(Long userId, boolean isPass) {
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException("该用户不存在");
        }

        if (isPass) {
            user.setIsVerified((byte) 1);
            user.setStatus((byte) 1); // 审核通过解锁状态
        } else {
            user.setIsVerified((byte) 0);
            user.setIdentityProofUrl(null); // 驳回强制要求重新上传
        }
        return this.updateById(user);
    }

    @Override
    public UserDashboardVO getUserDashboardStats(Long userId) {
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        UserDashboardVO vo = new UserDashboardVO();
        vo.setAvatar(user.getAvatar());
        vo.setRole(user.getRole());
        vo.setUsername(user.getUsername());

        // 🚴 志愿者：查 delivery_task 表
        if (user.getRole() == 3) {
            vo.setCreditScore(user.getCreditScore());
            long completedTasks = deliveryTaskMapper.selectCount(new LambdaQueryWrapper<DeliveryTask>()
                    .eq(DeliveryTask::getVolunteerId, userId)
                    .eq(DeliveryTask::getTaskStatus, (byte) 3)); // 3: 已核销送达

            vo.setTotalDeliveredOrders((int) completedTasks);

            // 每单暂估为2.5公里有氧跑
            BigDecimal mileage = new BigDecimal(completedTasks * 2.5).setScale(2, BigDecimal.ROUND_HALF_UP);
            vo.setRunningMileage(mileage);
        }
        // 🏪 爱心商家：查 fb_goods 表
        else if (user.getRole() == 2) {
            long totalGoods = goodsMapper.selectCount(new LambdaQueryWrapper<Goods>()
                    .eq(Goods::getMerchantId, userId));

            vo.setTotalDonatedGoods((int) totalGoods);
            vo.setTotalHelpCount((int) totalGoods * 3 + 12); // 模拟转化系数
        }
        // 👴 受赠方：查 dispatch_order 表
        else if (user.getRole() == 1) {
            vo.setUserTag(user.getUserTag());
            long received = dispatchOrderMapper.selectCount(new LambdaQueryWrapper<DispatchOrder>()
                    .eq(DispatchOrder::getDestId, userId)
                    .eq(DispatchOrder::getOrderType, (byte) 2) // 需求单
                    .eq(DispatchOrder::getStatus, (byte) 2));  // 已送达

            vo.setTotalReceivedTimes((int) received);
        }

        return vo;
    }
}