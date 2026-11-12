# AGENT.md — enjoy-iot 项目说明书（AI Agent 协作版）

> 本文档用于给 AI 编码助手 / Agent 快速建立项目心智模型。
> 阅读顺序建议：**1 → 2 → 3 → 5 → 6 → 9**，其余按需查阅。

---

## 1. 项目定位

`enjoy-iot` 是一个基于 **yudao-cloud（芋道）** 改造的 **物联网（IoT）平台** 后端 + 管理后台前端：

- 后端：Spring Boot（Java 21）单体工程，通过模块化多 Maven 模块管理业务
- IoT 接入：通过插件化 **Component** 适配多种设备协议（MQTT / CoAP / HTTP / TCP / UDP / Modbus / GE 系列）
- 时序数据：使用 **TDengine**（主）+ **IoTDB**（备）存储设备遥测
- AI：在 `module-ai` 中集成 DeepSeek，提供数据分析能力
- 前端：基于 **yudao-ui-admin-vue3**（Vue 3 + Vite + Element Plus）
- 部署：应用直跑在宿主机，中间件（MySQL/Redis/TDengine/EMQX）跑在 Docker

---

## 0. ⭐ 命名规则（**最重要，先看这条**）

本项目有一条**强约定**：**凡是新建的文件、类、表，一律以 `y` / `Y` 开头**（`y` 代表 enjoy，是项目统一前缀）。

> 例：`YDeepSeekClient`、`y_farm`、`y_pond`、`yt-crud.vue`、`YFarmService`…

### 0.1 Java 类名

| 类型 | 命名格式 | 示例 |
|---|---|---|
| 普通类 / 工具类 | `Y` + 驼峰名 | `YDeepSeekClient` |
| 业务实体 DO | `Y` + 业务名 | `YFarmDO` |
| 业务 Controller | `Y` + 业务名 + `Controller` | `YFarmController` |
| 业务 Service | `Y` + 业务名 + `Service` / `ServiceImpl` | `YFarmServiceImpl` |
| Mapper | `Y` + 业务名 + `Mapper` | `YFarmMapper` |
| VO / DTO | `Y` + 业务名 + `VO` / `DTO` | `YFarmSaveReqVO` |

> ⚠️ **只作用于"新创建"的文件**。已有文件（yudao 继承来的、`AbstractComponent`、`Component` 等）**保持原名，不要改名**。

### 0.2 数据库表

新建表**统一以 `y_` 开头**（表名小写下划线）：`y_业务名`。示例：`y_farm`、`y_pond`、`y_device_alarm`。

- 字段名沿用 `snake_case`（yudao 风格）。
- 建表脚本放到 `_sql/migrations/`，命名 `YYYYMMDD_y_业务名_动作_mysql.sql`。

### 0.3 前端

新建页面 / 组件 / 工具文件**以 `y` 开头的小驼峰或短横线命名**：`yDeviceList.vue`、`y-crud.vue`、`yFarm.ts`。

### 0.4 IoT 组件（例外说明）

`module-eiot/iot-components/` 下的子模块目录**沿用 `iot-component-xxx` 前缀**（如 `iot-component-udp`），不适用 `y` 前缀；但**组件内的业务类**仍建议 `Y` 开头。

---

## 2. 技术栈

| 层 | 技术 |
|---|---|
| 语言 / 运行时 | **Java 21**（分支 `v1.0.0-jdk21`） |
| 后端框架 | Spring Boot、Spring Cloud、Spring Security |
| 持久层 | MyBatis + MyBatis-Plus、Druid（动态数据源 master/slave） |
| 关系库 | **MySQL 8**（主）、PostgreSQL（备，部分 SQL 提供 pg 版本） |
| 缓存 | **Redis 6** |
| 时序库 | **TDengine 3.3**（主）、IoTDB（备） |
| 消息 | **EMQX 5.8**（MQTT 接入） |
| 物联网接入 | **Vert.x**（设备长连接/异步 IO），见 `module-eiot/iot-components/` |
| 工具库 | Lombok、MapStruct、Fastjson2、Hutool 等（由 `framework/` 提供） |
| AI | DeepSeek（`module-ai` 中通过 `YDeepSeekClient` 调用） |
| 前端 | Vue 3.5、Vite 5、Element Plus 2.9、TypeScript 5.3、Pinia、UnoCSS、mqtt.js |
| 构建 | **Maven**（多模块）；前端 **pnpm** |
| 部署 | Shell 脚本 + Docker Compose（仅中间件） |

