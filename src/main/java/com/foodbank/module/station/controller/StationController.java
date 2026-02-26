package com.foodbank.module.station.controller;

import com.foodbank.common.api.Result;
import com.foodbank.module.station.entity.Station;
import com.foodbank.module.station.service.IStationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Station Controller", description = "物资据点管理接口")
@RestController
@RequestMapping("/station") // 前端代理后的实际请求路径
public class StationController {

    @Autowired
    private IStationService stationService;

    @Operation(summary = "获取所有可用据点", description = "供前端地图页面渲染Marker使用")
    @GetMapping("/list")
    public Result<List<Station>> getStationList() {
        // 调用 Service 层获取数据
        List<Station> list = stationService.list();

        // 使用我们封装的全局响应体包裹数据
        return Result.success(list);
    }
}