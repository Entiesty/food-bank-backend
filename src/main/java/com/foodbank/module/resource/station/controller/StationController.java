package com.foodbank.module.resource.station.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.common.api.Result;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.service.IStationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Station Controller", description = "物资据点管理接口")
@RestController
@RequestMapping("/resource/station") // 🚨 调整为规范的资源域路径
public class StationController {

    @Autowired
    private IStationService stationService;

    @Operation(summary = "1. 新增社区据点", description = "管理员添加据点，系统自动同步其经纬度至 Redis Geo")
    @PostMapping("/add")
    public Result<String> addStation(@RequestBody Station station) {
        // 🚨 鉴权：只有管理员(Role=4)可以设立新据点
        Byte role = UserContext.getUserRole();
        if (role == null || role != 4) {
            return Result.failed("越权操作：仅限系统管理员执行");
        }

        stationService.addStationAndSyncGeo(station);
        return Result.success("据点设立成功！调度引擎已将其纳入雷达监控范围。");
    }

    @Operation(summary = "2. 分页获取可用据点", description = "供前端地图页面渲染Marker及后台列表管理使用")
    @GetMapping("/list")
    public Result<Page<Station>> getStationList(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        // 分页查询并返回
        Page<Station> pageResult = stationService.page(new Page<>(pageNum, pageSize));
        return Result.success(pageResult);
    }

    @Operation(summary = "3. 获取全量可用驿站列表", description = "无分页，专供前端下拉框或地图打点使用")
    @GetMapping("/all")
    public Result<List<Station>> getAllStations() {
        // 假设驿站没有被软删除，直接查全量
        List<Station> list = stationService.list(new LambdaQueryWrapper<Station>()
                .orderByDesc(Station::getStationId));
        return Result.success(list);
    }
}