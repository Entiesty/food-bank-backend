# 核心行为准则 (Karpathy Guidelines)

1. 编码前思考：不要假设。不要隐藏困惑。如果不确定，先提问；呈现多种解释；适时提出异议。
2. 简洁优先：用最少的代码解决问题。不要过度推测；不要添加要求之外的功能；禁止为一次性代码创建抽象。
3. 精准修改：只改必须改的地方，只清理自己造成的混乱。严禁顺手“改进”相邻代码、严禁重构没坏的模块；保持现有代码风格。
4. 目标驱动执行：定义成功标准，循环验证直到达成。将指令式任务转化为可验证的目标。

# 基础设施与框架限制 (Infrastructure & Stack)

* 核心语言: Java 21 (处理高并发场景时优先考虑 Virtual Threads 机制)
* 核心框架: Spring Boot 3.4.5 + MyBatis-Plus 3.5.10.1 (采用标准的 DDD 领域驱动设计分层规范)
* 分布式组件: Redisson 4.3.0 (严格使用 Redisson 处理分布式锁和复杂的 Redis GEO 交互)
* 实时通信: 原生 WebSocket，用于灾备与求助应急响应
* 安全与存储: JWT (jjwt 0.12.5) + MinIO 8.5.7 (完全替代阿里云 OSS)
* 数据库: MySQL 8.4 LTS
* 消息队列: RabbitMQ 4.0 (用于异步削峰与订单/任务解耦)

# 架构与并发控制规范 (绝对铁律)

## 1. 统一响应与异常拦截
* API 响应规范: 所有 Controller 层的返回值必须且只能是 `Result<T>`。成功调用 `Result.success(data)`，失败调用 `Result.failed(code, message)`，禁止返回裸对象。
* 异常抛出: 业务校验失败（如“跨区距离超限”、“库存不足”等），必须抛出 `BusinessException` 或传入 `ResultCode` 枚举，禁止抛出原生 RuntimeException。
* 异常捕获: 禁止在 Controller 层写 try-catch。所有异常交由 `GlobalExceptionHandler` (@RestControllerAdvice) 统一捕获转换。

## 2. 权限上下文环境
* 无侵入鉴权: 在 Controller 中获取用户信息，必须调用 `UserContext.getUserId()` 和 `UserContext.getUserRole()`。
* 越权阻断: 涉及越权操作，直接抛出 `BusinessException`。

## 3. 核心并发安全机制
* 分布式抢单锁 (Redisson): 在抢占式场景中，必须遵守模板：`tryLock(2, 10, TimeUnit.SECONDS)`，业务逻辑包裹在 `try` 块中，`finally` 中判断 `isHeldByCurrentThread()` 并解锁。
* 防超卖原子更新 (MyBatis-Plus): 扣减库存等场景，严禁先查后改！必须使用 LambdaUpdateWrapper 的乐观锁兜底机制，如 `.ge(Goods::getStock, num).setSql("stock = stock - " + num)`。
* 防重放/防抖锁 (Redis): 可能被连击的接口，使用 `setIfAbsent` 设置 30s 等待时间的防抖锁。

## 4. 异步链路与定时调度
* RabbitMQ 消费防丢包: 必须加 `@Transactional` 配合乐观锁条件更新状态。捕获异常后只打印日志，严禁向上抛出导致死循环重试。
* 定时自愈机制: `@Scheduled` 任务的循环处理中，必须在 for 循环内部针对单条记录进行 `try-catch`，绝不允许单条脏数据导致调度器崩溃停摆。

## 5. 实时通信与日志、Git
* 日志规范: 关键调度逻辑（算法匹配成功/失败、任务下发）必须保留完整的 SLF4J (Logback) 日志打印，格式如 `log.info("【操作标识】 业务细节: {}", data)`。
* WebSocket 路由: 内部会话管理强制使用 `ConcurrentHashMap` 确保线程安全。
* Git 规范: 必须严格遵循 Conventional Commits 规范 (feat:, fix:, chore:, refactor:)。

## 6. DevOps 与基础设施变更约束
* 容器化隔离: 严禁尝试在宿主机直接执行环境安装或配置命令（如 `apt-get`、`mysql -u`、`redis-cli` 等）。
* 间接修改原则: 任何针对 MySQL、Redis、RabbitMQ 或 MinIO 的配置变更、表结构修改或数据初始化，必须且只能通过修改 `docker-compose.yml` 文件或其对应的挂载卷脚本（如 `./mysql/init/` 目录下的 SQL 文件）来实现。
* 执行边界: 修改完配置文件或脚本后，停止操作，并明确提示用户：“请手动执行 `docker-compose up -d` 或重启相关容器以使配置生效。”