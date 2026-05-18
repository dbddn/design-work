/*
 Navicat Premium Dump SQL

 Source Server         : datebase
 Source Server Type    : MySQL
 Source Server Version : 80041 (8.0.41)
 Source Host           : localhost:3306
 Source Schema         : design

 Target Server Type    : MySQL
 Target Server Version : 80041 (8.0.41)
 File Encoding         : 65001

 Date: 14/04/2026 15:31:49
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for artists
-- ----------------------------
DROP TABLE IF EXISTS `artists`;
CREATE TABLE `artists`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '歌手主键',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '歌手名',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '歌手简介',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_artists_name`(`name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 33875812 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '歌手表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for chart_snapshot_items
-- ----------------------------
DROP TABLE IF EXISTS `chart_snapshot_items`;
CREATE TABLE `chart_snapshot_items`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '榜单项主键',
  `snapshot_id` bigint NOT NULL COMMENT '榜单快照ID',
  `music_id` bigint NULL DEFAULT NULL COMMENT '歌曲ID',
  `rank_no` int NOT NULL COMMENT '榜单排名',
  `score` decimal(10, 2) NULL DEFAULT NULL COMMENT '榜单分数',
  `display_music_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '展示歌曲名',
  `display_artist_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '展示歌手名',
  `extra_json` json NULL COMMENT '扩展信息',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_chart_snapshot_items_snapshot_rank`(`snapshot_id` ASC, `rank_no` ASC) USING BTREE,
  INDEX `idx_chart_snapshot_items_music_id`(`music_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '榜单项表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for chart_snapshots
-- ----------------------------
DROP TABLE IF EXISTS `chart_snapshots`;
CREATE TABLE `chart_snapshots`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '榜单快照主键',
  `chart_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '榜单类型：HOT/NEW',
  `snapshot_date` date NOT NULL COMMENT '榜单日期',
  `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '榜单标题',
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '榜单说明',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_chart_snapshots_type_date`(`chart_type` ASC, `snapshot_date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '榜单快照表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for community_comments
-- ----------------------------
DROP TABLE IF EXISTS `community_comments`;
CREATE TABLE `community_comments`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '评论主键',
  `post_id` bigint NOT NULL COMMENT '帖子ID',
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '评论用户ID',
  `parent_comment_id` bigint NULL DEFAULT NULL COMMENT '父评论ID',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '评论内容',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_community_comments_post_time`(`post_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_community_comments_user_time`(`user_id` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '社区评论表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for community_post_likes
-- ----------------------------
DROP TABLE IF EXISTS `community_post_likes`;
CREATE TABLE `community_post_likes`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '点赞主键',
  `post_id` bigint NOT NULL COMMENT '帖子ID',
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_community_post_likes_post_user`(`post_id` ASC, `user_id` ASC) USING BTREE,
  INDEX `idx_community_post_likes_user_time`(`user_id` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '社区点赞表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for community_posts
-- ----------------------------
DROP TABLE IF EXISTS `community_posts`;
CREATE TABLE `community_posts`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '帖子主键',
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '发帖用户ID',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '帖子内容',
  `topic` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '主题标签',
  `music_id` bigint NULL DEFAULT NULL COMMENT '关联歌曲ID',
  `playlist_id` bigint NULL DEFAULT NULL COMMENT '关联歌单ID',
  `like_count` int NOT NULL DEFAULT 0 COMMENT '点赞数',
  `comment_count` int NOT NULL DEFAULT 0 COMMENT '评论数',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_community_posts_user_time`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_community_posts_status_time`(`status` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '社区帖子表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for explore_timeline_nodes
-- ----------------------------
DROP TABLE IF EXISTS `explore_timeline_nodes`;
CREATE TABLE `explore_timeline_nodes`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '时间轴节点主键',
  `year_label` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '年份标签',
  `year_sort` int NOT NULL COMMENT '排序值',
  `genre` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '流派',
  `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '标题',
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '描述',
  `highlight_color` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '高亮色',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_explore_timeline_nodes_year_label`(`year_label` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '探索时间轴表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for genres
-- ----------------------------
DROP TABLE IF EXISTS `genres`;
CREATE TABLE `genres`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '流派主键',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流派名',
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '流派说明',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `prompt_hint` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_genres_name`(`name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 14 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '流派字典表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for listen_history
-- ----------------------------
DROP TABLE IF EXISTS `listen_history`;
CREATE TABLE `listen_history`  (
  `record_id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `music_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `genre_id` int NULL DEFAULT NULL,
  `genre_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `start_time` time NULL DEFAULT NULL,
  `ms_played` int NULL DEFAULT NULL,
  `reason_start` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `reason_end` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `shuffle` tinyint(1) NULL DEFAULT NULL,
  `skipped` tinyint(1) NULL DEFAULT NULL,
  `time_slot` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `hour` int NULL DEFAULT NULL,
  `played_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '播放发生时间',
  `completed_flag` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否完整播放',
  `source_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '来源：首页/搜索/榜单/时光机',
  `scene` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '推荐场景',
  `emotion` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '推荐情绪',
  `recommendation_log_id` bigint NULL DEFAULT NULL COMMENT '关联推荐日志ID',
  `device_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '设备类型',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  PRIMARY KEY (`record_id`) USING BTREE,
  INDEX `idx_listen_history_user_time`(`user_id` ASC, `played_at` ASC) USING BTREE,
  INDEX `idx_listen_history_music_time`(`music_id` ASC, `played_at` ASC) USING BTREE,
  INDEX `idx_listen_history_slot`(`time_slot` ASC, `hour` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for music_artists
-- ----------------------------
DROP TABLE IF EXISTS `music_artists`;
CREATE TABLE `music_artists`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '歌曲歌手关系主键',
  `music_id` bigint NOT NULL COMMENT '歌曲ID',
  `artist_id` bigint NOT NULL COMMENT '歌手ID',
  `artist_role` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PRIMARY' COMMENT '歌手角色',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_music_artists_music_artist`(`music_id` ASC, `artist_id` ASC) USING BTREE,
  INDEX `idx_music_artists_artist_id`(`artist_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '歌曲歌手关系表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for music_comments
-- ----------------------------
DROP TABLE IF EXISTS `music_comments`;
CREATE TABLE `music_comments`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '评论主键',
  `music_id` bigint NOT NULL COMMENT '歌曲ID',
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户ID',
  `parent_comment_id` bigint NULL DEFAULT NULL COMMENT '父评论ID（回复用）',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '评论内容',
  `like_count` int NOT NULL DEFAULT 0 COMMENT '点赞数',
  `reply_count` int NOT NULL DEFAULT 0 COMMENT '回复数',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态（ACTIVE/DELETED）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_music_comments_music_time`(`music_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_music_comments_user_time`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_music_comments_parent`(`parent_comment_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '歌曲评论表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for musics
-- ----------------------------
DROP TABLE IF EXISTS `musics`;
CREATE TABLE `musics`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `artist_ids` json NULL,
  `artist_names` json NULL,
  `album_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `album_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `lyric` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `similar_musics_ids` json NULL,
  `similar_musics_names` json NULL,
  `playlist_ids` json NULL,
  `playlist_names` json NULL,
  `genre_id` bigint NULL DEFAULT NULL,
  `rate` decimal(3, 1) NULL DEFAULT NULL,
  `cover_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `duration` int NULL DEFAULT NULL,
  `play_count` bigint NULL DEFAULT NULL,
  `source_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'LOCAL' COMMENT '数据来源',
  `release_year` int NULL DEFAULT NULL COMMENT '发行年份',
  `language` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '语言',
  `audio_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '音频地址',
  `album_release_date` date NULL DEFAULT NULL COMMENT '专辑发行日期',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_active` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否有效',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_musics_name`(`name` ASC) USING BTREE,
  INDEX `idx_musics_genre_id`(`genre_id` ASC) USING BTREE,
  INDEX `idx_musics_play_count`(`play_count` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1412672814 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for playlist_favorites
-- ----------------------------
DROP TABLE IF EXISTS `playlist_favorites`;
CREATE TABLE `playlist_favorites`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '歌单收藏主键',
  `playlist_id` bigint NOT NULL COMMENT '歌单ID',
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户ID',
  `favorite_flag` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否已收藏',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_playlist_favorites_playlist_user`(`playlist_id` ASC, `user_id` ASC) USING BTREE,
  INDEX `idx_playlist_favorites_user_id`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '歌单收藏表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for playlist_musics
-- ----------------------------
DROP TABLE IF EXISTS `playlist_musics`;
CREATE TABLE `playlist_musics`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `music_id` bigint NULL DEFAULT NULL,
  `order_index` int NULL DEFAULT NULL,
  `playlist_id` bigint NULL DEFAULT NULL,
  `added_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入歌单时间',
  `added_by_user_id` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '添加人用户ID',
  `is_deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否软删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_playlist_musics_playlist_music`(`playlist_id` ASC, `music_id` ASC) USING BTREE,
  INDEX `idx_playlist_musics_music_id`(`music_id` ASC) USING BTREE,
  INDEX `idx_playlist_musics_playlist_order`(`playlist_id` ASC, `order_index` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for playlists
-- ----------------------------
DROP TABLE IF EXISTS `playlists`;
CREATE TABLE `playlists`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '歌单主键',
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '创建用户ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '歌单名称',
  `cover_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '歌单封面',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '歌单描述',
  `mood_tag` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '情绪标签',
  `scene_tag` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '场景标签',
  `is_public` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否公开',
  `is_active` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否有效',
  `play_count` bigint NOT NULL DEFAULT 0 COMMENT '播放次数',
  `favorite_count` bigint NOT NULL DEFAULT 0 COMMENT '收藏次数',
  `song_count` int NOT NULL DEFAULT 0 COMMENT '歌曲数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_playlists_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_playlists_scene_tag`(`scene_tag` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '歌单表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for recommendation_log
-- ----------------------------
DROP TABLE IF EXISTS `recommendation_log`;
CREATE TABLE `recommendation_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '推荐日志主键',
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户ID',
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '推荐请求ID',
  `scene` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '场景',
  `emotion` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '情绪',
  `cold_start_flag` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否冷启动',
  `exploration_ratio` decimal(6, 4) NOT NULL DEFAULT 0.0000 COMMENT '探索比例',
  `hybrid_weight` json NULL COMMENT '混合权重JSON',
  `strategy_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'v1' COMMENT '策略版本',
  `result_music_ids` json NULL COMMENT '推荐结果歌曲ID列表',
  `result_count` int NOT NULL DEFAULT 0 COMMENT '返回歌曲数',
  `latency_ms` int NULL DEFAULT NULL COMMENT '推荐耗时',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `time_slot` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `ai_refined_flag` tinyint(1) NOT NULL DEFAULT 0,
  `ai_success_flag` tinyint(1) NOT NULL DEFAULT 0,
  `mcp_candidate_count` int NOT NULL DEFAULT 0,
  `ai_final_count` int NOT NULL DEFAULT 0,
  `fallback_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_recommendation_log_user_time`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_recommendation_log_scene_emotion`(`scene` ASC, `emotion` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 485 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '推荐日志表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for time_machine_nodes
-- ----------------------------
DROP TABLE IF EXISTS `time_machine_nodes`;
CREATE TABLE `time_machine_nodes`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '时光机节点主键',
  `period_label` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '年代标签',
  `period_sort` int NOT NULL COMMENT '排序值',
  `genre` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流派',
  `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '标题',
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '描述',
  `cover_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '封面图',
  `default_music_id` bigint NULL DEFAULT NULL COMMENT '默认歌曲ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_time_machine_nodes_period_genre`(`period_label` ASC, `genre` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '时光机节点表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user_behavior_summary
-- ----------------------------
DROP TABLE IF EXISTS `user_behavior_summary`;
CREATE TABLE `user_behavior_summary`  (
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户ID',
  `play_count_7d` int NOT NULL DEFAULT 0 COMMENT '近7天播放次数',
  `play_count_30d` int NOT NULL DEFAULT 0 COMMENT '近30天播放次数',
  `like_count_30d` int NOT NULL DEFAULT 0 COMMENT '近30天喜欢次数',
  `skip_count_30d` int NOT NULL DEFAULT 0 COMMENT '近30天跳过次数',
  `skip_rate_30d` decimal(6, 4) NOT NULL DEFAULT 0.0000 COMMENT '近30天跳过率',
  `active_days_30d` int NOT NULL DEFAULT 0 COMMENT '近30天活跃天数',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`) USING BTREE,
  INDEX `idx_user_behavior_summary_updated_at`(`updated_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户行为汇总表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user_music_feedback
-- ----------------------------
DROP TABLE IF EXISTS `user_music_feedback`;
CREATE TABLE `user_music_feedback`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户歌曲反馈主键',
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户ID',
  `music_id` bigint NOT NULL COMMENT '歌曲ID',
  `recommendation_log_id` bigint NULL DEFAULT NULL COMMENT '关联推荐日志',
  `rating` int NULL DEFAULT NULL COMMENT '评分',
  `liked_flag` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否喜欢',
  `skipped_flag` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否跳过',
  `feedback_source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '反馈来源',
  `feedback_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '反馈时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_music_feedback_user_time`(`user_id` ASC, `feedback_at` ASC) USING BTREE,
  INDEX `idx_user_music_feedback_music_time`(`music_id` ASC, `feedback_at` ASC) USING BTREE,
  INDEX `idx_user_music_feedback_recommendation_log_id`(`recommendation_log_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户歌曲反馈表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user_onboarding_preferences
-- ----------------------------
DROP TABLE IF EXISTS `user_onboarding_preferences`;
CREATE TABLE `user_onboarding_preferences`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `selected_genres` json NOT NULL,
  `source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'manual_select',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_onboarding_preferences_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_user_onboarding_preferences_updated_at`(`updated_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for users
-- ----------------------------
DROP TABLE IF EXISTS `users`;
CREATE TABLE `users`  (
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `gender` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `age_range` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `income_range` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `province` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `avatar_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '头像地址',
  `bio` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '个人简介',
  `timezone` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '时区',
  `preferred_genres` json NULL COMMENT '偏好流派',
  `preferred_scenes` json NULL COMMENT '偏好场景',
  `last_login_at` datetime NULL DEFAULT NULL COMMENT '最后登录时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `email` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '登录邮箱',
  `password_hash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '密码哈希',
  `role` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'USER' COMMENT '角色',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  PRIMARY KEY (`user_id`) USING BTREE,
  INDEX `idx_users_username`(`username` ASC) USING BTREE,
  INDEX `idx_users_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
