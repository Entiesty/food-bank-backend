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
import org.springframework.transaction.annotation.Transactional;

import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.trade.task.mapper.DeliveryTaskMapper;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.mapper.GoodsMapper;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.order.mapper.DispatchOrderMapper;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

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
            wrapper.ne(User::getRole, (byte) 4);
        }

        if (isVerified != null) {
            wrapper.eq(User::getIsVerified, isVerified);
        } else {
            wrapper.eq(User::getIsVerified, (byte) 0);
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
            user.setStatus((byte) 1);
        } else {
            user.setIsVerified((byte) 0);
            user.setIdentityProofUrl(null);
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

        if (user.getRole() == 3) {
            vo.setCreditScore(user.getCreditScore());
            long completedTasks = deliveryTaskMapper.selectCount(new LambdaQueryWrapper<DeliveryTask>()
                    .eq(DeliveryTask::getVolunteerId, userId)
                    .eq(DeliveryTask::getTaskStatus, (byte) 3));

            vo.setTotalDeliveredOrders((int) completedTasks);
            BigDecimal mileage = new BigDecimal(completedTasks * 2.5).setScale(2, BigDecimal.ROUND_HALF_UP);
            vo.setRunningMileage(mileage);
        } else if (user.getRole() == 2) {
            long totalGoods = goodsMapper.selectCount(new LambdaQueryWrapper<Goods>()
                    .eq(Goods::getMerchantId, userId));

            vo.setTotalDonatedGoods((int) totalGoods);
            vo.setTotalHelpCount((int) totalGoods * 3 + 12);
        } else if (user.getRole() == 1) {
            vo.setUserTag(user.getUserTag());
            long received = dispatchOrderMapper.selectCount(new LambdaQueryWrapper<DispatchOrder>()
                    .eq(DispatchOrder::getDestId, userId)
                    .eq(DispatchOrder::getOrderType, (byte) 2)
                    .eq(DispatchOrder::getStatus, (byte) 2));

            vo.setTotalReceivedTimes((int) received);
        }

        return vo;
    }

    // 🚨🚨🚨 这里就是之前漏掉的核心：强制清退与三维熔断逻辑！
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void evictUser(Long userId) {
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException("该用户不存在");
        }
        if (user.getRole() == 4) {
            throw new BusinessException("安全阻断：无法清退管理员账号");
        }

        // 1. 逻辑封禁账号
        user.setStatus((byte) 0);
        user.setIsVerified((byte) 0);
        this.updateById(user);

        // 2. 冻结该商家名下尚未被接单的物资
        if (user.getRole() == 2) {
            goodsMapper.delete(new LambdaQueryWrapper<Goods>()
                    .eq(Goods::getMerchantId, userId)
                    .eq(Goods::getStatus, (byte) 0)
            );
        }

        // 3. 熔断该账号关联的在途订单
        List<DispatchOrder> activeOrders = dispatchOrderMapper.selectList(new LambdaQueryWrapper<DispatchOrder>()
                .in(DispatchOrder::getStatus, Arrays.asList((byte) 0, (byte) 1))
                .and(w -> w.eq(DispatchOrder::getSourceId, userId).or().eq(DispatchOrder::getDestId, userId))
        );

        for (DispatchOrder order : activeOrders) {
            order.setStatus((byte) 3); // 3 为已取消
            dispatchOrderMapper.updateById(order);
        }
    }
}