package com.foodbank.module.system.service.impl;

import com.foodbank.module.system.entity.User;
import com.foodbank.module.system.mapper.UserMapper;
import com.foodbank.module.system.service.IUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 用户信息与信用体系表 服务实现类
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

}
