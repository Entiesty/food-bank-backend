# 暖心食光 (Heartwarming Food Bank) —— 城市食品银行智能调度与应急救援前端平台

## 项目概览

暖心食光是一个基于 Vue 3 + TypeScript + Vite 构建的现代化单页应用（SPA），专为城市社区食品银行及应急救援物资调度场景设计。平台覆盖从受赠方紧急求助、商家物资捐赠、骑手抢单配送到指挥中心全局管控的完整业务闭环。

---

## 一、核心依赖与系统入口

### 1.1 技术栈总览

* **核心框架**: Vue 3.5.25（严格 Composition API + `<script setup>`）
* **类型系统**: TypeScript 5.9.3
* **构建工具**: Vite 7.3.1
* **状态管理**: Pinia 3.0.4
* **路由管理**: Vue Router 4.6.4
* **UI 组件库**: Element Plus 2.13.2
* **地图引擎**: 高德地图 JSAPI 2.0（`@amap/amap-jsapi-loader`）
* **可视化图表**: ECharts 6.0
* **HTTP 客户端**: Axios 1.13.5
* **实时通信**: 浏览器原生 WebSocket API

### 1.2 系统入口与启动流程

* **入口文件**: `src/main.ts`
↓ `createApp(App)` → `use(router)` → `use(createPinia())` → `use(ElementPlus)` → `mount('#app')`
* **路由入口**: `src/router/index.ts`
* 采用 `createWebHistory` 模式
* 21 条路由，全部懒加载（`() => import(...)`）
* 路由守卫 `beforeEach` 中进行前端 RBAC 权限拦截（`localStorage userRole` + `meta.roles`）


* **全局布局**: `src/App.vue`
* `SideMenu`（侧边导航栏）仅在非 `/auth` 页面显示
* `keep-alive` 缓存 `AdminReview` 组件
* 页面切换动画（`page-enter` / `page-leave` transition）



---

## 二、角色体系与权限模型（RBAC）

平台定义四种角色，通过路由 `meta.roles` 与 `localStorage userRole` 实现前端拦截：

* **Role 1 — 受赠方（Recipient）**：求助发布、履约追踪、食物银行自助提取、历史档案
* **Role 2 — 商家（Merchant）**：物资捐赠、历史记录、应急雷达响应、CSR 社会责任看板
* **Role 3 — 骑手/志愿者（Rider/Volunteer）**：抢单大厅、任务执行、信誉中心
* **Role 4 — 管理员/指挥中心（Admin）**：全局调度大屏、订单流转、算法配置、用户审核、站点管理、异常监控

**审核冻结机制**：

* `isVerified === 0` 时，受赠方四张求助卡片变灰不可点击，展示红色冻结横幅。
* 骑手调度大屏显示锁屏遮罩，需等待指挥中心审核通过。
* 例外：`/volunteer/profile`（个人中心）始终可访问，供用户上传审核凭证。

---

## 三、WebSocket 实时通信机制

### 3.1 架构设计

`App.vue` 中建立全局 WebSocket 单例，连接至后端：
`ws://localhost:8080/api/ws/sos/${userId}`

采用"单一数据源 + 事件总线"模式：
WebSocket 消息 → `App.vue` 解析 → `window.dispatchEvent(CustomEvent)` → 各页面组件监听

### 3.2 消息类型与事件映射

| WebSocket type | 触发动作 | 派发的 CustomEvent |
| --- | --- | --- |
| `MODE_CHANGED` | 全局平急模式切换 | `mode-changed` |
| `NEW_SOS` | 紧急呼救（骑士+管理员） | `refresh-orders` |
| `NEW_REQ` | 常规流转单（骑士+管理员） | `refresh-orders` |
| `ORDER_TAKEN` | 订单已被抢单 | `refresh-orders` |
| `DELIVERED` | 物资已送达 | `refresh-orders` |
| `SOS_RESPONDED` | SOS已被商家响应 | `refresh-orders` |
| `ORDER_CANCELLED` | 订单被撤销（受赠方） | `refresh-orders` |
| `AUDIT_PASSED` | 资质审核通过 | `audit-status-changed` |
| `AUDIT_REJECTED` | 资质审核驳回 | `audit-status-changed` |
| `URGENT_TASK_READY` | P2P 天降神兵 | `ElMessageBox.confirm` 强弹窗 |

