CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_profile (
    user_id BIGINT PRIMARY KEY,
    nickname VARCHAR(64),
    avatar_url VARCHAR(255),
    preferred_genres JSON,
    preferred_scenes JSON,
    timezone VARCHAR(64),
    CONSTRAINT fk_profile_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS user_behavior_summary (
    user_id BIGINT PRIMARY KEY,
    play_count_30d INT NOT NULL DEFAULT 0,
    like_count_30d INT NOT NULL DEFAULT 0,
    skip_rate_30d DECIMAL(5,2) NOT NULL DEFAULT 0,
    active_days_30d INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_summary_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS tracks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    mcp_track_id VARCHAR(128) UNIQUE,
    title VARCHAR(255) NOT NULL,
    artist VARCHAR(255) NOT NULL,
    album VARCHAR(255),
    genre VARCHAR(64),
    release_year INT,
    duration_sec INT,
    audio_url VARCHAR(255),
    metadata_json JSON
);

CREATE TABLE IF NOT EXISTS listening_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    track_id BIGINT NOT NULL,
    played_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    play_duration_sec INT,
    completed_flag TINYINT(1) NOT NULL DEFAULT 0,
    source_type VARCHAR(32),
    INDEX idx_history_user_played_at(user_id, played_at DESC),
    CONSTRAINT fk_history_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_history_track FOREIGN KEY (track_id) REFERENCES tracks(id)
);

CREATE TABLE IF NOT EXISTS user_track_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    track_id BIGINT NOT NULL,
    rating INT,
    liked_flag TINYINT(1),
    skipped_flag TINYINT(1),
    feedback_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_feedback_user_time(user_id, feedback_at DESC),
    CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_feedback_track FOREIGN KEY (track_id) REFERENCES tracks(id)
);

CREATE TABLE IF NOT EXISTS recommendation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    scene VARCHAR(64),
    emotion VARCHAR(64),
    hybrid_weight JSON,
    strategy_version VARCHAR(32),
    result_track_ids JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_reco_user_time(user_id, created_at DESC),
    CONSTRAINT fk_reco_user FOREIGN KEY (user_id) REFERENCES users(id)
);
