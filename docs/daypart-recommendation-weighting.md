# 分时段歌单推荐权重与排序流程说明

## 1. 数据来源

分时段歌单推荐不让大模型直接凭空选歌，而是先由后端生成候选歌曲，再让大模型在候选范围内排序并生成文案。

系统当前使用两类核心偏好信号：

- 内容过滤信号 content：来自所有用户在当前时间段的群体偏好。后端从 `listen_history` 按播放时间划分上午、下午、晚上、深夜，统计每个时段的高频风格和歌手。
- 协同过滤信号 collaborative：来自当前用户自己的听歌偏好。后端统计该用户的近期播放、收藏、评分、跳过、常听风格、常听歌手、常听专辑和近期锚点歌曲。

这两类信号会同时进入 MCP 候选召回和分时段大模型排序提示词。

## 2. 用户阶段与权重

后端按照用户最近 30 天播放量划分用户阶段：

| 用户阶段 | 判定条件 | 推荐策略 | 权重示例 |
| --- | --- | --- | --- |
| 新用户 NEW_USER | `playCount30d = 0` | 当前用户历史不足，主要依赖全体用户在该时段的偏好、场景和少量热门兜底 | popular 0.20, content 0.55, scene 0.20, collaborative 0.05 |
| 稀疏用户 SPARSE_USER | `0 < playCount30d < 30` | 已有少量个人行为，但仍以时段群体偏好和场景为主，适度加入个人偏好 | popular 0.14, content 0.42, scene 0.22, collaborative 0.22 |
| 成长用户 GROWING_USER | `30 <= playCount30d < 80` | 群体偏好和个人偏好平衡，开始提高协同过滤占比 | content 0.26, scene 0.20 左右, collaborative 0.30-0.36 |
| 成熟用户 MATURE_USER | `playCount30d >= 80` | 用户画像较稳定，优先贴近当前用户自己的历史偏好 | content 0.18, scene 0.20 左右, collaborative 0.40-0.50 |

如果用户跳过率较高，系统会降低探索比例，并提高评分门槛，避免继续推荐用户可能不喜欢的歌曲。

## 3. 完整调用链路

1. 前端请求 `/api/recommendations/dayparts`，可传入 `timeSlot` 和 `refresh`。
2. `RecommendationService` 判断当前时段，读取缓存歌单。
3. 如果缓存完整且四个时段无重复歌曲，直接返回缓存。
4. 如果未命中缓存，先生成快速候选视图，同时通过单线程队列后台生成完整歌单。
5. `DaypartPlaylistSnapshotService` 构造用户上下文：
   - 当前用户高频风格、歌手、专辑、近期锚点。
   - 当前用户播放量、跳过率、反馈数量、正负反馈数量。
   - 全体用户在四个时段的高频风格和歌手。
6. `HybridWeightStrategyService` 根据新老用户阶段计算 `popular/content/scene/collaborative` 权重。
7. `McpQueryMappingService` 把权重和偏好映射为 MCP 参数：
   - `genres` 优先合并当前时段的全体用户偏好、当前用户偏好和时段默认风格。
   - `similarTo` 使用当前用户近期锚点歌曲。
   - `minRating` 根据时段和用户成熟度动态调整。
   - `hybridWeight` 根据用户阶段、反馈数量、跳过率计算。
8. MCP 先调用 `get_recommendations`，必要时调用 `search_library`。
9. MCP 外部候选必须映射回本地 `musics` 表中可播放歌曲。
10. 候选不足时，从本地同风格、热门或个人偏好相近歌曲补齐。
11. 后端先做多样性排序，限制同一歌手、专辑、流派过度集中。
12. 分时段专用大模型只接收候选歌曲、权重、群体偏好和个人偏好，在候选内输出：
    - `orderedTrackIds`
    - `playlistTitle`
    - `playlistSubtitle`
    - `playlistReason`
    - `tags`
    - `explanations`
13. 后端只接受候选列表中真实存在的 `orderedTrackIds`，不会使用大模型新增的歌曲。
14. 完整歌单持久化到数据库，供当天或有效期内复用。

## 4. 大模型排序规则

分时段大模型不是歌曲召回层，只做候选内排序和文案增强。

传给大模型的核心信息包括：

- 当前时段目标，例如上午偏清醒启动，深夜偏低刺激沉浸。
- 候选歌曲列表及真实本地 `musicId`。
- 权重配置：`popular/content/scene/collaborative/explorationRatio`。
- 全体用户在当前时段的高频风格和歌手。
- 当前用户自己的高频风格、歌手、专辑、收藏、播放和反馈数据。

大模型必须按以下规则排序：

1. 先匹配当前时段目标。
2. 再参考 content，也就是全体用户在该时段的偏好。
3. 再参考 collaborative，也就是当前用户自己的历史偏好。
4. 新用户更重视 content，成熟用户更重视 collaborative。
5. 避免同一歌手、同一专辑、同一流派过度集中。
6. 不允许输出候选之外的歌曲。

## 5. 示例

假设用户 `doubendoudn` 当前没有听歌记录，但系统统计到所有用户在上午常听：

- 群体时段风格：清新、流行、电子
- 群体时段歌手：A、B、C

同时 `doubendoudn` 的问卷偏好是：

- 个人初始偏好：清新、治愈、宁静

由于该用户是新用户，权重会更偏向 content：

```json
{
  "popular": 0.20,
  "content": 0.55,
  "scene": 0.20,
  "collaborative": 0.05
}
```

后端会先用“上午时段 + 群体上午偏好 + 用户初始偏好”召回候选歌曲。大模型不会新增歌曲，只会在候选内选择更适合上午启动、清新、低负担的顺序，并输出类似：

```json
{
  "playlistTitle": "清醒启动: 晨间提神歌单",
  "playlistSubtitle": "适合通勤和学习前的轻快开场",
  "playlistReason": "这组歌曲优先匹配上午群体偏好的清新与明亮氛围，同时保留用户初始偏好的治愈感，适合用较轻的节奏进入一天。",
  "tags": ["清新", "提神", "上午"],
  "orderedTrackIds": [101, 205, 318],
  "explanations": {
    "101": "节奏明亮，适合上午启动。",
    "205": "氛围清新，贴近用户初始偏好。"
  }
}
```

当用户后续听歌数据增加，例如最近 30 天播放超过 80 首，系统会逐步提高 collaborative 权重，让歌单更贴近该用户自己的常听歌手、专辑和评分反馈。

## 6. 关键代码位置

- 权重计算：`backend/src/main/java/com/music/reco/recommendation/strategy/HybridWeightStrategyService.java`
- 用户上下文构造：`backend/src/main/java/com/music/reco/recommendation/service/RecommendationService.java`
- 分时段缓存与持久化：`backend/src/main/java/com/music/reco/recommendation/service/DaypartPlaylistSnapshotService.java`
- MCP 参数映射：`backend/src/main/java/com/music/reco/recommendation/service/McpQueryMappingService.java`
- MCP 候选召回、本地匹配、多样性排序、大模型排序提示：`backend/src/main/java/com/music/reco/recommendation/service/TimeSlotPlaylistOrchestratorService.java`
- 全体用户分时段偏好统计：`backend/src/main/java/com/music/reco/legacy/LegacyJdbcRepository.java`
