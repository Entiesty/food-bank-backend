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

    @Operation(summary = "2. 分页获取可用据点(带条件检索)")
    @GetMapping("/list")
    public Result<Page<Station>> getStationList(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String stationName,
            @RequestParam(required = false) Integer isEmergencyHub) {

        // 1. 构造条件包装器
        LambdaQueryWrapper<Station> queryWrapper = new LambdaQueryWrapper<>();

        // 2. 如果前端传了据点名称，则进行模糊查询 (LIKE)
        // 注意：如果你没引入 StringUtils，可以使用 stationName != null && !stationName.isEmpty()
        if (stationName != null && !stationName.trim().isEmpty()) {
            queryWrapper.like(Station::getStationName, stationName.trim());
        }

        // 3. 如果前端传了网点类型，则进行精确匹配 (EQ)
        if (isEmergencyHub != null) {
            queryWrapper.eq(Station::getIsEmergencyHub, isEmergencyHub);
        }

        // 4. 建议加一个按时间倒序排列，让新加的驿站排在最前面
        queryWrapper.orderByDesc(Station::getCreateTime);

        // 5. 执行分页并返回
        return Result.success(stationService.page(new Page<>(pageNum, pageSize), queryWrapper));
    }

    @Operation(summary = "4. 获取驿站列表(按真实物理距离排序)")
    @GetMapping("/recommend")
    public Result<List<StationRecommendVO>> getRecommendStations(
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) Double lat) {
        return Result.success(stationService.getRecommendStations(lon, lat));
    }

    @Operation(summary = "5. 更新据点配置 (包含LBS坐标同步)")
    @PutMapping("/update")
    public Result<String> updateStation(@RequestBody Station station) {
        Byte role = UserContext.getUserRole();
        if (role == null || role != 4) {
            return Result.failed("越权操作：仅限系统管理员执行");
        }
        if (station.getStationId() == null) {
            return Result.failed("参数错误：据点ID不能为空");
        }

        // 调用 Service 层更新 MySQL 数据并强制覆盖 Redis Geo 缓存
        stationService.updateStationAndSyncGeo(station);

        return Result.success("据点配置更新成功！调度引擎 LBS 缓存已同步。");
    }
}