### 3.3 可靠性保障

* **心跳保活**：每 30 秒发送 `{"type":"PING"}`，防止 NAT/防火墙静默断连。
* **断线重连**：`onclose` 触发 5 秒后自动重连。
* **状态追赶**：重连后立即调用 `getCurrentConfig()` 拉取最新 `sysMode`，检测掉线期间模式变更并追赶同步。
* **防抖保护**：`refresh-orders` 监听器内置 300ms 防抖，避免 WebSocket 消息洪峰引发刷屏。

### 3.4 骑手 P2P 紧急任务通知的拦截逻辑

当收到 `URGENT_TASK_READY` 时，对骑手实施三级拦截：

1. **状态互斥**：`riderStatus === 'BUSY'` 时降级为温和通知。
2. **CVRP 运力硬约束**：`weightLevel >= 3` 或 `volumeLevel >= 3` 且 `vehicleType < 3`（步行/单车），直接屏蔽。
3. **通过全部拦截** → `ElMessageBox.confirm` 强制弹窗，引导导航至调度大屏。

---

## 四、智能调度系统与 SAW 多因子权重算法

### 4.1 双轨状态机

系统在两种模式下运行完全不同的调度策略：

| 维度 | NORMAL（常态模式） | EMERGENCY（应急模式） |
| --- | --- | --- |
| **物流拓扑** | Hub & Spoke 驿站中转 | P2P 直达优先 |
| **核心权重** | `wDist` = 50%（距离优先） | `wUrgency` = 70%（紧急度优先） |
| **配给制** | 无限制，自由流通 | 配给制 + 防挤兑 |
| **LBS 广播** | 关闭 | 自动激活周边商铺定向紧急募捐广播 |
| **自提通道** | 开放（`FoodBankMarket`） | 关闭，全部强制配送 |

**模式切换流程**：
管理员 API 请求 → 后端更新数据库 → 后端 WebSocket 广播 `MODE_CHANGED` → 前端同步更新 `localStorage sysMode` + 全局通知 → 各页面联动响应

### 4.2 SAW（Simple Additive Weighting）五维权重归一化

调度引擎使用加性加权模型计算每个候选驿站/骑手的综合得分，`AlgorithmConfig.vue` 提供五维滑块面板：

**五维因子及其默认配置（常态模式）**：

| 因子 | 权重 | 含义 |
| --- | --- | --- |
| `wDist` | 50% | LBS 空间距离 —— 骑手/驿站到求助点的物理距离 |
| `wUrgency` | 20% | 订单紧急度 —— 求助优先级（医疗 10 / 应急 9) |
| `wCredit` | 15% | 志愿者信誉加权 —— 历史评分与准时率 |
| `wExpiration` | 10% | 物资临期偏好（FEFO）—— 优先出库临期物资 |
| `wStock` | 5% | 据点库存偏好 —— 库存水位均衡 |

**归一化约束**：

* 五维权重之和 STRICTLY = 100%（前端滑块 step = 5%，实时校验）
* 向后端提交时，权重值除以 100，以小数形式存储（如 `wDist: 0.50`）
* 模式切换时自动拉取预设权重；也支持管理员手动微调后热更新

### 4.3 LBS 空间距离计算（Haversine 公式）

前端内置 Haversine 公式进行球面距离计算（`src/views/dispatch/index.vue:147-156`）：

$a = \sin^2(\Delta lat/2) + \cos(lat1) \times \cos(lat2) \times \sin^2(\Delta lon/2)$
$c = 2 \times \text{atan2}(\sqrt{a}, \sqrt{1-a})$
$d = R \times c$ （R = 6371 km，地球平均半径）

**坐标安全校验**：

* `isValidCoord()`：检查非空、非 NaN、isfinite
* `safeCoord()`：多层降级链（GPS → 数据库档案坐标 → GEO_FALLBACK）
* **GEO_FALLBACK**：`[118.092000, 24.623500]`（厦门集美区默认中心点）
* **漂移保护**：骑手 GPS 偏离集美区中心超过 5° 时自动降级

### 4.4 调度流程三通道

`fetchMapOrders()` 拉取待处理订单后，根据订单特征走三条通道：