> ⚠️ 项目用 Java 21，不要混用 Lombok/注解处理器不兼容版本。

---

## 3. 仓库结构

```text
enjoy-iot/
├── pom.xml                       # 父 POM（依赖管理、模块聚合）
├── dependencies/                 # 第三方依赖 BOM
├── framework/                    # 自研 starter 集合（17 个）
│   ├── common/                   # 通用工具、异常、常量
│   ├── spring-boot-starter-web/  # Web、统一返回、全局异常
│   ├── spring-boot-starter-mybatis/
│   ├── spring-boot-starter-redis/
│   ├── spring-boot-starter-security/
│   ├── spring-boot-starter-job/  # 定时任务（注意：Quartz 已被禁用，见 §6）
│   ├── spring-boot-starter-mq/   # Redis Stream / RocketMQ 抽象
│   ├── spring-boot-starter-websocket/
│   ├── spring-boot-starter-excel/
│   ├── spring-boot-starter-biz-data-permission/  # 数据权限
│   ├── spring-boot-starter-biz-tenant/          # 多租户
│   ├── spring-boot-starter-biz-ip/              # IP 归属地
│   ├── spring-boot-starter-monitor/             # Spring Boot Admin
│   ├── spring-boot-starter-protection/          # 幂等/限流
│   └── spring-boot-starter-test/                # 单元测试支持
│
├── server/                       # ⚡ Spring Boot 启动模块（唯一可执行）
│   ├── pom.xml                   # 依赖各 module-* 与 framework
│   └── src/main/
│       ├── java/com/enjoyiot/server/ServerApplication.java   # main 入口
│       └── resources/
│           ├── application.yaml         # 公共配置（端口、token、跨域等）
│           ├── application-local.yaml   # 本地开发
│           ├── application-test.yaml    # 测试环境
│           ├── application-prod.yaml    # 正式环境
│           ├── application-eiot.yaml    # IoT 业务配置（组件开关、协议端口）
│           ├── logback-spring.xml
│           └── banner.txt
│
├── module-ai/                    # AI 模块（DeepSeek 数据分析）
│   ├── module-ai-api/            # 对外 API / DTO
│   └── module-ai-biz/            # 业务实现（含 YDeepSeekClient）
│
├── module-eiot/                  # IoT 业务模块
│   ├── module-eiot-api/          # 对外 API / DTO
│   ├── module-eiot-biz/          # 业务实现（controller/service/dal/convert）
│   ├── module-eiot-core/         # 核心能力（被 biz 依赖）
│   │   ├── iot-common-core/      # 通用核心
│   │   ├── iot-common-thing/     # 物模型
│   │   ├── iot-message-bus/      # 消息总线
│   │   ├── iot-message-notify/   # 消息通知
│   │   ├── iot-rule-engine/      # 规则引擎
│   │   ├── iot-script-engine/    # 脚本引擎
│   │   └── iot-virtual-device/   # 虚拟设备
│   ├── module-eiot-task/         # 定时/异步任务
│   ├── module-eiot-temporal/     # Temporal 工作流
│   └── iot-components/           # 设备协议插件（SPI 式）
│       ├── iot-component-core/         # 抽象基类与接口（Component / ThingComponent / AbstractComponent）
│       ├── iot-component-mqtt/         # MQTT 通用客户端
│       ├── iot-component-emqx/         # EMQX 桥接
│       ├── iot-component-coap/         # CoAP
│       ├── iot-component-http/         # HTTP 设备接入
│       ├── iot-component-tcp/          # TCP
│       ├── iot-component-udp/          # UDP（含自定义解析器）
│       ├── iot-component-modbus-custom/  # 自定义 Modbus 协议
│       ├── iot-component-ge100d/       # 某厂商 100D 设备
│       └── iot-component-geio/         # geio 设备（基于 Vert.x）
│
├── enjoy-web/                    # 前端（yudao-ui-admin-vue3 改造）
│   ├── package.json
│   ├── src/                      # 业务页面
│   └── .env* / vite.config.ts
│
├── _script/                      # 部署与运维脚本
│   ├── shell/                    # 部署到远端的 shell
│   │   ├── deploy.sh             # 后端测试环境
│   │   ├── deploy-prod.sh        # 后端正式环境
│   │   ├── deploy-web.sh         # 前端测试
│   │   ├── deploy-prod-web.sh    # 前端正式
│   │   ├── deployOld.sh          # 旧版
│   │   └── id_ed25519            # 🔒 部署用 SSH 私钥（**不要提交到公网**）
│   ├── docker/                   # 中间件 docker-compose
│   │   ├── docker-compose.yml        # 基础
│   │   ├── docker-compose-test.yml   # 测试环境
│   │   └── iot-server/Dockerfile
│   └── python/                   # 一些工具脚本（如日志清洗）
│
├── _sql/                         # 数据库脚本
│   ├── mysql/                    # 初始结构
│   ├── postgresql/               # 初始结构
│   ├── migrations/               # 增量迁移（命名 YYYYMMDD_xxx_*.sql）
│   ├── tdengine/                 # TDengine 库/超级表
│   ├── redis/
│   ├── emqx/
│   └── tools/                    # 数据修复/工具脚本
│
├── docs/                         # 项目文档
├── lombok.config
└── log/                          # 本地日志输出（已 .gitignore）
```

