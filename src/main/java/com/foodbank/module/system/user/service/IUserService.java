package com.foodbank.module.system.user.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.model.vo.UserDashboardVO;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IUserService extends IService<User> {

    /**
     * 获取资质审核列表 (管理员视角)
     */
    Page<User> getAuditPage(int pageNum, int pageSize, Byte role, Byte isVerified);

    /**
     * 审批用户资质
     */
    boolean auditUser(Long userId, boolean isPass);

    /**
     * 获取千人千面角色成就看板数据
     */
    UserDashboardVO getUserDashboardStats(Long userId);

    /**
     * 🚨 新增：强制清退违规用户（执行逻辑封禁与业务熔断）
     */
    void evictUser(Long userId);
}