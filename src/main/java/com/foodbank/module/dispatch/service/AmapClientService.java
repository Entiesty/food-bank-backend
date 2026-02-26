package com.foodbank.module.dispatch.service;

import com.foodbank.common.exception.BusinessException;
import com.foodbank.module.dispatch.model.dto.AmapDirectionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 高德开放平台 Web 服务 API 客户端
 */
@Slf4j
@Service
public class AmapClientService {

    @Value("${amap.key}")
    private String amapKey;

    @Value("${amap.url}")
    private String amapUrl;

    // 注入最新的 RestClient (底层默认使用 JDK 内部的 HttpClient，配合虚拟线程性能极佳)
    private final RestClient restClient = RestClient.create();

    /**
     * 计算两点之间的骑行距离和时间
     *
     * @param originLonLat 起点经纬度 (如受赠方位置), 格式: "经度,纬度"
     * @param destLonLat   终点经纬度 (如食物银行据点), 格式: "经度,纬度"
     * @return 包含距离(米)和时间(秒)的 Path 对象
     */
    public AmapDirectionResponse.Path getRidingDistance(String originLonLat, String destLonLat) {
        log.debug("正在请求高德骑行路径规划：起点 [{}], 终点 [{}]", originLonLat, destLonLat);

        AmapDirectionResponse response = restClient.get()
                // 链式构建 URI 及其参数，极具可读性
                .uri(amapUrl + "?key={key}&origin={origin}&destination={destination}",
                        amapKey, originLonLat, destLonLat)
                .retrieve()
                // 直接将响应体反序列化为我们定义的 Record 类
                .body(AmapDirectionResponse.class);

        if (response == null || !response.isSuccess()) {
            log.error("高德 API 调用失败，返回信息：{}", response != null ? response.info() : "无响应");
            throw new BusinessException("地图服务暂时不可用，请稍后重试");
        }

        if (response.data() == null || response.data().paths() == null || response.data().paths().isEmpty()) {
            throw new BusinessException("无法计算出可行的骑行路线");
        }

        // 提取距离和时间信息
        AmapDirectionResponse.Path path = response.data().paths().get(0);
        log.debug("测算成功：距离 {} 米, 预计耗时 {} 秒", path.distance(), path.duration());

        return path;
    }
}