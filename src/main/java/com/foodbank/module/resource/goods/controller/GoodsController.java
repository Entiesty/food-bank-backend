package com.foodbank.module.resource.goods.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.common.api.Result;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.model.dto.DonateDTO;
import com.foodbank.module.resource.goods.model.vo.MerchantGoodsVO;
import com.foodbank.module.resource.goods.service.IGoodsService;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Tag(name = "Goods Controller", description = "救灾物资与库存管理")
@RestController
@RequestMapping("/resource/goods")
public class GoodsController {

    @Autowired
    private IGoodsService goodsService;

    @Autowired
    private IDispatchOrderService orderService; // 🚨 顶部记得注入 OrderService

    @Operation(summary = "1. 爱心商家捐赠物资入库")
    @PostMapping("/donate")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> donateGoods(@Validated @RequestBody DonateDTO dto) {
        Long merchantId = UserContext.getUserId();
        Byte role = UserContext.getUserRole();
        if (role == null || (role != 2 && role != 4)) return Result.failed("权限不足");

        // 1. 保存物资表
        Goods goods = new Goods();
        BeanUtils.copyProperties(dto, goods);
        goods.setMerchantId(merchantId);
        goods.setStatus((byte) 0);
        goods.setCreateTime(LocalDateTime.now());
        goodsService.save(goods);

        // 2. 自动生成调度大屏 DON 单
        DispatchOrder autoOrder = new DispatchOrder();
        autoOrder.setOrderSn("DON-" + System.currentTimeMillis());
        autoOrder.setOrderType((byte) 1);
        autoOrder.setGoodsId(goods.getGoodsId());
        autoOrder.setRequiredCategory(goods.getCategory());
        autoOrder.setSourceId(merchantId);
        autoOrder.setDestId(dto.getCurrentStationId());
        autoOrder.setDeliveryMethod((byte) 1);
        autoOrder.setUrgencyLevel((byte) 5);
        autoOrder.setStatus((byte) 0);

        // 🚨🚨 核心补齐：把商家的名称和数量塞给订单
        autoOrder.setGoodsName(goods.getGoodsName());
        autoOrder.setGoodsCount(goods.getStock());

        autoOrder.setCreateTime(LocalDateTime.now());
        orderService.save(autoOrder);

        return Result.success("感谢您的捐赠！系统已自动生成运单并广播给全城骑士。");
    }

    @Operation(summary = "2. 分页查询据点可用库存", description = "按据点ID或物资类型过滤查询")
    @GetMapping("/list")
    public Result<Page<Goods>> getGoodsList(
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        LambdaQueryWrapper<Goods> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Goods::getStatus, 2); // 依然只查已入库可用的物资
        if (stationId != null) queryWrapper.eq(Goods::getCurrentStationId, stationId);
        if (category != null) queryWrapper.eq(Goods::getCategory, category);

        return Result.success(goodsService.page(new Page<>(pageNum, pageSize), queryWrapper));
    }

    @Operation(summary = "3. 商家获取自己的捐赠记录", description = "支持按物资名、状态过滤")
    @GetMapping("/merchant/page")
    public Result<Page<MerchantGoodsVO>> getMerchantGoodsPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String goodsName,
            @RequestParam(required = false) Byte status) {

        Long merchantId = UserContext.getUserId();
        return Result.success(goodsService.getMerchantGoodsPage(pageNum, pageSize, goodsName, status, merchantId));
    }

    @Operation(summary = "4. 商家撤销捐赠", description = "仅限处于待取货状态的物资")
    @DeleteMapping("/revoke/{goodsId}")
    public Result<Void> revokeGoods(@PathVariable Long goodsId) {
        Long merchantId = UserContext.getUserId();
        goodsService.revokeGoods(goodsId, merchantId);
        return Result.success(null, "撤销成功，该物资已从调度大盘中移除");
    }

    @Operation(summary = "5. 商家开始自行配送", description = "状态设为4，锁定物资防止骑手抢单")
    @PutMapping("/start-self-delivery/{goodsId}")
    public Result<Void> startSelfDelivery(@PathVariable Long goodsId) {
        Long merchantId = UserContext.getUserId();
        goodsService.startSelfDelivery(goodsId, merchantId);
        return Result.success(null, "物资已锁定！请注意交通安全，到达后请确认送达。");
    }

    @Operation(summary = "6. 商家确认已送达驿站", description = "状态设为2，正式入库可用")
    @PutMapping("/finish-self-delivery/{goodsId}")
    public Result<Void> finishSelfDelivery(@PathVariable Long goodsId) {
        Long merchantId = UserContext.getUserId();
        goodsService.finishSelfDelivery(goodsId, merchantId);
        return Result.success(null, "物资已成功入库，感谢您的亲力亲为！");
    }
}