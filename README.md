# 食物银行后端调度系统 (Food Bank Backend)

基于 LBS 与多因子决策的社区"食物银行"智能调度系统，为灾备应急场景下的物资匹配、志愿者抢单与实时通信提供高并发后端支撑。

---

## 技术栈

| 组件              | 版本                           | 用途                             |
| ----------------- | ------------------------------ | -------------------------------- |
| Java              | 21 (Virtual Threads)           | 核心语言                         |
| Spring Boot       | 3.4.5                          | 应用框架                         |
| MyBatis-Plus      | 3.5.10.1                       | ORM（分页插件 + 乐观锁插件）     |
| Redisson          | 4.3.0                          | 分布式锁（抢单临界区保护）       |
| RabbitMQ          | 4.0 (spring-boot-starter-amqp) | 消息队列（异步落库解耦）         |
| WebSocket         | jakarta.websocket (原生)       | 实时双向通信                     |
| Redis             | spring-boot-starter-data-redis | 缓存 / GEO 空间索引 / 分布式计数 |
| MySQL             | 8.4 LTS                        | 关系型数据库                     |
| JWT               | jjwt 0.12.5                    | 无状态鉴权                       |
| MinIO             | 8.5.7                          | 对象存储（替代阿里云 OSS）       |
| SpringDoc OpenAPI | 2.8.5                          | Swagger 接口文档                 |
| FreeMarker        | 2.3.32                         | 代码生成器模板引擎               |
| 高德地图 API      | HTTP 外部调用                  | 骑行路线规划 / 实时测距          |

本项目为 **Spring Boot 单体和模块化 DDD 分层架构**。

---

## 项目架构（DDD 四层分层）

```
com.foodbank
├── FoodBankBackendApplication.java        # 启动入口 (@EnableScheduling)
│
├── common/                                 # 基础设施层
│   ├── api/                                 # 统一响应 Result<T> + ResultCode 枚举
│   ├── exception/                           # BusinessException + GlobalExceptionHandler
│   ├── constant/                            # Redis Key 常量
│   └── utils/                               # JwtUtils, UserContext (ThreadLocal)
│
├── config/                                  # 全局配置
│   ├── CorsConfig / JacksonConfig / WebMvcConfig
│   ├── MyBatisPlusConfig (分页 + 乐观锁)
│   ├── RabbitMQConfig (DirectExchange + Queue + Binding)
│   ├── RedissonConfig (单机模式)
│   └── OpenApiConfig (Swagger)
│
├── websocket/                               # WebSocket 实时通信层
│   ├── WebSocketConfig (ServerEndpointExporter)
│   └── WebSocketServer (@ServerEndpoint "/ws/sos/{userId}")
│
├── module/                                  # 业务模块（领域层 + 应用层）
│   ├── auth/          # 认证模块（注册/登录/验证码/JWT 鉴权拦截器）
│   ├── dispatch/      # ★ 调度引擎核心（匹配、抢单、广播、定时撮合）
│   ├── resource/      # 资源模块（物资 goods + 驿站 station + Redis GEO）
│   ├── trade/         # 交易模块（订单 order + 配送任务 task + MQ 消费者）
│   └── system/        # 系统模块（用户 user + 配置 config + 文件上传）
│
└── runner/                                  # 启动 Banner 打印
```

---

## 核心调度引擎

### 两级降级寻源链路

系统采用 **L0 → L1 两级降级** 物资寻源策略：

```
L0: P2P 直达（商家主动供给）
  │ 检索商家已响应的直供订单，虚拟化为负 ID 驿站
  │ 标签硬过滤（Jaccard 交集为空直接丢弃）
  │ 距离硬阈值 50km（超出直接剪枝）
  │ 命中 → 短路返回，跳过 L1
  ↓ 无匹配时降级
L1: Hub-and-Spoke 驿站中转
  │ Redis GEO GEORADIUS 命令，50km 半径检索驿站
  │ 逐站查询可用物资（品类匹配 + 模式过滤）
  │ 标签 Jaccard 相似度加分（非硬过滤，保证召回率）
  │ 调用高德骑行 API 计算实际配送距离/耗时
  ↓ 仍无匹配时
紧急广播三级扩散（10km → 30km → 全城） / 商家募捐兜底
```

### 多因子 SAW 决策算法（Min-Max 归一化 + 加权求和）

#### 系统端自动指派（四维评分）

对候选驿站/物资执行 Min-Max 归一化，消除异构量纲差异后加权求和：

