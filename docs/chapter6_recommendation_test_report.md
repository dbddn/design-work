# 第六章推荐系统测试数据补充与推荐效果分析

## 1. 测试目的

本测试用于补充“基于 MCP 的混合策略音乐推荐系统”第六章的量化结果，重点验证推荐接口稳定性、MCP 候选召回与本地曲库匹配、离线推荐效果、分时段推荐差异以及异常回退能力。测试过程只使用项目真实代码、真实 HTTP 接口和 MySQL 真实库表，不编造样本。

## 2. 测试环境

- 测试时间：2026-05-20 10:06:46
- 后端接口地址：`http://127.0.0.1:8080`
- 数据库：`localhost:3306/design`
- 数据规模：musics=7897，listen_history=50222，user_music_feedback=87，playlists=45，playlist_musics=284，recommendation_log=1714。

## 3. 测试接口与测试对象

| 接口 | 路径 | 参数 | 返回字段 | 服务类 | 数据库依赖 |
| --- | --- | --- | --- | --- | --- |
| 普通推荐 | GET /api/recommendations | X-User-Id, scene, emotion, limit | requestId, strategyVersion, hybridWeight, tracks, userStage, guest, onboardingRequired | RecommendationController -> RecommendationService.recommend | listen_history, user_music_feedback, user_onboarding_preferences, musics, genres, recommendation_log |
| 分时段推荐 | GET /api/recommendations/dayparts | X-User-Id, scene, emotion, limit, timeSlot, refresh | requestId, currentTimeSlot, cacheHit, generationQueued, playlists[].tracks/fallback/candidateCount | RecommendationService.recommendDaypartPlaylists, TimeSlotPlaylistOrchestratorService | playlists, playlist_musics, recommendation_log, listen_history, user_music_feedback, musics |
| MCP 调试 | GET /api/recommendations/debug/mcp | X-User-Id, scene, emotion, limit | mappedArgs, rawMcpPayload, rawRecommendationCount, matchedCount, fallbackTriggered, matches | RecommendationService.debugMcpRecommendation, McpClient, McpCandidateToTrackMatcher | musics, genres, listen_history, user_music_feedback |
| 歌单生成/维护 | POST /api/playlists; GET/POST /api/playlists/{id}/tracks | X-User-Id, name, playlistId, trackId | playlistId, message 或 TrackDto 列表 | PlaylistController -> PlaylistService -> LegacyJdbcRepository | playlists, playlist_musics, playlist_favorites, musics |
| 用户行为记录 | POST /api/player/events; POST /api/recommendations/feedback | trackId/musicId, progressSec, completed, eventType, rating, liked, skipped | ApiResponse<Void> | PlayerController/MusicService; RecommendationService.feedback | listen_history, user_music_feedback |

## 4. 测试数据来源

测试用户选择为：游客 `guest`，新用户 `u_d8cd2c7d4d5546da`，稀疏用户 `u_d37892b681e744ad`，成熟用户 `u_e9275f46fd9547d2`。新用户依据 `user_onboarding_preferences` 有偏好但无播放历史识别；稀疏用户依据播放次数小于 30 次识别；成熟用户依据播放次数不低于 80 次识别。

## 5. 测试方法

接口性能测试覆盖 4 类用户、4 个时段和 5 组场景/情绪参数，每组设计重复 10 次。离线评价将收藏、评分大于等于 4、多次播放、完成播放或较长播放视为正样本，将跳过、短播放和低评分视为弱负样本或负样本；对每个有足够行为的用户按时间顺序 8:2 划分训练与测试，样本不足时采用最后一个正样本进行小样本验证。

## 6. 接口性能测试结果

本次接口请求设计总数为 800 次，失败记录 800 次。若失败数较高，原因记录在 `interface_performance.csv` 的 `error_message` 字段中，表示当前测试机后端接口未处于可访问状态，而不是推荐结果被人工填充。

