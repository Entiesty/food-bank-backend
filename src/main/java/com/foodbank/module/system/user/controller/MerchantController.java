package com.foodbank.module.system.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodbank.common.api.Result;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.service.IGoodsService;
import com.foodbank.module.system.user.entity.User;
import com.foodbank.module.system.user.service.IUserService;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Tag(name = "Merchant Controller", description = "爱心商家CSR战报与荣誉体系")
@RestController
@RequestMapping("/merchant")
public class MerchantController {

    @Autowired
    private IUserService userService;
    @Autowired
    private IGoodsService goodsService;
    @Autowired
    private IDispatchOrderService orderService;

    @Operation(summary = "1. 获取当前商家的CSR战报数据")
    @GetMapping("/csr-report")
    public Result<Map<String, Object>> getCsrReport() {
        Long merchantId = UserContext.getUserId();
        User merchant = userService.getById(merchantId);

        // 统计该商家所有捐赠物资
        List<Goods> allGoods = goodsService.list(new LambdaQueryWrapper<Goods>()
                .eq(Goods::getMerchantId, merchantId));

        int totalDonations = allGoods.stream()
                .mapToInt(g -> g.getStock() != null ? g.getStock() : 0)
                .sum();

        BigDecimal totalValue = allGoods.stream()
                .map(g -> g.getEstimatedValue() != null ? g.getEstimatedValue() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 统计受助人数: 通过该商家的物资被配送到的受赠方数量
        Set<Long> servedUserIds = new HashSet<>();
        for (Goods g : allGoods) {
            List<DispatchOrder> orders = orderService.list(new LambdaQueryWrapper<DispatchOrder>()
                    .eq(DispatchOrder::getGoodsId, g.getGoodsId())
                    .eq(DispatchOrder::getOrderType, 2)
                    .ge(DispatchOrder::getStatus, 2));
            for (DispatchOrder o : orders) {
                if (o.getDestId() != null) {
                    servedUserIds.add(o.getDestId());
                }
            }
        }

        // 物资品类分布
        Map<String, Integer> categoryDistribution = allGoods.stream()
                .collect(Collectors.groupingBy(
                        g -> g.getCategory() != null ? g.getCategory() : "其他",
                        Collectors.summingInt(g -> g.getStock() != null ? g.getStock() : 0)
                ));

        int csrLevel = merchant.getCsrLevel() != null ? merchant.getCsrLevel() : 0;
        String csrLevelName = switch (csrLevel) {
            case 1 -> "铜牌爱心企业";
            case 2 -> "银牌爱心企业";
            case 3 -> "金牌爱心企业";
            default -> "爱心贡献者";
        };

        Map<String, Object> data = new HashMap<>();
        data.put("totalDonations", totalDonations);
        data.put("totalValue", totalValue);
        data.put("servedPeople", servedUserIds.size());
        data.put("csrLevel", csrLevel);
        data.put("csrLevelName", csrLevelName);
        data.put("categoryDistribution", categoryDistribution);
        data.put("merchantName", merchant.getUsername());
        return Result.success(data);
    }
}