| 维度   | 原始指标            | 归一化方向                        | 权重 (NORMAL) | 权重 (EMERGENCY) |
| ------ | ------------------- | --------------------------------- | ------------- | ---------------- |
| 距离   | 骑行米数 (高德 API) | 反向 `(max-x)/range` — 越近越高   | 0.50          | 0.10             |
| 库存   | 物资件数            | 正向 `(x-min)/range` — 越多越高   | 0.05          | 0.10             |
| 临期   | 过期时间戳 (秒)     | 反向 `(max-x)/range` — 越临期越高 | 0.10          | 0.05             |
| 紧急度 | 订单紧急等级 [1,10] | 全局归一化 `urgency/10.0`         | 0.20          | 0.70             |

**应急枢纽加成**：当 `urgencyLevel ≥ 8` 且站点 `isEmergencyHub = 1` 时，附加 `+1.0` emergencyBonus，在 EMERGENCY 模式下（wUrgency=0.70）可将应急枢纽得分提升 0.7 以上，确保应急物资优先从枢纽调配。

**SAW 综合得分公式**：

```
finalScore = normDist × wDist + normStock × wStock + normExpiration × wExpiration + (normUrgency + emergencyBonus) × wUrgency
```

#### 志愿者抢单大厅（四维千人千面排序）

| 维度       | 归一化方式                                  | 说明                     |
| ---------- | ------------------------------------------- | ------------------------ |
| 接驾距离   | 反向 Min-Max，坐标缺失→哨兵值 999km→赋 0 分 | 越近越优先               |
| 订单紧急度 | `urgencyLevel / 10.0`                       | 高分订单前置             |
| 信誉分     | `min(creditScore/150, 1.0)`                 | 上限 150，激励高质量服务 |
| 时间币     | `min(timeCoin/50, 1.0)`                     | 上限 50，奖励长期服务    |

#### 运行时权重热更新

六维权重（`wDist`, `wUrgency`, `wCredit`, `wExpiration`, `wStock`, `wTimeCoin`）存储在 `sys_config` 表中，通过 `ConfigController` 的 REST API 实时修改，调度策略在每次计算时从数据库读取最新值，**无需重启服务**。系统内置 NORMAL/EMERGENCY 两套预设，支持一键切换。

---

## 实时通信 — WebSocket 灾备高并发底层机制

### 架构设计

```
┌──────────────────────────────────────────────────────────┐
│  前端 (指挥中心大屏 / 志愿者 App / 商家终端)                │
│    │  WebSocket 连接 ws://host:8080/api/ws/sos/{userId}    │
└────┼──────────────────────────────────────────────────────┘
     │
┌────▼──────────────────────────────────────────────────────┐
│  WebSocketServer (@ServerEndpoint)                        │
│  ├─ ConcurrentHashMap<Long, Session> sessionMap           │
│  │  线程安全的在线用户会话注册表                              │
│  ├─ @OnOpen  → sessionMap.put(userId, session)            │
│  ├─ @OnClose → sessionMap.remove(userId)                  │
│  ├─ @OnError → log.error()                                │
│  └─ @OnMessage → PING/PONG 心跳防断连                       │
│                                                           │
│  核心 API:                                                 │
│  ├─ sendMessageToUser(userId, msg) — 点对点推送             │
│  │  向受助方实时推送 SOS 响应/物资匹配结果                    │
│  └─ broadcast(msg) — 全网广播                              │
│     向大屏推送实时指标/订单状态变更/异常报警                   │
└──────────────────────────────────────────────────────────┘
```

### 性能优化措施

1. **ConcurrentHashMap 会话管理**：`ConcurrentHashMap<Long, Session>` 确保多线程并发读写在线用户表时的线程安全，避免 `HashMap` 在扩容时产生死循环或数据丢失。读操作不加锁，写操作仅锁分段（JDK 内部 CAS + synchronized），支撑大规模并发连接。

2. **心跳 PING/PONG 机制**：前端定时发送 `{"type":"PING"}`，服务端回复 `{"type":"PONG"}`，防止 NAT 路由器/防火墙因长时间无数据交互而静默断开 WebSocket 连接（典型 NAT 超时 30s-120s）。

3. **非阻塞广播**：`broadcast()` 方法遍历 `sessionMap` 同步发送，在会话数较少（通常数百以内）时保持低延迟；若需万级并发广播，可通过 RabbitMQ Fanout Exchange 将广播消息异步分发至水平扩展的多个 WebSocket 节点。

4. **异常隔离**：单条推送失败（如某用户突然断连但未触发 @OnClose）仅记录日志，不影响同批次其他用户的推送。

### 消息推送场景

| 场景          | 推送方式        | 触发时机                   |
| ------------- | --------------- | -------------------------- |
| SOS 响应通知  | 点对点 → 受助方 | 商家/驿站匹配成功后        |
| 模式切换广播  | 全网广播        | NORMAL ↔ EMERGENCY 切换    |
| 新订单通知    | 全网广播        | 受助方发布新求助           |
| 订单取消/变更 | 点对点          | 订单状态变更               |
| 骑手任务通知  | 点对点          | 配送任务分配/完成          |
| 异常监控告警  | 全网广播 → 大屏 | 零库存 / 订单滞留超 3 分钟 |