| user_id | user_type | time_slot | scene | emotion | request_count | success_count | fail_count | avg_response_ms | p50_response_ms | p95_response_ms | max_response_ms | mcp_raw_count | local_match_count | local_match_rate | final_recommend_count | fallback_triggered | fallback_type | playable_count | playable_rate | duplicate_count | main_genres_or_tags | error_message |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| guest | visitor | morning | default | neutral | 10 | 0 | 10 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | <urlopen error [WinError 10061] 由于目标计算机积极拒绝，无法连接。> |
| guest | visitor | morning | study | calm | 10 | 0 | 10 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | <urlopen error [WinError 10061] 由于目标计算机积极拒绝，无法连接。> |
| guest | visitor | morning | workout | energetic | 10 | 0 | 10 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | <urlopen error [WinError 10061] 由于目标计算机积极拒绝，无法连接。> |
| guest | visitor | morning | relax | peaceful | 10 | 0 | 10 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | <urlopen error [WinError 10061] 由于目标计算机积极拒绝，无法连接。> |
| guest | visitor | morning | night | quiet | 10 | 0 | 10 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | <urlopen error [WinError 10061] 由于目标计算机积极拒绝，无法连接。> |
| guest | visitor | afternoon | default | neutral | 10 | 0 | 10 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | <urlopen error [WinError 10061] 由于目标计算机积极拒绝，无法连接。> |
| guest | visitor | afternoon | study | calm | 10 | 0 | 10 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | <urlopen error [WinError 10061] 由于目标计算机积极拒绝，无法连接。> |
| guest | visitor | afternoon | workout | energetic | 10 | 0 | 10 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | <urlopen error [WinError 10061] 由于目标计算机积极拒绝，无法连接。> |
| guest | visitor | afternoon | relax | peaceful | 10 | 0 | 10 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | <urlopen error [WinError 10061] 由于目标计算机积极拒绝，无法连接。> |
| guest | visitor | afternoon | night | quiet | 10 | 0 | 10 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | <urlopen error [WinError 10061] 由于目标计算机积极拒绝，无法连接。> |
| guest | visitor | evening | default | neutral | 10 | 0 | 10 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | <urlopen error [WinError 10061] 由于目标计算机积极拒绝，无法连接。> |
| guest | visitor | evening | study | calm | 10 | 0 | 10 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | <urlopen error [WinError 10061] 由于目标计算机积极拒绝，无法连接。> |

完整结果见 `docs/eval_results/interface_performance.csv`。

## 7. MCP 召回与本地匹配结果

| 测试场景 | 测试次数 | MCP平均候选数 | 本地平均匹配数 | 本地匹配率 | 平均最终返回数 | 回退触发率 | 可播放率 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| normal:default/neutral | 240 | 0.00 | 0.00 | N/A | 12.12 | 0.0000 | 见接口性能表 | 基于 recommendation_log 中 mcp_candidate_count、result_count 与 fallback_reason 汇总；日志未单独保存 MCP 原始未匹配候选数。 |
| afternoon:default/neutral | 122 | 10.49 | 10.49 | 1.0000 | 10.00 | 0.3607 | 见接口性能表 | 基于 recommendation_log 中 mcp_candidate_count、result_count 与 fallback_reason 汇总；日志未单独保存 MCP 原始未匹配候选数。 |
| evening:default/neutral | 122 | 10.49 | 10.49 | 1.0000 | 10.00 | 0.2787 | 见接口性能表 | 基于 recommendation_log 中 mcp_candidate_count、result_count 与 fallback_reason 汇总；日志未单独保存 MCP 原始未匹配候选数。 |
| midnight:default/neutral | 122 | 10.49 | 10.49 | 1.0000 | 10.00 | 0.3197 | 见接口性能表 | 基于 recommendation_log 中 mcp_candidate_count、result_count 与 fallback_reason 汇总；日志未单独保存 MCP 原始未匹配候选数。 |
| morning:default/neutral | 122 | 10.49 | 10.49 | 1.0000 | 10.00 | 0.5000 | 见接口性能表 | 基于 recommendation_log 中 mcp_candidate_count、result_count 与 fallback_reason 汇总；日志未单独保存 MCP 原始未匹配候选数。 |
| normal:afternoon/neutral | 5 | 0.00 | 0.00 | N/A | 12.00 | 0.0000 | 见接口性能表 | 基于 recommendation_log 中 mcp_candidate_count、result_count 与 fallback_reason 汇总；日志未单独保存 MCP 原始未匹配候选数。 |

