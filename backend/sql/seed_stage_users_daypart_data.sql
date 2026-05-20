-- Seed four demo users into four recommendation stages.
-- Checked current data before writing this script:
-- 110717: 3 listens, 0 feedback
-- doubendoudn: 0 listens, 0 feedback
-- 12345: 9 listens, 0 feedback
-- 200422: 0 listens, 0 feedback
--
-- Stage mapping:
-- doubendoudn -> NEW_USER_WITH_ONBOARDING, 0 listens
-- 110717      -> SPARSE_USER, 18 listens
-- 12345       -> GROWING_USER, 55 listens
-- 200422      -> MATURE_USER, 120 listens

START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS tmp_stage_users;
CREATE TEMPORARY TABLE tmp_stage_users AS
SELECT user_id, username
FROM users
WHERE username COLLATE utf8mb4_unicode_ci IN ('110717', 'doubendoudn', '12345', '200422');

-- Remove old behavior rows for these four users so their stages are deterministic.
DELETE uf
FROM user_music_feedback uf
JOIN tmp_stage_users tu ON tu.user_id COLLATE utf8mb4_unicode_ci = uf.user_id COLLATE utf8mb4_unicode_ci;

DELETE lh
FROM listen_history lh
JOIN tmp_stage_users tu ON tu.user_id COLLATE utf8mb4_unicode_ci = lh.user_id COLLATE utf8mb4_unicode_ci;

-- Soft-disable generated daypart playlists so the next request regenerates them.
UPDATE playlist_musics pm
JOIN playlists p ON p.id = pm.playlist_id
JOIN tmp_stage_users tu ON tu.user_id COLLATE utf8mb4_unicode_ci = p.user_id COLLATE utf8mb4_unicode_ci
SET pm.is_deleted = 1
WHERE p.scene_tag LIKE 'daypart:%';

UPDATE playlists p
JOIN tmp_stage_users tu ON tu.user_id COLLATE utf8mb4_unicode_ci = p.user_id COLLATE utf8mb4_unicode_ci
SET p.is_active = 0,
    p.updated_at = NOW()
WHERE p.scene_tag LIKE 'daypart:%';

-- Onboarding preferences keep the zero-listen user out of the onboarding-blocked state.
INSERT INTO user_onboarding_preferences (user_id, selected_genres, source)
SELECT user_id,
       CASE username
           WHEN 'doubendoudn' THEN JSON_ARRAY(
               (SELECT description FROM genres WHERE id = 1),
               (SELECT description FROM genres WHERE id = 2),
               (SELECT description FROM genres WHERE id = 8)
           )
           WHEN '110717' THEN JSON_ARRAY(
               (SELECT description FROM genres WHERE id = 2),
               (SELECT description FROM genres WHERE id = 3),
               (SELECT description FROM genres WHERE id = 1)
           )
           WHEN '12345' THEN JSON_ARRAY(
               (SELECT description FROM genres WHERE id = 3),
               (SELECT description FROM genres WHERE id = 9),
               (SELECT description FROM genres WHERE id = 10)
           )
           WHEN '200422' THEN JSON_ARRAY(
               (SELECT description FROM genres WHERE id = 7),
               (SELECT description FROM genres WHERE id = 6),
               (SELECT description FROM genres WHERE id = 11),
               (SELECT description FROM genres WHERE id = 8)
           )
       END,
       'stage_seed'
FROM tmp_stage_users
ON DUPLICATE KEY UPDATE
    selected_genres = VALUES(selected_genres),
    source = VALUES(source);

UPDATE users u
JOIN user_onboarding_preferences pref ON pref.user_id COLLATE utf8mb4_unicode_ci = u.user_id COLLATE utf8mb4_unicode_ci
JOIN tmp_stage_users tu ON tu.user_id COLLATE utf8mb4_unicode_ci = u.user_id COLLATE utf8mb4_unicode_ci
SET u.preferred_genres = pref.selected_genres,
    u.updated_at = NOW();

DROP TEMPORARY TABLE IF EXISTS tmp_stage_plan;
CREATE TEMPORARY TABLE tmp_stage_plan (
    username VARCHAR(50) NOT NULL,
    stage_label VARCHAR(32) NOT NULL,
    total_count INT NOT NULL,
    primary_genre_id BIGINT NOT NULL,
    secondary_genre_id BIGINT NOT NULL,
    tertiary_genre_id BIGINT NOT NULL,
    primary_slot VARCHAR(32) NOT NULL,
    secondary_slot VARCHAR(32) NOT NULL,
    skip_every INT NOT NULL,
    rating_base INT NOT NULL
);