* **通道一：捐赠直达（Donation Dispatch）**
* 触发条件：`orderType === 1`（定向捐赠）
* 行为：跳过 SAW 驿站匹配，直接生成骑手→捐赠商铺→社区驿站的回收路线


* **通道二：P2P 已响应（P2P Dispatch）**
* 触发条件：`sourceId` 存在且 `sourceLon`/`sourceLat` 坐标合法
* 行为：商家已确认备货，直接生成骑手→商家取货点→求助市民的三点接驾路线


* **通道三：SAW 智能匹配（Smart Dispatch）**
* 触发条件：无预设取货源，需要算法匹配最近驿站
* 调用 API：`POST /dispatch/smart-match`
* 入参：`targetLon, targetLat, requiredCategory, urgencyLevel, requiredTags, deliveryMethod`
* 降级行为：若匹配结果为空，提示"附近暂无满足条件的驿站物资，将尝试商家募捐"



### 4.5 超时兜底（运力熔断）

当 `deliveryMethod === 1`（志愿者配送）时启动倒计时（默认 30 秒）：若超时未抢单，自动调用 `switchOrderToPickup()` 将配送模式转为"居民自提"。管理员也可手动触发切为自提。

---

## 五、HTTP 请求架构

### 5.1 Axios 实例配置

* `baseURL`: `http://localhost:8080/api`
* `timeout`: 10 秒
* **请求拦截器**：自动附加 Bearer Token（从 localStorage `ACCESS_TOKEN` 读取）
* **响应拦截器**：
* `code !== 200` → `ElMessage` 警告提示 + `Promise.reject`
* HTTP 401 → 清除 Token 和 userRole → 强制跳转 `/auth` 登录页
* 网络异常 → `ElMessage.error('网络异常，请检查后端是否启动')`



### 5.2 API 模块划分

| API 模块 | 职责范围 |
| --- | --- |
| `auth.js` | 登录、注册、验证码 |
| `user.js` | 个人资料、密码修改、成就看板、头像上传、管理员列表 |
| `trade.js` | 订单发布/抢单/响应、任务执行/核销、流转查询、评价 |
| `dispatch.js` | `smart-match` 智能派单、dashboard 大屏指标、紧急广播 |
| `config.js` | 系统配置读写、模式切换、CSR 报告、切换预检 |
| `admin.js` | 用户审核、站点管理、异常监控 |
| `resource.js` | 物资捐赠发布、查询 |
| `volunteer.js` | 信誉积分、志愿者榜单 |
| `common.js` | 通用接口（文件上传等） |

**入参规范**：

* GET 请求 / 简单 POST → 使用 `params` 包装（拼接到 URL Query String）
* JSON Body 传参 → 使用 `data` 包装
* 响应统一解构 `res.data` 获取真实业务数据

---

## 六、高德地图调度大屏

### 6.1 初始化策略

* **地图容器**：`#amap-container` 常驻 DOM
* **骑手（Role 3）**：进大屏立即调用 `navigator.geolocation.getCurrentPosition()` 获取实时 GPS
* **其他角色**：从数据库档案读取坐标，降级至 `GEO_FALLBACK`
* **审核未通过或 GPS 获取失败**：展示锁屏遮罩，阻止地图渲染

### 6.2 路径渲染引擎

* 使用 `AMap.Riding` 骑行路径规划（`policy: 0`，推荐路线）
* **双段路径分层渲染**：
* 第一段（接驾段，A → B）：蓝色 `#3b82f6`，`zIndex: 60`
* 第二段（履约段，B → C）：绿色 `#10b981`，`zIndex: 50`


* **降级策略**：骑行路线规划失败时，虚线连接起终点
* **路径 NaN 点过滤**：过滤掉所有非法坐标后绘制

### 6.3 地图标注

* `sos-pulse-marker`：红色脉动圆点（求助点）
* `don-pulse-marker`：蓝色脉动圆点（捐赠商铺）
* pill-style 文字标签：白色描边圆角胶囊（"我" / "取:XX" / "送:XX"）

---

## 七、全局事件总线体系

系统使用 `window.dispatchEvent(CustomEvent)` 实现跨组件通信，不引入额外依赖：

