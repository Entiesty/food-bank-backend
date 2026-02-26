package com.foodbank.module.dispatch.controller;

import com.foodbank.common.api.Result;
import com.foodbank.module.dispatch.model.dto.DispatchReqDTO;
import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import com.foodbank.module.dispatch.service.impl.DispatchOrderServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Dispatch Controller", description = "核心智能调度指令接口")
@RestController
@RequestMapping("/dispatch")
public class DispatchController {

    @Autowired
    private DispatchOrderServiceImpl dispatchOrderService;

    @Operation(summary = "发起智能派单计算", description = "基于LBS与多因子的派单引擎，返回评分排序后的候选据点")
    @PostMapping("/smart-match")
    public Result<List<DispatchCandidateVO>> smartMatch(@Validated @RequestBody DispatchReqDTO reqDTO) {
        // 调用我们刚刚写好的流水线服务
        List<DispatchCandidateVO> bestStations = dispatchOrderService.smartMatchStations(reqDTO);
        return Result.success(bestStations);
    }
}