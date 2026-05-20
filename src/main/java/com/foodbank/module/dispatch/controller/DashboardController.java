package com.foodbank.module.dispatch.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.foodbank.common.api.Result;
import com.foodbank.module.dispatch.model.vo.CategoryStockVO;
import com.foodbank.module.dispatch.model.vo.VolunteerRankVO;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.service.IGoodsService;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.IUserService;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import com.foodbank.module.trade.task.entity.DeliveryTask;
import com.foodbank.module.trade.task.service.IDeliveryTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Dashboard Controller", description = "调度中心大屏数据统计接口")
@RestController
@RequestMapping("/dispatch/dashboard")
public class DashboardController {

    @Autowired
    private IDispatchOrderService orderService;
    @Autowired
    private IGoodsService goodsService;
    @Autowired
    private IUserService userService;
    @Autowired
    private IDeliveryTaskService taskService;

    @Operation(summary = "1. 获取今日核心指标", description = "返回今日新增求助数、今日完成派送数、全网总库存")
    @GetMapping("/base-metrics")
    public Result<Map<String, Object>> getBaseMetrics() {
        LocalDateTime startOfToday = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime endOfToday = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);

        // 指标 1：今日新增求助订单数
        long todayNewOrders = orderService.count(new LambdaQueryWrapper<DispatchOrder>()
                .ge(DispatchOrder::getCreateTime, startOfToday)
                .le(DispatchOrder::getCreateTime, endOfToday));

// 🚨 修正：指标 2：今日完成派送数 (直接查 DeliveryTask 表中今天核销的任务)
        long todayCompletedOrders = taskService.count(new LambdaQueryWrapper<DeliveryTask>()
                .eq(DeliveryTask::getTaskStatus, 3) // 3: 已完成
                .ge(DeliveryTask::getCompleteTime, startOfToday)
                .le(DeliveryTask::getCompleteTime, endOfToday));

        // 指标 3：全网可用物资总库存 (状态为 2:已入库)
        QueryWrapper<Goods> stockQuery = new QueryWrapper<>();
        stockQuery.select("IFNULL(SUM(stock), 0) as totalStock")
                .eq("status", 2)
                .isNotNull("current_station_id");  // 排除 P2P 直达物资，仅统计驿站库存
        Map<String, Object> stockResult = goodsService.getMap(stockQuery);
        long totalStock = stockResult != null ? Long.parseLong(stockResult.get("totalStock").toString()) : 0;

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("todayNewOrders", todayNewOrders);
        metrics.put("todayCompletedOrders", todayCompletedOrders);
        metrics.put("totalStock", totalStock);

        return Result.success(metrics);
    }

    @Operation(summary = "2. 获取物资分类占比 (供饼图使用)", description = "按类别分组聚合统计全城库存")
    @GetMapping("/category-stock")
    public Result<List<CategoryStockVO>> getCategoryStockDistribution() {
        // 🚀 技术亮点：利用 MyBatis-Plus 进行灵活的 Group By 聚合查询
        QueryWrapper<Goods> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("category as categoryName, SUM(stock) as totalStock")
                .eq("status", 2)
                .isNotNull("current_station_id")  // 排除 P2P 直达物资
                .groupBy("category")
                .having("SUM(stock) > 0");

        List<Map<String, Object>> mapList = goodsService.listMaps(queryWrapper);
        List<CategoryStockVO> resultList = new ArrayList<>();

        for (Map<String, Object> map : mapList) {
            CategoryStockVO vo = new CategoryStockVO();
            vo.setCategoryName((String) map.get("categoryName"));
            // 注意数据库聚合查出来的数字类型转换
            vo.setTotalStock(Integer.parseInt(map.get("totalStock").toString()));
            resultList.add(vo);
        }

        return Result.success(resultList);
    }

    @Operation(summary = "3. 获取志愿者信誉分排行榜 TOP 5", description = "用于大屏右侧光荣榜轮播展示")
    @GetMapping("/volunteer-rank")
    public Result<List<VolunteerRankVO>> getVolunteerRank() {
        // 查询角色为 3(志愿者) 的用户，按信誉分倒序排，取前 5 名
        List<User> topVolunteers = userService.list(new LambdaQueryWrapper<User>()
                .eq(User::getRole, 3)
                .orderByDesc(User::getCreditScore)
                .last("LIMIT 5"));

        List<VolunteerRankVO> rankList = new ArrayList<>();
        int rank = 1;
        for (User user : topVolunteers) {
            rankList.add(VolunteerRankVO.builder()
                    .volunteerName(user.getUsername())
                    .creditScore(user.getCreditScore() != null ? user.getCreditScore() : 0)
                    .rank(rank++)
                    .build());
        }

        return Result.success(rankList);
    }
}