| 事件名 | 派发者 | 监听者（响应行为） |
| :--- | :--- | :--- |
| `mode-changed` | `App.vue` (WS) | `SideMenu`、`ElderlySOS`、`dispatch`<br>`FoodBankMarket`、`AlgorithmConfig`<br>`MerchantDonate`、`EmergencyRadar` |
| `audit-status-changed` | `App.vue` + `ProfileSetting` | `SideMenu`、`ElderlySOS`、`dispatch`<br>`AdminReview`、`ProfileSetting` |
| `user-info-updated` | `ProfileSetting` | `SideMenu`（同步 `deliveryType` 等） |
| `refresh-orders` | `App.vue` (WS) | `OrderFlow`、`AdminReview`<br>`MerchantHistory`、`FoodBankMarket`<br>`dispatch/index` |

**规范约束**：
* 所有 `addEventListener` 必须在 `onUnmounted` 中 `removeEventListener`
* 监听器必须提取为命名函数引用，禁止匿名函数
* 不在多处修改同一状态 —— 保持 WebSocket 驱动的单一数据源

---

## 八、页面路由与功能矩阵

| 路由路径 | 页面组件 | 允许角色 | 核心功能 |
| --- | --- | --- | --- |
| `/auth` | `Auth.vue` | 所有（公开） | 登录/注册 |
| `/map` | `dispatch/index.vue` | 1,2,3,4 | 调度大屏 + 高德地图 |
| `/sos` | `ElderlySOS.vue` | 1,4 | 紧急求助发布 + 追踪 |
| `/market` | `FoodBankMarket.vue` | 1 | 食物银行自助提取 |
| `/recipient/history` | `RecipientHistory.vue` | 1 | 受赠方历史求助档案 |
| `/merchant/donate` | `MerchantDonate.vue` | 2 | 商家物资捐赠发布 |
| `/merchant/history` | `MerchantHistory.vue` | 2 | 商家捐赠历史记录 |
| `/merchant/radar` | `EmergencyRadar.vue` | 2 | 应急求助雷达响应 |
| `/merchant/csr` | `MerchantCsr.vue` | 2 | CSR 社会责任看板 |
| `/my-tasks` | `MyTasks.vue` | 3 | 骑手任务列表 |
| `/volunteer/credit` | `CreditCenter.vue` | 2,3 | 志愿者信誉中心 |
| `/volunteer/profile` | `ProfileSetting.vue` | 1,2,3,4 | 个人信息与凭证管理 |
| `/admin/review` | `AdminReview.vue` | 4 | 用户资质审核 |
| `/admin/users` | `UserManage.vue` | 4 | 用户管理 |
| `/admin/stations` | `StationManage.vue` | 4 | 社区驿站管理 |
| `/config` | `AlgorithmConfig.vue` | 4 | 算法权重调参引擎 |
| `/flow` | `OrderFlow.vue` | 4 | 全局订单流转追踪 |
| `/admin/exception-monitor` | `ExceptionMonitor.vue` | 4 | 异常预警大屏 |
| `*` | `Auth.vue` (fallback) | - | 404 兜底 |

---

## 九、关键业务场景流程

### 9.1 受赠方求助全链路

受赠方打开 `ElderlySOS` → 选择物资大类 → 抽屉选择物资细类 → `ElMessageBox` 二次确认 → `publishDemand()` → WebSocket `NEW_SOS` 广播 → 调度大屏自动匹配 → 骑手抢单 → 商家备货（如是捐赠）→ 骑手取货 → 配送 → 送达 → 受赠方确认收货 → 评分 → 订单闭环

### 9.2 捐赠物资流转全链路

商家发布捐赠 → 管理员审核 → 物资入库社区驿站 → 受赠方求助 → SAW 算法匹配驿站 → 骑手去驿站取货 → 配送上门

### 9.3 P2P 紧急直达全链路

受赠方发布高紧急度求助 → 管理员触发周边商铺紧急广播 → 商家在 `EmergencyRadar` 中响应（填写物资）→ WebSocket `URGENT_TASK_READY` 推送骑手 → 骑手强制弹窗确认 → 直接前往商家取货 → P2P 直达求助市民

### 9.4 模式切换全链路

