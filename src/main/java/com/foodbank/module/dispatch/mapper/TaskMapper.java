package com.foodbank.module.dispatch.mapper;

import com.foodbank.module.dispatch.entity.Task;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 志愿者配送执行任务表 Mapper 接口
 * </p>
 *
 * @author Entiesty
 * @since 2026-02-26
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {

}