> 🔒 **不要**把 `_script/shell/id_ed25519` 提交到公开仓库；目前已在 `_script/shell/` 下但**未在 `.gitignore`** 中，提交前请自行评估。

---

## 4. 本地开发

### 4.1 后端

```bash
# 前置：JDK 21、Maven 3.8+、Docker（启中间件）
# 1. 启动 MySQL/Redis/TDengine/EMQX（仅本机端口）
docker compose -f _script/docker/docker-compose.yml up -d

# 2. 初始化数据库（首次）
#    MySQL：导入 _sql/mysql/system.sql
#    TDengine：执行 _sql/tdengine/*.sql

# 3. 启动后端（默认 profile = local）
cd server
mvn spring-boot:run \
    -Dspring-boot.run.profiles=local,eiot
```

- IDEA 启动：直接跑 `ServerApplication#main`，VM options 加 `-Dspring.profiles.active=local,eiot`
- 应用监听端口见 `application.yaml` / `application-local.yaml`

### 4.2 前端

```bash
cd enjoy-web
pnpm i
pnpm dev                # 等价 vite --mode env.local
# 其他环境：
pnpm dev-server         # dev
pnpm build:test         # 输出 dist-test/
pnpm build:prod         # 输出 dist-prod/
```

> 前端构建 Node 内存上限统一 `4G`（见 `package.json` scripts），无需再手动调。

---

## 5. 构建 & 部署

### 5.1 后端打包

```bash
# 在项目根目录
mvn -DskipTests clean package
# 产物：server/target/server.jar
```

部署脚本约定（见 `_script/shell/`）：

| 脚本 | 作用 |
|---|---|
| `deploy.sh` | 后端 → **测试**环境 |
| `deploy-prod.sh` | 后端 → **正式**环境 |
| `deploy-web.sh` | 前端 → 测试 |
| `deploy-prod-web.sh` | 前端 → 正式 |