---

## 分布式并发控制

### Redisson 分布式抢单锁

```java
// 模板：tryLock(waitTime=2s, leaseTime=10s, TimeUnit.SECONDS)
RLock lock = redissonClient.getLock("lock:order:grab:" + orderId);
try {
    boolean isLocked = lock.tryLock(2, 10, TimeUnit.SECONDS);
    if (!isLocked) throw new BusinessException("抢单人数过多，请重试");
    // ① Double Check 订单状态（防止锁等待期间被他人领取）
    // ② CVRP 载具容量约束校验（体积/重量累计点值）
    // ③ RabbitMQ 异步落库
} finally {
    if (lock.isHeldByCurrentThread()) lock.unlock();
}
```

**关键设计**：

- 锁粒度为**单个订单**（`lock:order:grab:{orderId}`），而非全局锁，避免不相关订单的争用。
- `isHeldByCurrentThread()` 安全释放：Redisson WatchDog 在 10s TTL 到期自动回收锁后，`finally` 块如不判断就直接 `unlock()` 会误删其他线程持有的新锁。
- `tryLock` 而非 `lock`：避免线程无限阻塞，超时即返回提示用户重试。

### MyBatis-Plus 乐观锁防超卖

```java
// 严禁先查后改！使用 LambdaUpdateWrapper 的条件更新兜底
boolean success = goodsMapper.update(null,
    new LambdaUpdateWrapper<Goods>()
        .eq(Goods::getGoodsId, goodsId)
        .ge(Goods::getStock, num)          // 乐观锁条件：库存 ≥ 扣减量
        .setSql("stock = stock - " + num)  // 原子更新
);
```

### Redis SETNX 防重放/防抖锁

```java
// 紧急广播防抖：30s TTL，防止管理员短时间重复触发造成商家消息轰炸
Boolean isLocked = stringRedisTemplate.opsForValue()
    .setIfAbsent("LOCK:BROADCAST:" + orderId, "1", 30, TimeUnit.SECONDS);
```

---

## RabbitMQ 异步削峰

### 架构

```
抢单请求 ──→ [Redisson 分布式锁] ──→ [CVRP 校验通过]
                                          │
                          RabbitTemplate.convertAndSend()
                                          │
                                   ┌──────▼───────┐
                                   │ DirectExchange │
                                   │ dispatch.order  │
                                   └──────┬───────┘
                                          │ routingKey: grab.order
                                   ┌──────▼──────────┐
                                   │ grab.order.queue  │
                                   └──────┬──────────┘
                                          │
                              OrderTaskConsumer (@RabbitListener)
                                          │
                              @Transactional + 乐观锁条件更新
                                          │
                              订单状态跃迁 → 生成配送任务 → WebSocket 广播
```

**关键保障**：

- 消费端加 `@Transactional`：防止消息丢失（事务回滚后消息重新入队）。
- 异常只记日志、不向上抛出：避免 `AmqpRejectAndDontRequeueException` 未配置导致的死循环重试。
- 解耦效果：抢单 API 响应时延仅包含锁获取 + CVRP 校验 + MQ 投递，数据库写入在消费端异步完成，峰值吞吐显著提升。

---

## 定时自愈机制

### 自动撮合引擎 (`@Scheduled fixedDelay=5000`)

每 5 秒扫描 `status=0` 且 `orderType=2` 的待匹配 SOS/REQ 订单，自动调用 `smartMatchStations` 执行 L0→L1 寻源并原子扣减库存。每条记录独立 `try-catch`，单条异常不影响其余订单处理。

### 异常订单监控 (`@Scheduled cron="0 * * * * ?"`)

仅 EMERGENCY 模式下每分钟检测：

- **零库存告警**：品类库存耗尽时标记异常原因
- **滞留告警**：订单创建超过 3 分钟仍未匹配时标记
- **自愈恢复**：库存恢复或订单被接单后自动清除异常标记

---

## 距离计算与空间索引

### Haversine 球面距离公式

作为高德骑行 API 的降级方案，将地球建模为标准球体（半径 6371 km），计算任意两点间的大圆距离：

```
a = sin²(Δlat/2) + cos(lat1)·cos(lat2)·sin²(Δlon/2)
c = 2·atan2(√a, √(1-a))
distance = R × c   (单位: km)
```

短距离（50km 内）误差约 0.3%，满足调度粗排需求。

### Redis GEO 空间索引

