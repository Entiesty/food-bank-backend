package com.foodbank.module.station.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.foodbank.module.station.entity.Station;

/**
 * 物资据点服务类接口
 */
public interface IStationService extends IService<Station> {
    // 可以在这里扩展复杂的业务接口，例如：获取附近 5 公里的据点
}