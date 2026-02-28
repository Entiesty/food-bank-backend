package com.foodbank.module.dispatch.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "志愿者信誉分排行榜视图")
public class VolunteerRankVO {
    @Schema(description = "志愿者姓名")
    private String volunteerName;

    @Schema(description = "当前信誉分")
    private Integer creditScore;

    @Schema(description = "排名名次")
    private Integer rank;
}