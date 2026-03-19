package com.foodbank.module.resource.goods.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodbank.common.api.Result;
import com.foodbank.common.utils.UserContext;
import com.foodbank.module.resource.goods.entity.Goods;
import com.foodbank.module.resource.goods.model.dto.DonateDTO;
import com.foodbank.module.resource.goods.model.dto.GoodsAdjustDTO;
import com.foodbank.module.resource.goods.model.vo.MerchantGoodsVO;
import com.foodbank.module.resource.goods.service.IGoodsService;
import com.foodbank.module.trade.order.entity.DispatchOrder;
import com.foodbank.module.trade.order.service.IDispatchOrderService;
import com.foodbank.module.resource.station.entity.Station;
import com.foodbank.module.resource.station.service.IStationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "Goods Controller", description = "救灾物资与库存管理")
@RestController
@RequestMapping("/resource/goods")
public class GoodsController {

    @Autowired
    private IGoodsService goodsService;
    @Autowired
    private IDispatchOrderService orderService;
    @Autowired
    private IStationService stationService;

    @Operation(summary = "1. 爱心商家捐赠物资入库")
    @PostMapping("/donate")
    public Result<String> donateGoods(@Validated @RequestBody DonateDTO dto) {
        Byte role = UserContext.getUserRole();
        if (role == null || (role != 2 && role != 4)) return Result.failed("权限不足");

        // 🚨 修复：将大段业务逻辑全权交还给 Service 层处理！
        goodsService.donateGoods(dto);

        return Result.success("感谢您的捐赠！系统已自动评估物资属性并接入调度大盘。");
    }

    // ... 下方其他的 5 个接口保持你的原样完全不变 ...
    @Operation(summary = "2. 分页查询据点可用库存")
    @GetMapping("/list")
    public Result<Page<Goods>> getGoodsList(@RequestParam(required = false) Long stationId, @RequestParam(required = false) String category, @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "10") int pageSize) {
        LambdaQueryWrapper<Goods> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Goods::getStatus, 2);
        if (stationId != null) queryWrapper.eq(Goods::getCurrentStationId, stationId);
        if (category != null) queryWrapper.eq(Goods::getCategory, category);
        return Result.success(goodsService.page(new Page<>(pageNum, pageSize), queryWrapper));
    }

    @Operation(summary = "3. 商家获取自己的捐赠记录")
    @GetMapping("/merchant/page")
    public Result<Page<MerchantGoodsVO>> getMerchantGoodsPage(@RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "10") int pageSize, @RequestParam(required = false) String goodsName, @RequestParam(required = false) Byte status) {
        return Result.success(goodsService.getMerchantGoodsPage(pageNum, pageSize, goodsName, status, UserContext.getUserId()));
    }

    @Operation(summary = "4. 商家撤销捐赠")
    @DeleteMapping("/revoke/{goodsId}")
    public Result<Void> revokeGoods(@PathVariable Long goodsId) {
        goodsService.revokeGoods(goodsId, UserContext.getUserId());
        return Result.success(null, "撤销成功，该物资已从调度大盘中移除");
    }

    @Operation(summary = "5. 商家开始自行配送")
    @PutMapping("/start-self-delivery/{goodsId}")
    public Result<Void> startSelfDelivery(@PathVariable Long goodsId) {
        goodsService.startSelfDelivery(goodsId, UserContext.getUserId());
        return Result.success(null, "物资已锁定！请注意交通安全，到达后请确认送达。");
    }

    @Operation(summary = "6. 商家确认已送达驿站")
    @PutMapping("/finish-self-delivery/{goodsId}")
    public Result<Void> finishSelfDelivery(@PathVariable Long goodsId) {
        goodsService.finishSelfDelivery(goodsId, UserContext.getUserId());
        return Result.success(null, "物资已成功入库，感谢您的亲力亲为！");
    }

    @Operation(summary = "查询指定驿站大仓台账", description = "获取当前入库可调度的物资列表")
    @GetMapping("/station/{stationId}")
    public Result<List<Goods>> getStationGoods(@PathVariable Long stationId) {
        return Result.success(goodsService.getStationGoods(stationId));
    }

    @Operation(summary = "手工校准库存", description = "网格员线下手工入库或损耗报废")
    @PostMapping("/adjust")
    public Result<String> adjustGoodsStock(@RequestBody GoodsAdjustDTO dto) {
        goodsService.adjustGoodsStock(dto);
        return Result.success("库存校准及平账成功");
    }
}