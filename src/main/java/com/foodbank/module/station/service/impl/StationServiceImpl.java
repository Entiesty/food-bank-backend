package com.foodbank.module.station.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodbank.module.station.mapper.StationMapper;
import com.foodbank.module.station.entity.Station;
import com.foodbank.module.station.service.IStationService;
import org.springframework.stereotype.Service;

/**
 * 物资据点服务实现类
 */
@Service
public class StationServiceImpl extends ServiceImpl<StationMapper, Station> implements IStationService {
}