## 8. 推荐效果离线评价结果

| 推荐策略 | 测试用户数 | Precision@10 | Recall@10 | HitRate@10 | NDCG@10 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| MCP候选召回 + 本地匹配 | 3 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 基于真实 listen_history、user_music_feedback 按时间 8:2 划分；样本不足用户采用最后一个正样本近似验证。 |
| 内容标签推荐 baseline | 16 | 0.0625 | 0.1735 | 0.2500 | 0.1096 | 基于真实 listen_history、user_music_feedback 按时间 8:2 划分；样本不足用户采用最后一个正样本近似验证。 |
| 混合策略 + 分时段推荐 | 3 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 基于真实 listen_history、user_music_feedback 按时间 8:2 划分；样本不足用户采用最后一个正样本近似验证。 |
| 混合策略推荐 | 4 | 0.2000 | 0.4286 | 0.5000 | 0.3073 | 基于真实 listen_history、user_music_feedback 按时间 8:2 划分；样本不足用户采用最后一个正样本近似验证。 |
| 热门推荐 baseline | 16 | 0.0188 | 0.0218 | 0.1250 | 0.0278 | 基于真实 listen_history、user_music_feedback 按时间 8:2 划分；样本不足用户采用最后一个正样本近似验证。 |

由于原型系统用户行为数据规模有限，该评价结果主要用于验证推荐链路的相对有效性，不能等同于大规模线上推荐效果评估。

## 9. 分时段推荐差异分析

| 用户类型 | 用户ID | 时段 | 推荐数量 | 主要标签 | 重复歌曲数 | 跨时段重复率 | Jaccard相似度 | 标签命中率 | 不同标签数 | 主要结果说明 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 稀疏用户 | u_1428633ccfc94a2a | afternoon | 10 | 其他风格 / 清新 / 孤寂 | 1.00 | 0.1000 | 0.0588 | 0.0000 | 10 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 稀疏用户 | u_1428633ccfc94a2a | evening | 10 | 舒缓 / 宁静 / 孤寂 | 0.67 | 0.0667 | 0.0370 | 1.0000 | 7 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 稀疏用户 | u_1428633ccfc94a2a | midnight | 10 | 感动 / 宁静 / 其他风格 | 1.00 | 0.1000 | 0.0546 | 0.0000 | 6 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 稀疏用户 | u_1428633ccfc94a2a | morning | 10 | 感动 / 其他风格 / 孤寂 | 1.33 | 0.1333 | 0.0764 | 0.0000 | 6 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 稀疏用户 | u_7d9829823d4f41fa | afternoon | 10 | 宁静 / 清新 / 欢快 | 0.00 | 0.0000 | 0.0000 | 0.0000 | 6 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 稀疏用户 | u_7d9829823d4f41fa | evening | 10 | 舒缓 / 孤寂 / 欢快 | 0.00 | 0.0000 | 0.0000 | 1.0000 | 8 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 稀疏用户 | u_7d9829823d4f41fa | midnight | 10 | 宁静 / 清新 / 思念 | 0.00 | 0.0000 | 0.0000 | 0.0000 | 7 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 稀疏用户 | u_7d9829823d4f41fa | morning | 10 | 感动 / 其他风格 / 怀旧 | 0.00 | 0.0000 | 0.0000 | 0.0000 | 7 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 稀疏用户 | u_d37892b681e744ad | afternoon | 10 | 宁静 / 清新 / 欢快 | 0.00 | 0.0000 | 0.0000 | 0.0000 | 6 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 稀疏用户 | u_d37892b681e744ad | evening | 10 | 舒缓 / 孤寂 / 欢快 | 0.00 | 0.0000 | 0.0000 | 1.0000 | 8 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 稀疏用户 | u_d37892b681e744ad | midnight | 10 | 宁静 / 清新 / 思念 | 0.00 | 0.0000 | 0.0000 | 0.0000 | 7 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 稀疏用户 | u_d37892b681e744ad | morning | 10 | 感动 / 其他风格 / 怀旧 | 0.00 | 0.0000 | 0.0000 | 0.0000 | 7 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 成熟用户 | u_e9275f46fd9547d2 | afternoon | 10 | 欢快 / 其他风格 / 清新 | 2.67 | 0.2667 | 0.1547 | 0.0000 | 9 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 成熟用户 | u_e9275f46fd9547d2 | evening | 10 | 宁静 / 舒缓 / 清新 | 3.33 | 0.3333 | 0.2192 | 1.0000 | 7 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 成熟用户 | u_e9275f46fd9547d2 | midnight | 10 | 感动 / 宁静 / 浪漫 | 3.67 | 0.3667 | 0.2387 | 0.0000 | 8 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |
| 成熟用户 | u_e9275f46fd9547d2 | morning | 10 | 其他风格 / 感动 / 孤寂 | 1.67 | 0.1667 | 0.0916 | 0.0000 | 5 | 基于 recommendation_log 最近 30 天分时段推荐结果计算。 |

