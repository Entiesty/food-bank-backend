package com.foodbank.module.system.user.service.impl;

import com.foodbank.module.system.user.entity.CreditLog;
import com.foodbank.module.system.user.mapper.CreditLogMapper;
import com.foodbank.module.system.user.service.ICreditLogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 信誉分变动明细记录表 服务实现类
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Service
public class CreditLogServiceImpl extends ServiceImpl<CreditLogMapper, CreditLog> implements ICreditLogService {

}