> 部署脚本通过 SSH 推送产物到远端。**所有部署脚本使用同一把 SSH 私钥** `_script/shell/id_ed25519`。
> 修改部署脚本时务必保留：`-o StrictHostKeyChecking=no -p 22 -i ${KEY_FILE}`。

### 5.2 远端环境

| 项 | 值 |
|---|---|
| 主机 | `119.45.251.129`（腾讯云） |
| 端口 | `22` |
| 用户 | `root` |
| 私钥 | `_script/shell/id_ed25519` |
| 系统 | TencentOS Server 3.3（CentOS/RHEL 系） |
| 规格 | 8C8G，磁盘 50G，**已配置 8GB swap** |
| API 端口 | 测试 `30010`、正式 `30011`（仅本机，nginx 反代） |
| MySQL | 容器内 3306 → 宿主机 `30015` |
| Redis | 宿主机 `6379`（测试 db 8、正式 db 9） |
| TDengine | 宿主机 `6041` |
| 前端静态目录 | `/shihe_back/enjoy-iot/enjoy-web/` |

> 正式环境 API 监听 `127.0.0.1:30011`，**必须**先配好 nginx 反代（`admin-api` 域名 → `127.0.0.1:30011`），否则会失联。

### 5.3 部署后端示例

```bash
# 测试环境
bash _script/shell/deploy.sh

# 正式环境
bash _script/shell/deploy-prod.sh
```

> 部署脚本会做：编译 → 上传 jar → 远端停服 → 启动 jar（通常用 `nohup java -jar ... &`）。
> **正式环境务必确认 nginx 与备份策略**。

---

## 6. 关键配置 & Profile

`server/src/main/resources/` 下的 profile：

| 文件 | 用途 |
|---|---|
| `application.yaml` | 公共：端口、Token 签名、日志、跨域、文件上传、JVM 提示 |
| `application-local.yaml` | 本地开发（连本机 Docker 中间件） |
| `application-test.yaml` | 测试环境（**库 `shihe`、Redis db 8**） |
| `application-prod.yaml` | 正式环境（**库 `shihe-prod`、Redis db 9、监听 127.0.0.1**） |
| `application-eiot.yaml` | IoT 业务总开关（各 Component 启停、协议端口） |

⚠️ **重要约定（不要破坏）**：

1. **Quartz 已被禁用**：`application.yaml` 与 `*-test/prod` 都 exclude 了 `QuartzAutoConfiguration`，**`infra_job` 表的定时任务不会执行**。真正运行的是 Spring `@Scheduled` 注解任务。
2. **`@Scheduled` 多实例重复执行风险**：测试 + 正式同时跑在同一台机时，**所有 `@Scheduled` 任务会双跑**。新加 `@Scheduled` 任务必须考虑幂等。
3. **Druid 监控台**：
   - 测试：`enabled: true`
   - 正式：`stat-view-servlet.enabled: false`（避免无鉴权暴露）
4. **Redis db**：测试 8、正式 9。**不要改密码、不要混用 db**。
5. **MySQL 用户**：测试 `shihe / fnfDDLcAeZrW6z5j`、正式 `shihe-prod / 8XkcrwE5PYXySLZa`。**生产密码仅在正式库生效**。

---

## 7. 模块与代码架构

### 7.1 包名根

所有 Java 代码根包：`com.enjoyiot.*`

| 模块 | 主包 | 入口/关键类 |
|---|---|---|
| `server` | `com.enjoyiot.server` | `ServerApplication` |
| `framework` | `com.enjoyiot.framework` | 各 starter 下的 `XxxAutoConfiguration` |
| `module-ai/module-ai-biz` | `com.enjoyiot.module.ai` | `YDeepSeekClient`（DeepSeek 调用） |
| `module-eiot/module-eiot-biz` | `com.enjoyiot.module.eiot` | 设备/产品/物模型 controller & service |
| `module-eiot/module-eiot-core/*` | `com.enjoyiot.eiot.*` | 物模型 / 规则引擎 / 消息总线等核心能力 |
| `module-eiot/iot-components/iot-component-core` | `com.enjoyiot.eiot.component.core` | `Component` / `ThingComponent` / `AbstractComponent` |

