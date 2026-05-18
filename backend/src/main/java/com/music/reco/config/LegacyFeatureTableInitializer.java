package com.music.reco.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class LegacyFeatureTableInitializer {

    private static final String COLUMN_EXISTS_SQL = """
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = ?
              AND column_name = ?
            """;

    @Bean
    ApplicationRunner initLegacyFeatureTables(JdbcTemplate jdbcTemplate) {
        return args -> {
            jdbcTemplate.execute(
                    """
                    CREATE TABLE IF NOT EXISTS community_posts (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      user_id VARCHAR(50) NOT NULL,
                      content TEXT NOT NULL,
                      topic VARCHAR(64) NULL,
                      music_id BIGINT NULL,
                      playlist_id BIGINT NULL,
                      like_count INT NOT NULL DEFAULT 0,
                      comment_count INT NOT NULL DEFAULT 0,
                      status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      KEY idx_community_posts_user_time (user_id, created_at),
                      KEY idx_community_posts_status_time (status, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """
            );

            jdbcTemplate.execute(
                    """
                    CREATE TABLE IF NOT EXISTS community_comments (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      post_id BIGINT NOT NULL,
                      user_id VARCHAR(50) NOT NULL,
                      parent_comment_id BIGINT NULL,
                      content TEXT NOT NULL,
                      status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      KEY idx_community_comments_post_time (post_id, created_at),
                      KEY idx_community_comments_user_time (user_id, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """
            );

            jdbcTemplate.execute(
                    """
                    CREATE TABLE IF NOT EXISTS community_post_likes (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      post_id BIGINT NOT NULL,
                      user_id VARCHAR(50) NOT NULL,
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      UNIQUE KEY uk_community_post_likes_post_user (post_id, user_id),
                      KEY idx_community_post_likes_user_time (user_id, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """
            );

            jdbcTemplate.execute(
                    """
                    CREATE TABLE IF NOT EXISTS user_music_feedback (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      user_id VARCHAR(50) NOT NULL,
                      music_id BIGINT NOT NULL,
                      recommendation_log_id BIGINT NULL,
                      rating INT NULL,
                      liked_flag TINYINT(1) NOT NULL DEFAULT 0,
                      skipped_flag TINYINT(1) NOT NULL DEFAULT 0,
                      feedback_source VARCHAR(32) NULL,
                      feedback_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      KEY idx_user_music_feedback_user_time (user_id, feedback_at),
                      KEY idx_user_music_feedback_music_time (music_id, feedback_at),
                      KEY idx_user_music_feedback_recommendation_log_id (recommendation_log_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """
            );

            jdbcTemplate.execute(
                    """
                    CREATE TABLE IF NOT EXISTS user_onboarding_preferences (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      user_id VARCHAR(50) NOT NULL,
                      selected_genres JSON NOT NULL,
                      source VARCHAR(32) NOT NULL DEFAULT 'manual_select',
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      UNIQUE KEY uk_user_onboarding_preferences_user (user_id),
                      KEY idx_user_onboarding_preferences_updated_at (updated_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """
            );

            jdbcTemplate.execute(
                    """
                    CREATE TABLE IF NOT EXISTS recommendation_log (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      user_id VARCHAR(50) NOT NULL,
                      request_id VARCHAR(64) NULL,
                      scene VARCHAR(64) NULL,
                      emotion VARCHAR(64) NULL,
                      cold_start_flag TINYINT(1) NOT NULL DEFAULT 0,
                      exploration_ratio DECIMAL(6,4) NOT NULL DEFAULT 0.0000,
                      hybrid_weight JSON NULL,
                      strategy_version VARCHAR(32) NOT NULL DEFAULT 'v1',
                      result_music_ids JSON NULL,
                      result_count INT NOT NULL DEFAULT 0,
                      latency_ms INT NULL,
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      KEY idx_recommendation_log_user_time (user_id, created_at),
                      KEY idx_recommendation_log_scene_emotion (scene, emotion, created_at),
                      KEY idx_recommendation_log_request_id (request_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """
            );

            addColumnIfMissing(jdbcTemplate, "recommendation_log", "time_slot",
                    "ALTER TABLE recommendation_log ADD COLUMN time_slot VARCHAR(32) NULL");
            addColumnIfMissing(jdbcTemplate, "recommendation_log", "ai_refined_flag",
                    "ALTER TABLE recommendation_log ADD COLUMN ai_refined_flag TINYINT(1) NOT NULL DEFAULT 0");
            addColumnIfMissing(jdbcTemplate, "recommendation_log", "ai_success_flag",
                    "ALTER TABLE recommendation_log ADD COLUMN ai_success_flag TINYINT(1) NOT NULL DEFAULT 0");
            addColumnIfMissing(jdbcTemplate, "recommendation_log", "mcp_candidate_count",
                    "ALTER TABLE recommendation_log ADD COLUMN mcp_candidate_count INT NOT NULL DEFAULT 0");
            addColumnIfMissing(jdbcTemplate, "recommendation_log", "ai_final_count",
                    "ALTER TABLE recommendation_log ADD COLUMN ai_final_count INT NOT NULL DEFAULT 0");
            addColumnIfMissing(jdbcTemplate, "recommendation_log", "fallback_reason",
                    "ALTER TABLE recommendation_log ADD COLUMN fallback_reason VARCHAR(255) NULL");
        };
    }

    private void addColumnIfMissing(JdbcTemplate jdbcTemplate, String tableName, String columnName, String alterSql) {
        Integer count = jdbcTemplate.queryForObject(COLUMN_EXISTS_SQL, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(alterSql);
        }
    }
}