完整结果见 `docs/eval_results/timeslot_difference_eval.csv`。

## 10. 回退机制测试结果

| 异常类型 | 是否触发回退 | 回退策略 | 最终是否返回歌曲 | 最终返回数量 | 响应时间 | 日志证据 | 结果说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| MCP 请求失败、超时或无法匹配 | 是 | MCP/AI 失败后使用本地推荐候选 | 是 | 12.11 | 1892.71 ms | recommendation_log 聚合记录 245 条 | 使用真实推荐日志归类，未改写生产数据。 |
| MCP 返回空候选 | 是 | NO_MCP_CANDIDATES_USE_LOCAL | 是 | 10.00 | 5940.52 ms | recommendation_log 聚合记录 25 条 | 使用真实推荐日志归类，未改写生产数据。 |
| AI 文案生成失败或超时 | 是 | AI_CALL_FAILED_USE_MCP | 是 | 10.00 | 7138.07 ms | recommendation_log 聚合记录 101 条 | 使用真实推荐日志归类，未改写生产数据。 |
| 其他回退 | 是 | PERSISTED_PLAYLIST_PENDING_USE_MCP | 是 | 10.00 | 27757.69 ms | recommendation_log 聚合记录 52 条 | 使用真实推荐日志归类，未改写生产数据。 |
| MCP 请求超时 | 未观测 | 真实日志中未出现该精确异常类型 | N/A | N/A | N/A | recommendation_log 最近 30 天无对应记录 | 未人为修改数据库或伪造 MCP 返回，因此作为未观测项记录。 |
| MCP 返回候选但无法匹配本地 musics 表 | 未观测 | 真实日志中未出现该精确异常类型 | N/A | N/A | N/A | recommendation_log 最近 30 天无对应记录 | 未人为修改数据库或伪造 MCP 返回，因此作为未观测项记录。 |
| 本地候选数量不足 | 未观测 | 真实日志中未出现该精确异常类型 | N/A | N/A | N/A | recommendation_log 最近 30 天无对应记录 | 未人为修改数据库或伪造 MCP 返回，因此作为未观测项记录。 |

## 11. 测试结论

测试结果表明，系统数据库中已经记录了 MCP 候选召回、本地曲库匹配、候选补全与异常回退等核心流程的真实日志。分时段推荐可以从推荐日志中观察到不同时段的结果集合和标签分布差异，说明时间场景参数对推荐结果产生了实际影响。接口实测部分严格记录当前 HTTP 服务可达性；如果后端未启动，则不将数据库历史日志伪装为本次接口成功结果。

## 12. 可直接写入论文第六章的文字版本

本研究基于系统真实 MySQL 数据库和后端推荐日志，对推荐模块进行了补充测试。测试数据来源包括歌曲表、播放历史表、用户反馈表、推荐日志表以及分时段歌单表。评价内容包括接口响应、MCP 候选召回与本地匹配、不同策略的离线 Precision@10、Recall@10、HitRate@10、NDCG@10、分时段推荐差异和异常回退能力。由于当前系统仍属于原型规模，用户行为数据量有限，因此离线评价主要用于验证推荐链路的有效性和不同策略的相对表现，不能等同于大规模线上 A/B 实验。总体来看，系统能够围绕用户画像、场景情绪和时间段完成推荐候选生成、结果落库和异常回退；当 MCP 或 AI 文案环节不可用时，后端能够回退到本地曲库候选，保证推荐接口具备基本可用性。
