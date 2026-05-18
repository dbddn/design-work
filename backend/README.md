# reco-backend

Spring Boot 后端基础架构（MCP 混合策略音乐推荐原型）。

## 已完成基础骨架
- 分层结构：`auth / user / music / recommendation / mcp / analytics / common / config`
- 基础能力：
  - 统一响应 `ApiResponse`
  - 全局异常处理 `GlobalExceptionHandler`
  - Flyway 初始化脚本（核心表）
  - MCP 客户端适配（`get_recommendations/search_library/get_listening_history`）
  - 推荐主链路（策略生成 -> MCP 调用 -> 标准化 -> 日志落库）
  - 用户、搜索、播放行为、推荐、统计等核心接口骨架
- 工程能力：
  - Swagger: `/swagger-ui.html`
  - Actuator: `/actuator/health`

## 目录（关键）
- `src/main/java/com/music/reco/config`：安全、OpenAPI、配置属性
- `src/main/java/com/music/reco/common`：通用响应与异常
- `src/main/java/com/music/reco/mcp`：MCP 客户端、DTO、标准化
- `src/main/java/com/music/reco/recommendation`：推荐编排与策略
- `src/main/resources/db/migration`：数据库迁移

## 运行前准备
1. 安装 JDK 17+
2. 安装 Maven 3.9+
3. 准备 MySQL 8 和 Redis（或调整配置）
4. 配置环境变量（可选）：
   - `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD`
   - `REDIS_HOST/REDIS_PORT`
   - `MCP_BASE_URL`
   - `JWT_SECRET`

## 本地启动
```bash
cd backend
mvn spring-boot:run
```

## 下一步建议（进入业务实现）
1. 接入真实 JWT Filter（替代当前原型级放行配置）
2. 落地 UserProfile/UserBehaviorSummary 实体与真实统计聚合
3. 完善推荐重排（多样性约束、黑名单过滤、场景加权）
4. 增加 MCP schema 校验与更精细降级策略
5. 补齐单元测试与集成测试