### 7.2 IoT 组件（**重点**）

设备协议以 **插件化** 方式接入。`iot-component-core` 提供：

- `Component`：组件顶层接口（生命周期、配置、设备上下线下行）
- `ThingComponent`：物模型相关扩展接口（属性/事件/服务调用）
- `AbstractComponent`：抽象实现，**新协议请继承它**
- `ComponentServices`：组件间共享服务（消息分发、设备注册）

> 已有插件：`mqtt / emqx / coap / http / tcp / udp / modbus-custom / ge100d / geio`，新增协议 = 在 `iot-components/` 下加一个子模块 + 注册到 `application-eiot.yaml`。

### 7.3 业务模块常用包结构（yudao 风格）

```text
com.enjoyiot.module.eiot          # module-eiot-biz
├── controller          # 管理后台 BFF（/admin-api/iot/...）
├── service             # 业务逻辑
├── dal                 # dataobject / mysql(Mapper)
├── convert             # MapStruct 转换
├── config / enums / util / api
```

> 命名风格遵循 yudao：**DO / VO / DTO / Convert / Controller / Service / ServiceImpl**。
> **新建类请加 `Y` 前缀**（见 §0）。

---

## 8. 数据库与中间件

| 组件 | 默认端口 | 数据位置 | 初始化 |
|---|---|---|---|
| MySQL 8 | 容器 3306 → 宿主机 30015 | Docker volume `mysql` | `_sql/mysql/system.sql` 启动时自动执行 |
| Redis 6 | 6379 | volume `redis` | 手动 `requirepass` 设置密码 |
| TDengine 3.3 | 6041 / 6030 | volume `tdengine_lib` | `_sql/tdengine/` 手动建库 |
| EMQX 5.8 | 1883 / 8083 / 18083 | volume `emqx-data` | Dashboard admin/public |
| IoTDB | 6667 | _（备用）_ | _（备用）_ |

迁移脚本命名：**`YYYYMMDD_业务名_动作_mysql.sql` 与 `_postgresql.sql` 一同维护**（见 `_sql/migrations/`）。
→ 新加表/字段必须同时提交 mysql 与 postgresql 两份（若启用 pg）。

---

## 9. 常见任务流程（Do）

### 9.1 加一个管理后台接口

1. `module-eiot/module-eiot-biz/.../controller/xxx/` 新增 Controller（**类名 `Y` 开头**）
2. 在对应 `service/` 加方法、DO/VO、Convert（**同上 `Y` 前缀**）
3. 走 **yudao 的 `@RestController` + `@RequestMapping("/admin-api/iot/...")`** 统一前缀
4. 如需权限码：在 `system_menu` 初始化 SQL 里加
5. 前端：`enjoy-web/src/api/iot/...` 加 ts 封装 + 页面（**文件名 `y` 开头**）

### 9.2 加一种设备协议

1. `module-eiot/iot-components/` 下新建子模块，依赖 `iot-component-core`
2. 实现 `AbstractComponent`（或 `Component` 接口）
3. 资源目录加 `META-INF/services/com.enjoyiot.eiot.component.core.Component` 注册 SPI
4. `server/pom.xml` 增加对该子模块的依赖
5. `application-eiot.yaml` 加该组件的开关与协议端口
6. 写最少 1 个设备消息解析的单测（`iot-component-core` 提供 TestSupport）

### 9.3 加一个 SQL 迁移

```text
_sql/migrations/
├── YYYYMMDD_y_业务_动作_mysql.sql
├── YYYYMMDD_y_业务_动作_postgresql.sql   # 与上同名同结构（注意类型差异）
└── （如改 TDengine）YYYYMMDD_y_业务_动作_tdengine.sql
```

> 日期以 `git log --pretty=format:%cd` 提交当天日期为准，不要用「明天」。
> **新建表须以 `y_` 开头**（见 §0.2）。