INSERT INTO tmp_stage_plan
    (username, stage_label, total_count, primary_genre_id, secondary_genre_id, tertiary_genre_id,
     primary_slot, secondary_slot, skip_every, rating_base)
VALUES
    ('doubendoudn', 'NEW_USER_WITH_ONBOARDING', 0, 1, 2, 8, 'morning', 'afternoon', 0, 4),
    ('110717', 'SPARSE_USER', 18, 2, 3, 1, 'morning', 'afternoon', 9, 4),
    ('12345', 'GROWING_USER', 55, 3, 9, 10, 'afternoon', 'evening', 7, 4),
    ('200422', 'MATURE_USER', 120, 7, 6, 11, 'evening', 'midnight', 11, 5);

DROP TEMPORARY TABLE IF EXISTS tmp_numbers;
CREATE TEMPORARY TABLE tmp_numbers (n INT PRIMARY KEY);

INSERT INTO tmp_numbers (n)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 120
)
SELECT n FROM seq;

DROP TEMPORARY TABLE IF EXISTS tmp_music_pool;
CREATE TEMPORARY TABLE tmp_music_pool AS
SELECT genre_id, music_id, rn
FROM (
    SELECT m.genre_id,
           m.id AS music_id,
           ROW_NUMBER() OVER (PARTITION BY m.genre_id ORDER BY COALESCE(m.play_count, 0) DESC, COALESCE(m.rate, 0) DESC, m.id DESC) AS rn
    FROM musics m
    WHERE COALESCE(m.is_active, 1) = 1
      AND m.genre_id IN (1, 2, 3, 6, 7, 8, 9, 10, 11)
) ranked
WHERE rn <= 180;

INSERT INTO listen_history (
    record_id, user_id, music_id, genre_id, genre_name, start_time, ms_played,
    reason_start, reason_end, shuffle, skipped, time_slot, hour, played_at,
    completed_flag, source_type, scene, emotion, device_type, created_at
)
SELECT UUID(),
       tu.user_id,
       CAST(pool.music_id AS CHAR),
       CAST(pool.genre_id AS SIGNED),
       g.description,
       MAKETIME(
           CASE
               WHEN derived.slot_key = 'morning' THEN 8 + (num.n % 3)
               WHEN derived.slot_key = 'afternoon' THEN 13 + (num.n % 4)
               WHEN derived.slot_key = 'evening' THEN 19 + (num.n % 3)
               ELSE 0 + (num.n % 5)
           END,
           (num.n * 7) % 60,
           0
       ),
       CASE WHEN derived.should_skip = 1 THEN 45000 ELSE 190000 + ((num.n % 5) * 15000) END,
       'stage_seed',
       CASE WHEN derived.should_skip = 1 THEN 'skip' ELSE 'track_done' END,
       CASE WHEN num.n % 5 = 0 THEN 1 ELSE 0 END,
       derived.should_skip,
       derived.slot_key,
       CASE
           WHEN derived.slot_key = 'morning' THEN 8 + (num.n % 3)
           WHEN derived.slot_key = 'afternoon' THEN 13 + (num.n % 4)
           WHEN derived.slot_key = 'evening' THEN 19 + (num.n % 3)
           ELSE 0 + (num.n % 5)
       END,
       DATE_SUB(NOW(), INTERVAL num.n HOUR),
       CASE WHEN derived.should_skip = 1 THEN 0 ELSE 1 END,
       'stage_seed',
       CASE
           WHEN derived.slot_key = 'morning' THEN 'study'
           WHEN derived.slot_key = 'afternoon' THEN 'default'
           WHEN derived.slot_key = 'evening' THEN 'commute'
           ELSE 'sleep'
       END,
       CASE
           WHEN derived.slot_key = 'morning' THEN 'energetic'
           WHEN derived.slot_key = 'afternoon' THEN 'focus'
           WHEN derived.slot_key = 'evening' THEN 'neutral'
           ELSE 'sad'
       END,
       CASE WHEN num.n % 2 = 0 THEN 'desktop' ELSE 'mobile' END,
       NOW()