驿站坐标在服务启动时自动预热至 Redis GEO 集合（`StationGeoServiceImpl`），系统运行时通过 `GEORADIUS` 命令以受助方坐标为中心检索 50km 半径内的最近驿站，结合高德骑行 API 获取实时路线距离。

---

## CVRP 载具容量约束

抢单前对志愿者的运力进行双重校验：

| 载具类型         | 体积上限 | 重量上限 | 跨区限制 |
| ---------------- | -------- | -------- | -------- |
| 步行 (vType=1)   | 2 点     | 2 点     | ≤ 50km   |
| 单车 (vType=2)   | 5 点     | 4 点     | ≤ 50km   |
| 电动车 (vType=3) | 15 点    | 10 点    | 无限制   |
| 货车 (vType=4)   | 100 点   | 100 点   | 无限制   |

物资体积/重量点值映射：level=1（小件）→ 1 点、level=2（中件）→ 5 点、level=3（大件）→ 体积 40 点 / 重量 20 点。

抢单时累加志愿者当前进行中任务（taskStatus = 1 或 2）的累计体重点值 + 新订单体重点值，超出载具上限即拒单。

---

## 项目不是 RPC 框架 / 微服务体系的说明

1. **非 RPC 框架**：项目中不存在 Zookeeper 服务注册中心、Dubbo RPC 调用、gRPC 协议栈或任何动态代理（Proxy/InvocationHandler）相关代码。所有远程调用均为 REST（高德地图 API 使用 Spring RestClient）和 RabbitMQ 异步消息。

2. **非微服务体系**：项目为 Spring Boot 单体和应用，尽管采用 DDD 四层分包（common/config/module/websocket），但所有模块共享同一个进程空间和数据库连接池。未引入 Spring Cloud、Nacos、Sentinel、Zipkin/SkyWalking 等微服务治理或链路追踪组件。

3. **灾备 WebSocket 后端定位**：项目的实时通信基于 Jakarta WebSocket 原生实现（非 STOMP/Netty），适用于数百级并发连接的指挥中心大屏、志愿者 App 及商家终端的双向通知场景。若需扩展至万级并发，建议引入 Netty + RabbitMQ Fanout 广播的水平扩展方案。

---

## 快速启动

```bash
# 1. 启动基础设施
docker-compose up -d

# 2. 初始化数据库（自动执行 ./mysql/init/ 下的 SQL 脚本）

# 3. 启动应用
./mvnw spring-boot:run

# 4. 访问 Swagger 文档
# http://localhost:8080/api/swagger-ui.html
```

**默认配置**：

- 服务端口：`8080`
- 上下文路径：`/api`
- WebSocket 端点：`ws://localhost:8080/api/ws/sos/{userId}`
- JWT 密钥通过 `jwt.secret` 配置

---

## 系统运行模式

| 模式      | 触发                | 权重特征                  | 行为差异                                               |
| --------- | ------------------- | ------------------------- | ------------------------------------------------------ |
| NORMAL    | 默认 / 手动切换     | wDist=0.50, wUrgency=0.20 | 距离优先，标签软匹配                                   |
| EMERGENCY | 手动切换 / 灾备触发 | wDist=0.10, wUrgency=0.70 | 紧急度优先，开启异常监控，标签硬过滤，启用应急枢纽加成 |

运行模式通过 `ConfigController` 热切换，所有调度策略实时生效。

---

## API 模块总览

| 模块   | 路由前缀              | 职责                                           |
| ------ | --------------------- | ---------------------------------------------- |
| 认证   | `/auth`               | 注册、登录、短信验证码、找回密码、登出         |
| 调度   | `/dispatch`           | 智能匹配、抢单、取货确认、紧急广播、商家轮询   |
| 看板   | `/dispatch/dashboard` | 今日指标、分类库存占比、志愿者排行榜           |
| 物资   | `/resource/goods`     | 捐赠入库、列表分页、库存校准、撤销             |
| 驿站   | `/resource/station`   | 新增/更新据点、GEO 搜索、推荐排序              |
| 订单   | `/trade/order`        | 待处理列表、SOS 响应、抢单大厅、异常监控、核销 |
| 任务   | `/trade/task`         | 确认取货、核销送达、我的任务                   |
| 用户   | `/system/user`        | 个人信息、资料更新、资质审核、大盘看板         |
| 管理   | `/admin`              | 商家准入、全域用户管理、信誉分干预、账号清退   |
| 商家   | `/merchant`           | CSR 战报（捐赠统计、受助人数、品类分布）       |
| 志愿者 | `/volunteer/credit`   | 信誉分看板、荣誉等级、积分流水                 |
| 配置   | `/system/config`      | SAW 权重热更新、模式切换、战备预检             |
| 文件   | `/common/file`        | MinIO 图片上传                                 |