管理员在 `AlgorithmConfig` 或 `dispatch/index` 点击模式切换按钮 → `ElMessageBox` 确认 → `switchMode()` API → 后端更新 `sysMode` → 后端 WebSocket `MODE_CHANGED` → `App.vue` 解析广播 → `window.dispatchEvent('mode-changed')` → 全部页面联动更新：

* `dispatch/index`：清空 pendingOrder/result + clearMap + fetchMapOrders
* `ElderlySOS`：更新 urgency 映射 + 切换横幅文案 + 关闭/开启自提入口
* `FoodBankMarket`：应急模式下强制跳转 `/sos`
* `SideMenu`：更新菜单项可用性
* `AlgorithmConfig`：同步 `form.sysMode` + 自动拉取新权重预设

---

## 十、构建与部署

### 10.1 开发环境

```bash
npm run dev              # 启动 Vite 开发服务器（127.0.0.1:5173）

```

**环境变量（`.env.development`）**：

* `VITE_AMAP_KEY`: 高德地图 JSAPI Key
* `VITE_AMAP_SECURITY_CODE`: 高德地图安全密钥（2.0 强制要求）

### 10.2 生产构建

```bash
npm run build            # vue-tsc 类型检查 + vite build
npm run preview          # 预览生产构建

```

### 10.3 后端依赖

* 前端请求直连 `http://localhost:8080/api`（未使用 Vite 代理），需要后端 Java 服务在 8080 端口运行。
* WebSocket 连接 `ws://localhost:8080/api/ws/sos/{userId}`。

---

## 十一、项目目录结构

```text
food-bank-frontend/
├── index.html                        # HTML 入口
├── package.json                      # 依赖与脚本
├── vite.config.ts                    # Vite 构建配置（含 @ 别名）
├── tsconfig.json / tsconfig.app.json # TypeScript 配置
├── CLAUDE.md                         # 项目编码规范与行为准则
├── .env.development                  # 开发环境变量
├── public/                           # 静态资源（favicon 等）
└── src/
    ├── main.ts                       # 应用入口
    ├── App.vue                       # 根组件（WebSocket + 全局布局）
    ├── style.css                     # 全局样式
    ├── vite-env.d.ts                 # Vite 类型声明
    ├── router/
    │   └── index.ts                  # 路由配置 + 权限守卫
    ├── utils/
    │   └── request.js                # Axios 实例 + 拦截器
    ├── api/
    │   ├── auth.js                   # 认证 API
    │   ├── user.js                   # 用户 API
    │   ├── trade.js                  # 订单/任务 API
    │   ├── dispatch.js               # 调度 API
    │   ├── config.js                 # 系统配置 API
    │   ├── admin.js                  # 管理后台 API
    │   ├── resource.js               # 物资/资源 API
    │   ├── volunteer.js              # 志愿者 API
    │   └── common.js                 # 通用 API
    └── views/
        ├── auth/Auth.vue             # 登录注册页
        ├── dispatch/
        │   ├── index.vue             # 调度大屏
        │   └── components/
        │       ├── SideMenu.vue      # 侧边导航菜单
        │       ├── DashboardPanel.vue # 数据面板
        │       └── DispatchControl.vue# 调度控制面板
        ├── sos/
        │   ├── ElderlySOS.vue        # 紧急求助工作台
        │   ├── FoodBankMarket.vue    # 食物银行自助超市
        │   └── RecipientHistory.vue  # 受赠方历史档案
        ├── merchant/
        │   ├── EmergencyRadar.vue    # 应急求助雷达
        │   ├── MerchantHistory.vue   # 商家历史记录
        │   └── MerchantCsr.vue       # CSR 社会责任看板
        ├── resource/
        │   └── MerchantDonate.vue    # 商家物资捐赠
        ├── trade/
        │   └── MyTasks.vue           # 骑手任务列表
        ├── volunteer/
        │   ├── ProfileSetting.vue    # 个人设置
        │   └── CreditCenter.vue      # 信誉中心
        └── admin/
            ├── AdminReview.vue       # 用户审核
            ├── AlgorithmConfig.vue   # 算法权重配置
            ├── OrderFlow.vue         # 订单流转追踪
            ├── UserManage.vue        # 用户管理
            ├── StationManage.vue     # 驿站管理
            └── ExceptionMonitor.vue  # 异常预警大屏

```