### 9.4 调整启动参数

- 仅本机生效：改 `application-*.yaml`
- 跨环境：先确认是「测试/正式」共用还是区分，**不要在公共 `application.yaml` 写环境相关值**

---

## 10. 踩坑清单（Don't）

| 行为 | 后果 / 为什么不要 |
|---|---|
| ❌ 启用 Quartz 跑 `infra_job` | 与现有架构冲突，会双跑 |
| ❌ 加 `@Scheduled` 不做幂等 | 测试+正式同机会双跑 |
| ❌ 正式环境把 `stat-view-servlet` 打开且不设账号 | Druid 监控台无鉴权公网暴露 |
| ❌ 改 Redis db 索引 / 密码后忘了同步两个 profile | 缓存穿透 |
| ❌ 提交 `_script/shell/id_ed25519` 到公网 | 私钥泄露 |
| ❌ 直接 `mvn install` 不带 `-DskipTests` 在 CI 之外 | 单测可能调真实 DB |
| ❌ 改 `application.yaml` 公共配置 | 容易带环境信息，影响测试/正式 |
| ❌ 给新协议写 `Thread.sleep` 同步阻塞 | Vert.x 上下文下会卡死 event loop |
| ❌ 改 `_sql/mysql/system.sql` 已有结构 | 已有环境不会自动重跑 |
| ❌ 新建类/表/文件不带 `y` / `Y` 前缀 | 违反项目强约定，见 §0 |
| ❌ 把已存在的类/表改名加 `y` 前缀 | 会破坏 yudao 继承与线上表，**只对新建生效** |

---

## 11. 关键文件快速索引

| 关注点 | 路径 |
|---|---|
| Spring Boot 入口 | `server/src/main/java/com/enjoyiot/server/ServerApplication.java` |
| 公共配置 | `server/src/main/resources/application.yaml` |
| 测试/正式差异 | `application-test.yml` ↔ `application-prod.yml` |
| IoT 组件开关 | `server/src/main/resources/application-eiot.yaml` |
| 组件 SPI 注册 | `module-eiot/iot-components/*/src/main/resources/META-INF/services/...` |
| 部署脚本 | `_script/shell/deploy*.sh` |
| 中间件 | `_script/docker/docker-compose*.yml` |
| 数据库迁移 | `_sql/migrations/` |
| 文档 | `docs/` |
| 日志清洗工具 | `_script/python/` |

---

## 12. 给 AI Agent 的协作约定

- **新建任何文件（Java 类 / 前端文件 / SQL 建表）**：一律 `y` / `Y` 开头（见 §0）。这是**强制**约定。
- **改 Java 代码前**：先看是否在 `framework/`（会被全局复用）— 是的话更要谨慎。
- **改 SQL 必看**：是否有对应 `mysql` + `postgresql` 两个版本；增量必须落在 `_sql/migrations/`，**不要改** `system.sql`。
- **改配置必看**：是否只影响单一 profile；如影响所有环境，**标注**。
- **加新依赖**：写到 `dependencies/dependencies/pom.xml`（BOM），再在具体模块 `pom.xml` 引用，**不要**直接在业务模块写版本号。
- **修改前端**：`enjoy-web/` 默认用 pnpm；产出目录 `dist-test/`、`dist-prod/`，由部署脚本 rsync 到 `/shihe_back/enjoy-iot/enjoy-web/`。
- **部署相关**：`deploy*.sh` 中的 SSH 参数、远端路径、备份目录是**约定**；调整前先确认。
- **不要做的事**：
  - 不主动 `git commit`（除非显式要求）
  - 不直接 push
  - 不删除 `log/`（仅清内容）
  - 不修改 `_script/shell/id_ed25519` 内容
  - 不动 `target/`、`.flattened-pom.xml`
- **沟通**：回答前先看 §1~§6；不确定环境（local/test/prod）就问清楚再下手。
