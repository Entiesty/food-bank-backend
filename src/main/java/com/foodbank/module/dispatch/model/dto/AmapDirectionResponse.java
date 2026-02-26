package com.foodbank.module.dispatch.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 高德地图骑行路径规划 API 响应映射 (JDK 21 Record 语法)
 * @JsonIgnoreProperties(ignoreUnknown = true) 忽略多余的字段，只要我们关心的
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapDirectionResponse(
        String status,
        String info,
        @JsonProperty("route") RouteData route
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RouteData(List<Path> paths) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Path(
            @JsonProperty("distance") Long distance, // 骑行距离，单位：米
            @JsonProperty("duration") Long duration  // 预计耗时，单位：秒
    ) {}

    /**
     * 便捷方法：判断 API 调用是否成功
     */
    public boolean isSuccess() {
        return "1".equals(status);
    }
}