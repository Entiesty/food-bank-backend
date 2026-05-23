package com.foodbank.module.dispatch.strategy;

import com.foodbank.module.dispatch.model.vo.DispatchCandidateVO;
import com.foodbank.module.system.config.entity.Config;
import com.foodbank.module.system.config.service.IConfigService;
import com.foodbank.module.trade.order.model.vo.AvailableOrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * 基于 SAW（Simple Additive Weighting，简单加权求和）算法的多因子调度决策策略。
 *
 * <h3>六维权重体系</h3>
 * 通过 {@link IConfigService} 从 {@link Config} 实体读取运行时权重配置，
 * 包含以下六个维度（各维度权重之和应为 1.0，由运营人员在后台动态调整）：
 * <ul>
 *   <li>{@code wDist}       — 距离权重：距离越近得分越高（反向归一化）</li>
 *   <li>{@code wUrgency}    — 紧急度权重：订单紧急程度对得分的加成比例</li>
 *   <li>{@code wCredit}     — 信誉分权重：仅用于志愿者抢单大厅排序</li>
 *   <li>{@code wTag}        — 标签匹配权重：Jaccard 相似度加权项（由上游调用方计算后注入）</li>
 *   <li>{@code wExpiration} — 临期优先权重：越临近过期的物资得分越高，优先消耗</li>
 *   <li>{@code wStock}      — 库存充裕权重：库存越充裕的驿站得分越高</li>
 * </ul>
 *
 * <h3>归一化方式</h3>
 * 所有原始指标均采用 Min-Max 归一化，将异构量纲映射到 [0, 1] 区间后再加权求和，
 * 避免量纲差异（如距离 km 与库存件数）导致某单一指标主导结果。
 *
 * <h3>wTimeCoin 说明</h3>
 * 时间币（{@code wTimeCoin}）独立于六维体系之外，
 * 仅在 {@link #rankOrdersForVolunteer} 志愿者抢单路径中生效，
 * 用于奖励累计服务时长较长的志愿者。
 *
 * @author System Architect
 * @see MultiFactorDispatchStrategy#calculateAndRank(List, int)
 * @see MultiFactorDispatchStrategy#rankOrdersForVolunteer(List, Double, Double, int, int)
 */
@Slf4j
@Component
public class MultiFactorDispatchStrategy {

    @Autowired
    private IConfigService configService;

    /**
     * 对候选物资/驿站列表执行 Min-Max 归一化，并计算 SAW 综合得分，用于系统端自动指派。
     *
     * <h3>算法流程</h3>
     * <ol>
     *   <li>从 Config 读取运行时权重（{@code wDist}、{@code wUrgency}、{@code wStock}、{@code wExpiration}）</li>
     *   <li>遍历候选列表，分别提取距离、库存、过期时间的极值（max/min）</li>
     *   <li>对每个候选按 Min-Max 公式归一化：
     *       <ul>
     *         <li>距离：反向归一化 {@code (max - x) / range}，越近得分越高</li>
     *         <li>库存：正向归一化 {@code (x - min) / range}，越充裕得分越高</li>
     *         <li>过期：反向归一化 {@code (maxExpTime - x) / range}，越临期得分越高（促进消耗）</li>
     *       </ul>
     *   </li>
     *   <li>紧急度全局统一（非候选级别），不参与 Min-Max，直接以 {@code urgency / 10.0} 归一化</li>
     *   <li>紧急度 ≥ 8 且候选站点为应急枢纽时，附加 1.0 的 {@code emergencyBonus}，强化应急响应能力</li>
     *   <li>加权求和写入 {@link DispatchCandidateVO#setFinalScore}，按降序排列后返回</li>
     * </ol>
     *
     * <h3>注意事项</h3>
     * <ul>
     *   <li>当 {@code wStock} 或 {@code wExpiration} 在 Config 中未配置时，分别默认为 0.10</li>
     *   <li>极差为 0 时（所有候选值相同）分母强制取 1，避免除零，此时归一化结果统一为 0</li>
     * </ul>
     *
     * @param candidates   候选驿站及物资列表，不能为 {@code null}（空列表会直接返回）
     * @param orderUrgency 订单紧急度，取值范围 [1, 10]，值越大越紧急
     * @return 按 {@code finalScore} 降序排列后的候选列表（原地排序，同一对象引用）
     */
    public List<DispatchCandidateVO> calculateAndRank(List<DispatchCandidateVO> candidates, int orderUrgency) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        // 从数据库读取当前生效的运行时权重配置（支持热更新，无需重启）
        Config activeConfig = configService.getCurrentConfig();
        double wDist       = activeConfig.getWDist().doubleValue();
        double wUrgency    = activeConfig.getWUrgency().doubleValue();
        // wStock / wExpiration 为可选配置项，未设置时给予合理默认值
        double wStock      = activeConfig.getWStock()      != null ? activeConfig.getWStock().doubleValue()      : 0.10;
        double wExpiration = activeConfig.getWExpiration() != null ? activeConfig.getWExpiration().doubleValue() : 0.10;

        // ---- 第一步：提取各维度极值，用于后续 Min-Max 归一化 ----

        // 距离极值（单位：米）
        long maxDistance  = candidates.stream().mapToLong(DispatchCandidateVO::getDistance).max().orElse(1L);
        long minDistance  = candidates.stream().mapToLong(DispatchCandidateVO::getDistance).min().orElse(0L);
        // 极差为 0（所有候选等距）时取 1，防止分母为零
        long distanceRange = Math.max(maxDistance - minDistance, 1L);

        // 库存极值（单位：件）
        int maxStock  = candidates.stream().mapToInt(DispatchCandidateVO::getCurrentStock).max().orElse(1);
        int minStock  = candidates.stream().mapToInt(DispatchCandidateVO::getCurrentStock).min().orElse(0);
        int stockRange = Math.max(maxStock - minStock, 1);

        // 过期时间极值（转为 Unix 时间戳秒数，便于数值比较）
        long maxExpTime = candidates.stream()
                .mapToLong(c -> c.getGoods().getExpirationDate().toEpochSecond(ZoneOffset.UTC))
                .max().orElse(1L);
        long minExpTime = candidates.stream()
                .mapToLong(c -> c.getGoods().getExpirationDate().toEpochSecond(ZoneOffset.UTC))
                .min().orElse(0L);
        long expRange = Math.max(maxExpTime - minExpTime, 1L);

        // 紧急度归一化：全局统一值，所有候选共享同一紧急度上下文，不需要极值
        double normUrgency = orderUrgency / 10.0;

        // ---- 第二步：逐候选计算 SAW 综合得分 ----
        for (DispatchCandidateVO candidate : candidates) {

            // 距离：反向归一化——候选距离越近，normDistance 越接近 1.0
            // 公式：(max - x) / range
            double normDistance = (double) (maxDistance - candidate.getDistance()) / distanceRange;

            // 库存：正向归一化——库存越充裕，normStock 越接近 1.0
            // 公式：(x - min) / range
            double normStock = (double) (candidate.getCurrentStock() - minStock) / stockRange;

            // 过期时间：反向归一化——过期时间越早（越临期），normExpiration 越接近 1.0，优先消耗临期物资
            // 公式：(max - x) / range（maxExpTime 为最晚过期，对应 normExpiration 最低）
            long currentExpTime   = candidate.getGoods().getExpirationDate().toEpochSecond(ZoneOffset.UTC);
            double normExpiration = (double) (maxExpTime - currentExpTime) / expRange;

            // 应急枢纽加成：紧急度 ≥ 8 时，标记为应急枢纽的站点额外获得 1.0 的 bonus，
            // 与 normUrgency 叠加后乘以 wUrgency，大幅提升应急站点在高紧急场景下的优先级
            double emergencyBonus = (orderUrgency >= 8 && candidate.getStation().getIsEmergencyHub() == 1) ? 1.0 : 0.0;

            // SAW 加权求和：各归一化分项乘以对应权重后累加
            double finalScore = (normDistance   * wDist)
                    + (normStock      * wStock)
                    + (normExpiration * wExpiration)
                    + ((normUrgency + emergencyBonus) * wUrgency);

            candidate.setFinalScore(finalScore);
        }

        // ---- 第三步：按综合得分降序排列，得分最高者排在首位供调用方直接取用 ----
        candidates.sort(Comparator.comparing(DispatchCandidateVO::getFinalScore).reversed());
        return candidates;
    }

    /**
     * 志愿者抢单大厅个性化排序策略（{@code timeCoin} 默认为 0 的重载入口）。
     *
     * <p>等同于调用 {@link #rankOrdersForVolunteer(List, Double, Double, int, int)}
     * 并传入 {@code timeCoin = 0}，供未接入时间币体系的旧接口兼容调用。
     *
     * @param orders    待排序的可接单订单列表
     * @param volLon    志愿者当前经度
     * @param volLat    志愿者当前纬度
     * @param volCredit 志愿者信誉分，取值范围 [0, 150]
     * @see #rankOrdersForVolunteer(List, Double, Double, int, int)
     */
    public void rankOrdersForVolunteer(List<AvailableOrderVO> orders, Double volLon, Double volLat, int volCredit) {
        rankOrdersForVolunteer(orders, volLon, volLat, volCredit, 0);
    }

    /**
     * 志愿者抢单大厅个性化订单排序策略（完整版，含时间币维度）。
     *
     * <h3>算法目标</h3>
     * 实现「千人千面」的抢单大厅排序：相同的订单池，针对不同位置、信誉、
     * 服务时长的志愿者呈现不同的排序结果，优先将合适的订单推送给合适的人。
     *
     * <h3>四维评分模型</h3>
     * <ol>
     *   <li><b>接驾距离</b>（反向归一化）：志愿者离订单起点越近，接驾距离分越高，
     *       优先分配路程成本最低的订单</li>
     *   <li><b>订单紧急度</b>（正向归一化）：紧急度越高的订单得分越高，
     *       确保高优先级需求优先被领取</li>
     *   <li><b>信誉分赋能</b>（正向，上限 150 分）：高信誉骑手在同等条件下获得更高的基础分加成，
     *       激励长期高质量服务</li>
     *   <li><b>时间币赋能</b>（正向，上限 50 枚）：累计服务时长越多的志愿者优先级越高，
     *       形成正向激励飞轮</li>
     * </ol>
     *
     * <h3>距离降级处理</h3>
     * 当订单起点坐标缺失时，接驾距离赋值为哨兵值 {@code 999.0} km，
     * 归一化时赋 0 分（等价于最远），不因坐标缺失引发异常或排序跳过。
     *
     * <h3>副作用</h3>
     * 方法直接修改 {@code orders} 列表元素的 {@code pickupDistance} 和 {@code matchScore} 字段，
     * 并原地重排列表顺序，无返回值。
     *
     * @param orders    待排序的可接单订单列表，方法直接对其原地排序
     * @param volLon    志愿者当前经度；为 {@code null} 时方法提前返回，不做排序
     * @param volLat    志愿者当前纬度；为 {@code null} 时方法提前返回，不做排序
     * @param volCredit 志愿者信誉分，取值范围 [0, 150]，超出上限时截断至 1.0
     * @param timeCoin  志愿者时间币余额，取值范围 [0, 50]，超出上限时截断至 1.0
     */
    public void rankOrdersForVolunteer(List<AvailableOrderVO> orders, Double volLon, Double volLat,
                                       int volCredit, int timeCoin) {
        // 前置校验：列表为空或志愿者坐标缺失时无法计算接驾距离，直接返回
        if (orders == null || orders.isEmpty() || volLon == null || volLat == null) {
            return;
        }

        // 读取运行时权重配置
        Config activeConfig = configService.getCurrentConfig();
        double wDist     = activeConfig.getWDist().doubleValue();
        double wUrgency  = activeConfig.getWUrgency().doubleValue();
        double wCredit   = activeConfig.getWCredit().doubleValue();
        // wTimeCoin 为可选配置，未配置时默认 0.05，为时间币维度保留少量权重
        double wTimeCoin = activeConfig.getWTimeCoin() != null ? activeConfig.getWTimeCoin().doubleValue() : 0.05;

        // ---- 第一步：计算志愿者到各订单起点的接驾距离，并提取极值 ----
        // maxDist 初始化为 1.0 而非 0，防止极差为 0 时分母为零
        double maxDist = 1.0;
        double minDist = Double.MAX_VALUE;

        for (AvailableOrderVO order : orders) {
            double dist = 999.0; // 哨兵值：订单坐标缺失时标记为"无效距离"
            // 仅当订单起点坐标完整时，才调用 Haversine 公式计算真实球面距离
            if (order.getSourceLon() != null && order.getSourceLat() != null) {
                dist = calculateDistance(
                        volLat, volLon,
                        order.getSourceLat().doubleValue(),
                        order.getSourceLon().doubleValue()
                );
            }
            order.setPickupDistance(dist);

            // 仅将有效距离（非哨兵值）纳入极值统计，避免哨兵值污染归一化区间
            if (dist != 999.0 && dist > maxDist) maxDist = dist;
            if (dist < minDist) minDist = dist;
        }

        // 极差为 0（所有订单等距）时取 1.0，防止分母为零
        double distRange = Math.max(maxDist - minDist, 1.0);

        // ---- 第二步：逐订单计算多因子加权得分 ----
        for (AvailableOrderVO order : orders) {

            // A. 接驾距离：反向归一化——越近得分越高；哨兵值（坐标缺失）直接赋 0
            double normDist = (order.getPickupDistance() == 999.0)
                    ? 0
                    : ((maxDist - order.getPickupDistance()) / distRange);

            // B. 订单紧急度：正向归一化，满分 10 → 归一化为 1.0
            double normUrgency = order.getUrgencyLevel() / 10.0;

            // C. 信誉分赋能：正向归一化，满分 150 分 → 归一化为 1.0
            //    超出上限（如历史数据异常）时截断至 1.0，避免超权
            double normCredit = Math.min(volCredit / 150.0, 1.0);

            // D. 时间币赋能：正向归一化，满值 50 枚 → 归一化为 1.0
            //    激励机制：服务时长越长的志愿者自然积累更多时间币，排序时优先展示优质订单
            double normTimeCoin = Math.min(timeCoin / 50.0, 1.0);

            // SAW 加权求和
            double finalScore = (normDist     * wDist)
                    + (normUrgency  * wUrgency)
                    + (normCredit   * wCredit)
                    + (normTimeCoin * wTimeCoin);
            order.setMatchScore(finalScore);
        }

        // ---- 第三步：按 matchScore 降序排列——得分最高的订单排在大厅顶部 ----
        orders.sort(Comparator.comparing(AvailableOrderVO::getMatchScore).reversed());
    }

    /**
     * Haversine 球面距离公式，计算地球表面两点间的大圆距离（单位：km）。
     *
     * <h3>使用场景</h3>
     * 作为高德路线规划 API 的降级方案：当高德 API 限流、超时或不可用时，
     * 使用此方法进行粗略距离估算，保障调度流程的基本可用性。
     *
     * <h3>精度说明</h3>
     * Haversine 将地球建模为标准球体（半径 6371 km），
     * 忽略地球椭球形状及地形起伏，短距离（50km 内）误差约 0.3%，可满足调度粗排需求。
     *
     * @param lat1 起点纬度（十进制度数，南纬为负）
     * @param lon1 起点经度（十进制度数，西经为负）
     * @param lat2 终点纬度
     * @param lon2 终点经度
     * @return 两点间球面距离，单位 km
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // 地球平均半径（km）

        // 将纬度差和经度差转换为弧度，供三角函数使用
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);

        // Haversine 公式核心：计算半正矢值 a
        // a = sin²(Δlat/2) + cos(lat1) * cos(lat2) * sin²(Δlon/2)
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);

        // 中心角 c = 2 * atan2(√a, √(1-a))，再乘以地球半径得到弧长
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}