FROM tmp_stage_plan plan
JOIN tmp_stage_users tu ON tu.username = plan.username
JOIN tmp_numbers num ON num.n <= plan.total_count
JOIN LATERAL (
    SELECT CASE
        WHEN num.n % 10 IN (1, 2, 3, 4, 5, 6) THEN plan.primary_slot
        WHEN num.n % 10 IN (7, 8) THEN plan.secondary_slot
        WHEN num.n % 10 = 9 THEN 'morning'
        ELSE 'midnight'
    END AS slot_key,
    CASE WHEN plan.skip_every > 0 AND num.n % plan.skip_every = 0 THEN 1 ELSE 0 END AS should_skip,
    CASE
        WHEN num.n % 10 IN (1, 2, 3, 4, 5) THEN plan.primary_genre_id
        WHEN num.n % 10 IN (6, 7, 8) THEN plan.secondary_genre_id
        ELSE plan.tertiary_genre_id
    END AS selected_genre_id
) AS derived ON TRUE
JOIN tmp_music_pool pool
  ON pool.genre_id = derived.selected_genre_id
 AND pool.rn = 1 + ((num.n - 1) % 120)
JOIN genres g ON g.id = pool.genre_id;

INSERT INTO user_music_feedback (
    user_id, music_id, recommendation_log_id, rating, liked_flag, skipped_flag, feedback_source, feedback_at
)
SELECT tu.user_id,
       CAST(lh.music_id AS UNSIGNED),
       NULL,
       CASE
           WHEN lh.skipped = 1 THEN 2
           WHEN plan.stage_label = 'MATURE_USER' THEN 5
           WHEN lh.n % 6 = 0 THEN 3
           ELSE plan.rating_base
       END,
       CASE WHEN lh.skipped = 0 AND lh.n % 4 <> 0 THEN 1 ELSE 0 END,
       lh.skipped,
       'stage_seed',
       DATE_ADD(lh.played_at, INTERVAL 1 MINUTE)
FROM tmp_stage_plan plan
JOIN tmp_stage_users tu ON tu.username = plan.username
JOIN (
    SELECT lh.*,
           ROW_NUMBER() OVER (PARTITION BY lh.user_id ORDER BY lh.played_at DESC) AS n
    FROM listen_history lh
    WHERE lh.source_type = 'stage_seed'
) lh ON lh.user_id COLLATE utf8mb4_unicode_ci = tu.user_id COLLATE utf8mb4_unicode_ci
WHERE plan.total_count > 0
  AND (
      (plan.stage_label = 'SPARSE_USER' AND lh.n <= 8)
      OR (plan.stage_label = 'GROWING_USER' AND lh.n <= 22)
      OR (plan.stage_label = 'MATURE_USER' AND lh.n <= 45)
  );

SELECT u.username,
       COUNT(DISTINCT lh.record_id) AS listen_count,
       COUNT(DISTINCT CASE WHEN lh.skipped = 1 THEN lh.record_id END) AS skip_count,
       COUNT(DISTINCT uf.id) AS feedback_count
FROM tmp_stage_users u
LEFT JOIN listen_history lh ON lh.user_id COLLATE utf8mb4_unicode_ci = u.user_id COLLATE utf8mb4_unicode_ci
LEFT JOIN user_music_feedback uf ON uf.user_id COLLATE utf8mb4_unicode_ci = u.user_id COLLATE utf8mb4_unicode_ci
GROUP BY u.username
ORDER BY FIELD(u.username, 'doubendoudn', '110717', '12345', '200422');

SELECT u.username, lh.time_slot, COUNT(*) AS play_count
FROM tmp_stage_users u
JOIN listen_history lh ON lh.user_id COLLATE utf8mb4_unicode_ci = u.user_id COLLATE utf8mb4_unicode_ci
GROUP BY u.username, lh.time_slot
ORDER BY u.username, play_count DESC;

SELECT u.username, COALESCE(g.description, CONCAT('genre-', m.genre_id), 'unknown') AS genre, COUNT(*) AS play_count
FROM tmp_stage_users u
JOIN listen_history lh ON lh.user_id COLLATE utf8mb4_unicode_ci = u.user_id COLLATE utf8mb4_unicode_ci
JOIN musics m ON CAST(m.id AS CHAR) = CAST(lh.music_id AS CHAR)
LEFT JOIN genres g ON g.id = m.genre_id
GROUP BY u.username, genre
ORDER BY u.username, play_count DESC;

COMMIT;
