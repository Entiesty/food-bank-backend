package com.foodbank.module.resource.station.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.common.api.Result;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.model.vo.StationRecommendVO;
import com.foodbank.module.resource.station.service.IStationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Station Controller", description = "物资据点管理接口")
@RestController
@RequestMapping("/resource/station")
public class StationController {

    @Autowired
    private IStationService stationService;

    @Operation(summary = "1. 新增社区据点")
    @PostMapping("/add")
    public Result<String> addStation(@RequestBody Station station) {
        Byte role = UserContext.getUserRole();
        if (role == null || role != 4) {
            return Result.failed("越权操作：仅限系统管理员执行");
        }
        stationService.addStationAndSyncGeo(station);
        return Result.success("据点设立成功！调度引擎已将其纳入雷达监控范围。");
    }

    @Operation(summary = "2. 分页获取可用据点")
    @GetMapping("/list")
    public Result<Page<Station>> getStationList(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(stationService.page(new Page<>(pageNum, pageSize)));
    }

    @Operation(summary = "3. 获取全量可用驿站列表")
    @GetMapping("/all")
    public Result<List<Station>> getAllStations() {
        List<Station> list = stationService.list(new LambdaQueryWrapper<Station>()
                .orderByDesc(Station::getStationId));
        return Result.success(list);
    }

    @Operation(summary = "4. 获取驿站列表(按真实物理距离排序)")
    @GetMapping("/recommend")
    public Result<List<StationRecommendVO>> getRecommendStations(
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) Double lat) {
        return Result.success(stationService.getRecommendStations(lon, lat));
    }
}