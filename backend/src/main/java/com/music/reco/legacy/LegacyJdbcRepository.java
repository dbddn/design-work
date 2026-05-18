package com.music.reco.legacy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.analytics.dto.GenreDistributionDto;
import com.music.reco.auth.dto.AuthUserRecord;
import com.music.reco.analytics.dto.HeatmapPointDto;
import com.music.reco.common.exception.BusinessException;
import com.music.reco.common.exception.ErrorCode;
import com.music.reco.music.dto.ChartItemDto;
import com.music.reco.music.dto.CommunityCommentDto;
import com.music.reco.music.dto.CommunityPostDto;
import com.music.reco.music.dto.FavoriteTrackDto;
import com.music.reco.music.dto.HistoryItemDto;
import com.music.reco.music.dto.MusicCommentDto;
import com.music.reco.music.dto.ArtistDetailDto;
import com.music.reco.music.dto.PlaylistSummaryDto;
import com.music.reco.music.dto.ArtistSummaryDto;
import com.music.reco.music.dto.TimeMachineNodeDto;
import com.music.reco.music.dto.TimelineNodeDto;
import com.music.reco.music.dto.TrackDto;
import com.music.reco.music.service.LocalSongAssetService;
import com.music.reco.music.service.NeteaseApiTrackFallbackService;
import com.music.reco.user.dto.UserProfileResponse;
import com.music.reco.user.dto.UserStatsResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Time;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class LegacyJdbcRepository {
    private static final DateTimeFormatter PLAYLIST_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final LocalSongAssetService localSongAssetService;
    private final NeteaseApiTrackFallbackService neteaseApiTrackFallbackService;

    public LegacyJdbcRepository(JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper,
                                LocalSongAssetService localSongAssetService,
                                NeteaseApiTrackFallbackService neteaseApiTrackFallbackService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.localSongAssetService = localSongAssetService;
        this.neteaseApiTrackFallbackService = neteaseApiTrackFallbackService;
    }

    public Optional<UserProfileResponse> findUserProfile(String userId) {
        List<UserProfileResponse> rows = jdbcTemplate.query(
                """
                SELECT user_id, username, email, timezone, gender, age_range, province, avatar_url, bio, preferred_genres
                FROM users
                WHERE user_id = ?
                """,
                (rs, rowNum) -> new UserProfileResponse(
                        rs.getString("user_id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("username"),
                        firstString(rs.getString("timezone"), "Asia/Shanghai"),
                        rs.getString("gender"),
                        rs.getString("age_range"),
                        rs.getString("province"),
                        rs.getString("avatar_url"),
                        rs.getString("bio"),
                        readStringList(rs.getString("preferred_genres"))
                ),
                userId
        );
        return rows.stream().findFirst();
    }

    public Optional<UserProfileResponse> findUserByUsername(String username) {
        List<UserProfileResponse> rows = jdbcTemplate.query(
                """
                SELECT user_id, username, email, timezone, gender, age_range, province, avatar_url, bio, preferred_genres
                FROM users
                WHERE username = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new UserProfileResponse(
                        rs.getString("user_id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("username"),
                        firstString(rs.getString("timezone"), "Asia/Shanghai"),
                        rs.getString("gender"),
                        rs.getString("age_range"),
                        rs.getString("province"),
                        rs.getString("avatar_url"),
                        rs.getString("bio"),
                        readStringList(rs.getString("preferred_genres"))
                ),
                username
        );
        return rows.stream().findFirst();
    }

    public Optional<AuthUserRecord> findAuthUserByUsername(String username) {
        List<AuthUserRecord> rows = jdbcTemplate.query(
                """
                SELECT user_id, username, email, password_hash, status
                FROM users
                WHERE username = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new AuthUserRecord(
                        rs.getString("user_id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("status")
                ),
                username
        );
        return rows.stream().findFirst();
    }

    public boolean existsUserByEmail(String email) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM users WHERE email = ?",
                Integer.class,
                email
        );
        return count != null && count > 0;
    }

    public String createUser(String username, String email, String passwordHash) {
        String userId = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update(
                """
                INSERT INTO users (user_id, username, email, password_hash, role, status, timezone, created_at, updated_at, last_login_at)
                VALUES (?, ?, ?, ?, 'USER', 'ACTIVE', 'Asia/Shanghai', NOW(), NOW(), NOW())
                """,
                userId,
                username,
                email,
                passwordHash
        );
        return userId;
    }

    public void updateLastLoginAt(String userId) {
        jdbcTemplate.update("UPDATE users SET last_login_at = NOW(), updated_at = NOW() WHERE user_id = ?", userId);
    }

    public List<TrackDto> searchTracks(String keyword, int page, int size) {
        String safeKeyword = keyword == null ? "" : keyword.trim();
        int offset = Math.max(0, page) * Math.max(1, size);
        return jdbcTemplate.query(
                """
                SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description, m.lyric,
                       COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                       COALESCE(m.play_count, 0) AS play_count
                FROM musics m
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE COALESCE(m.is_active, 1) = 1
                  AND (
                    m.name LIKE ?
                    OR COALESCE(m.album_name, '') LIKE ?
                    OR COALESCE(m.artist_names, '') LIKE ?
                    OR EXISTS (
                        SELECT 1
                        FROM music_artists ma
                        JOIN artists a ON a.id = ma.artist_id
                        WHERE ma.music_id = m.id
                          AND a.name LIKE ?
                    )
                  )
                ORDER BY m.play_count DESC, m.id DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> toTrackDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("artist_names"),
                        rs.getString("album_name"),
                        rs.getString("cover_url"),
                        rs.getObject("duration"),
                        rs.getString("description"),
                        rs.getString("lyric"),
                        rs.getString("genre_name"),
                        rs.getLong("play_count")
                ),
                "%" + safeKeyword + "%",
                "%" + safeKeyword + "%",
                "%" + safeKeyword + "%",
                "%" + safeKeyword + "%",
                size,
                offset
        );
    }

    public long countTracks(String keyword) {
        String safeKeyword = keyword == null ? "" : keyword.trim();
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1)
                FROM musics m
                WHERE COALESCE(m.is_active, 1) = 1
                  AND (
                    m.name LIKE ?
                    OR COALESCE(m.album_name, '') LIKE ?
                    OR COALESCE(m.artist_names, '') LIKE ?
                    OR EXISTS (
                        SELECT 1
                        FROM music_artists ma
                        JOIN artists a ON a.id = ma.artist_id
                        WHERE ma.music_id = m.id
                          AND a.name LIKE ?
                    )
                  )
                """,
                Long.class,
                "%" + safeKeyword + "%",
                "%" + safeKeyword + "%",
                "%" + safeKeyword + "%",
                "%" + safeKeyword + "%"
        );
        return count == null ? 0 : count;
    }

    public Optional<ArtistSummaryDto> findArtistCard(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Optional.empty();
        }

        String trimmed = keyword.trim();
        List<ArtistSummaryDto> exactRows = jdbcTemplate.query(
                """
                SELECT id, name, description
                FROM artists
                WHERE name = ?
                ORDER BY id ASC
                LIMIT 1
                """,
                (rs, rowNum) -> new ArtistSummaryDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        ""
                ),
                trimmed
        );
        if (!exactRows.isEmpty()) {
            return exactRows.stream().findFirst();
        }

        List<ArtistSummaryDto> fuzzyRows = jdbcTemplate.query(
                """
                SELECT id, name, description
                FROM artists
                WHERE name LIKE ?
                ORDER BY id ASC
                LIMIT 1
                """,
                (rs, rowNum) -> new ArtistSummaryDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        ""
                ),
                "%" + trimmed + "%"
        );
        return fuzzyRows.stream().findFirst();
    }

    public ArtistDetailDto getArtistDetail(Long artistId, int page, int size) {
        Map<String, Object> artist = jdbcTemplate.queryForMap(
                """
                SELECT id, name, description
                FROM artists
                WHERE id = ?
                """,
                artistId
        );

        String artistName = artist.get("name") == null ? "" : String.valueOf(artist.get("name"));
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);
        int offset = safePage * safeSize;

        List<TrackDto> tracks = jdbcTemplate.query(
                """
                SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description, m.lyric,
                       COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                       COALESCE(m.play_count, 0) AS play_count
                FROM musics m
                JOIN music_artists ma ON ma.music_id = m.id
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE ma.artist_id = ?
                  AND COALESCE(m.is_active, 1) = 1
                ORDER BY COALESCE(m.play_count, 0) DESC, m.id DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> toTrackDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("artist_names"),
                        rs.getString("album_name"),
                        rs.getString("cover_url"),
                        rs.getObject("duration"),
                        rs.getString("description"),
                        rs.getString("lyric"),
                        rs.getString("genre_name"),
                        rs.getLong("play_count")
                ),
                artistId,
                safeSize + 1,
                offset
        );

        if (tracks.isEmpty() && !artistName.isBlank()) {
            tracks = jdbcTemplate.query(
                    """
                    SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description, m.lyric,
                           COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                           COALESCE(m.play_count, 0) AS play_count
                    FROM musics m
                    LEFT JOIN genres g ON m.genre_id = g.id
                    WHERE COALESCE(m.is_active, 1) = 1
                      AND (
                        (
                            m.artist_ids IS NOT NULL AND JSON_VALID(m.artist_ids) AND (
                                JSON_CONTAINS(m.artist_ids, CAST(? AS JSON), '$')
                                OR JSON_CONTAINS(m.artist_ids, CAST(? AS JSON), '$')
                            )
                        )
                        OR COALESCE(m.artist_names, '') LIKE ?
                      )
                    ORDER BY COALESCE(m.play_count, 0) DESC, m.id DESC
                    LIMIT ? OFFSET ?
                    """,
                    (rs, rowNum) -> toTrackDto(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("artist_names"),
                            rs.getString("album_name"),
                            rs.getString("cover_url"),
                            rs.getObject("duration"),
                            rs.getString("description"),
                            rs.getString("lyric"),
                            rs.getString("genre_name"),
                            rs.getLong("play_count")
                    ),
                    String.valueOf(artistId.longValue()),
                    "\"" + artistId + "\"",
                    "%" + artistName + "%",
                    safeSize + 1,
                    offset
            );
        }

        boolean hasMore = tracks.size() > safeSize;
        List<TrackDto> visibleTracks = hasMore ? tracks.subList(0, safeSize) : tracks;

        return new ArtistDetailDto(
                ((Number) artist.get("id")).longValue(),
                artistName,
                artist.get("description") == null ? "" : String.valueOf(artist.get("description")),
                "",
                safePage,
                safeSize,
                hasMore,
                visibleTracks
        );
    }

    public Optional<TrackDto> findTrack(Long id) {
        List<TrackDto> rows = jdbcTemplate.query(
                """
                SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description, m.lyric,
                       COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                       COALESCE(m.play_count, 0) AS play_count
                FROM musics m
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE m.id = ?
                """,
                (rs, rowNum) -> toTrackDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("artist_names"),
                        rs.getString("album_name"),
                        rs.getString("cover_url"),
                        rs.getObject("duration"),
                        rs.getString("description"),
                        rs.getString("lyric"),
                        rs.getString("genre_name"),
                        rs.getLong("play_count")
                ),
                id
        );
        return rows.stream().findFirst();
    }

    public Long findOrCreateMusicFromToplist(Long neteaseTrackId,
                                             String title,
                                             String artist,
                                             String album,
                                             String coverUrl,
                                             Integer durationSec,
                                             Integer releaseYear) {
        String safeTitle = title == null || title.isBlank() ? "未命名歌曲" : title.trim();
        String safeArtist = artist == null || artist.isBlank() ? "未知歌手" : artist.trim();
        String safeAlbum = album == null ? "" : album.trim();

        List<Long> existing = jdbcTemplate.query(
                """
                SELECT m.id
                FROM musics m
                WHERE m.name = ?
                  AND (
                        COALESCE(m.artist_names, '') LIKE ?
                        OR COALESCE(m.artist_names, '') = ?
                      )
                ORDER BY COALESCE(m.play_count, 0) DESC, m.id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getLong("id"),
                safeTitle,
                "%" + safeArtist + "%",
                safeArtist
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        String artistNamesJson;
        try {
            artistNamesJson = objectMapper.writeValueAsString(List.of(safeArtist));
        } catch (Exception ignored) {
            artistNamesJson = "[\"" + safeArtist.replace("\"", "") + "\"]";
        }

        String description = "榜单同步入库：" + safeTitle + " - " + safeArtist;
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO musics
                    (name, artist_names, album_name, cover_url, duration, source_type, release_year, audio_url, description, play_count, is_active)
                    VALUES (?, ?, ?, ?, ?, 'NETEASE_TOPLIST', ?, '', ?, 0, 1)
                    """,
                    safeTitle,
                    artistNamesJson,
                    safeAlbum,
                    coverUrl,
                    durationSec,
                    releaseYear,
                    description
            );
        } catch (DataAccessException firstError) {
            jdbcTemplate.update(
                    """
                    INSERT INTO musics
                    (name, artist_names, album_name, cover_url, duration, description, play_count)
                    VALUES (?, ?, ?, ?, ?, ?, 0)
                    """,
                    safeTitle,
                    artistNamesJson,
                    safeAlbum,
                    coverUrl,
                    durationSec,
                    description
            );
        }

        Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return newId == null ? -1L : newId;
    }

    public Optional<TrackDto> findTrackByArtistAndAlbum(String artist, String album) {
        if (album == null || album.isBlank()) {
            return Optional.empty();
        }

        List<TrackDto> rows = jdbcTemplate.query(
                """
                SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description, m.lyric,
                       COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                       COALESCE(m.play_count, 0) AS play_count
                FROM musics m
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE LOWER(COALESCE(m.album_name, '')) = LOWER(?)
                ORDER BY m.play_count DESC, m.id DESC
                """,
                (rs, rowNum) -> toTrackDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("artist_names"),
                        rs.getString("album_name"),
                        rs.getString("cover_url"),
                        rs.getObject("duration"),
                        rs.getString("description"),
                        rs.getString("lyric"),
                        rs.getString("genre_name"),
                        rs.getLong("play_count")
                ),
                album.trim()
        );

        String normalizedArtist = normalizeSearchToken(artist);
        if (normalizedArtist.isBlank()) {
            return rows.stream().findFirst();
        }

        return rows.stream()
                .filter(track -> normalizeSearchToken(track.artist()).contains(normalizedArtist)
                        || normalizedArtist.contains(normalizeSearchToken(track.artist())))
                .findFirst()
                .or(() -> rows.stream().findFirst());
    }

    public List<TrackDto> fuzzyFindTracksByArtistAndAlbum(String artist, String album, int limit) {
        int safeLimit = Math.max(1, limit);
        if ((artist == null || artist.isBlank()) && (album == null || album.isBlank())) {
            return List.of();
        }

        String artistKeyword = "%" + (artist == null ? "" : artist.trim()) + "%";
        String albumKeyword = "%" + (album == null ? "" : album.trim()) + "%";

        return jdbcTemplate.query(
                """
                SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description, m.lyric,
                       COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                       COALESCE(m.play_count, 0) AS play_count
                FROM musics m
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE COALESCE(m.artist_names, '') LIKE ? OR COALESCE(m.album_name, '') LIKE ?
                ORDER BY m.play_count DESC, m.id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> toTrackDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("artist_names"),
                        rs.getString("album_name"),
                        rs.getString("cover_url"),
                        rs.getObject("duration"),
                        rs.getString("description"),
                        rs.getString("lyric"),
                        rs.getString("genre_name"),
                        rs.getLong("play_count")
                ),
                artistKeyword,
                albumKeyword,
                safeLimit
        );
    }

    public List<TrackDto> findTopTracksByAlbum(String album, int limit) {
        int safeLimit = Math.max(1, limit);
        if (album == null || album.isBlank()) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description, m.lyric,
                       COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                       COALESCE(m.play_count, 0) AS play_count
                FROM musics m
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE LOWER(COALESCE(m.album_name, '')) = LOWER(?)
                ORDER BY m.play_count DESC, m.id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> toTrackDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("artist_names"),
                        rs.getString("album_name"),
                        rs.getString("cover_url"),
                        rs.getObject("duration"),
                        rs.getString("description"),
                        rs.getString("lyric"),
                        rs.getString("genre_name"),
                        rs.getLong("play_count")
                ),
                album.trim(),
                safeLimit
        );
    }

    public List<TrackDto> recommendTracks(String scene, String emotion, int limit) {
        String genreKeyword = switch (emotion == null ? "" : emotion.toLowerCase(Locale.ROOT)) {
            case "sad" -> "ballad";
            case "focus" -> "light";
            case "energetic" -> "elect";
            case "happy" -> "pop";
            default -> "";
        };

        if (!genreKeyword.isBlank()) {
            List<TrackDto> filtered = jdbcTemplate.query(
                    """
                    SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description, m.lyric,
                           COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                           COALESCE(m.play_count, 0) AS play_count
                    FROM musics m
                    LEFT JOIN genres g ON m.genre_id = g.id
                    WHERE LOWER(COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown')) LIKE ?
                    ORDER BY m.play_count DESC, m.rate DESC, m.id DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> toTrackDto(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("artist_names"),
                            rs.getString("album_name"),
                            rs.getString("cover_url"),
                            rs.getObject("duration"),
                            rs.getString("description"),
                            rs.getString("lyric"),
                            rs.getString("genre_name"),
                            rs.getLong("play_count")
                    ),
                    "%" + genreKeyword + "%",
                    limit
            );
            if (!filtered.isEmpty()) {
                return filtered;
            }
        }

        return jdbcTemplate.query(
                """
                SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description, m.lyric,
                       COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                       COALESCE(m.play_count, 0) AS play_count
                FROM musics m
                LEFT JOIN genres g ON m.genre_id = g.id
                ORDER BY m.play_count DESC, m.rate DESC, m.id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> toTrackDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("artist_names"),
                        rs.getString("album_name"),
                        rs.getString("cover_url"),
                        rs.getObject("duration"),
                        rs.getString("description"),
                        rs.getString("lyric"),
                        rs.getString("genre_name"),
                        rs.getLong("play_count")
                ),
                limit
        );
    }

    public void insertHistory(String userId, Long musicId, Integer progressSec, boolean completed, String eventType) {
        TrackDto track = findTrack(musicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "track not found"));
        String genreName = track.genre();
        Integer genreId = queryGenreIdByName(genreName);
        LocalTime now = LocalTime.now();
        jdbcTemplate.update(
                """
                INSERT INTO listen_history
                (record_id, user_id, music_id, genre_id, genre_name, start_time, ms_played, reason_start, reason_end, shuffle, skipped, time_slot, hour)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                userId,
                musicId,
                genreId,
                genreName,
                Time.valueOf(now),
                progressSec == null ? null : progressSec * 1000,
                "frontend_play",
                completed ? "track_done" : "manual_event",
                false,
                !completed && "skip".equalsIgnoreCase(eventType),
                resolveTimeSlot(now.getHour()),
                now.getHour()
        );
    }

    public List<HistoryItemDto> history(String userId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT h.record_id, h.music_id, h.played_at, h.ms_played, h.skipped, h.source_type,
                       m.name, m.artist_names, m.album_name, m.cover_url,
                       COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name
                FROM listen_history h
                LEFT JOIN musics m ON CAST(h.music_id AS CHAR) = CAST(m.id AS CHAR)
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE h.user_id = ?
                ORDER BY h.played_at DESC, h.record_id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    return new HistoryItemDto(
                            Math.abs((long) rs.getString("record_id").hashCode()),
                            rs.getLong("music_id"),
                            rs.getString("name"),
                            firstString(rs.getString("artist_names"), ""),
                            rs.getString("album_name"),
                            rs.getString("cover_url"),
                            rs.getString("genre_name"),
                            rs.getTimestamp("played_at") == null ? Instant.now() : rs.getTimestamp("played_at").toInstant(),
                            rs.getInt("ms_played") > 0 ? rs.getInt("ms_played") / 1000 : null,
                            !rs.getBoolean("skipped"),
                            firstString(rs.getString("source_type"), "legacy_db")
                    );
                },
                userId,
                limit
        );
    }

    public UserStatsResponse buildUserStats(String userId) {
        Integer playCount30d = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM listen_history WHERE user_id = ?",
                Integer.class,
                userId
        );
        Integer skipCount30d = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM listen_history WHERE user_id = ? AND skipped = 1",
                Integer.class,
                userId
        );
        int total = playCount30d == null ? 0 : playCount30d;
        int skipped = skipCount30d == null ? 0 : skipCount30d;
        int playCount7d = Math.min(total, 7 + (total / 4));
        double skipRate = total == 0 ? 0 : (double) skipped / total;
        int likeCount = Math.max(0, total - skipped);
        return new UserStatsResponse(playCount7d, total, skipRate, likeCount);
    }

    public List<String> findUserTopGenreLabels(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                       COUNT(1) AS total
                FROM listen_history h
                JOIN musics m ON h.music_id = m.id
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE h.user_id = ?
                GROUP BY COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown')
                ORDER BY total DESC, MAX(m.play_count) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getString("genre_name"),
                userId,
                Math.max(1, limit)
        ).stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    public List<String> findUserTopArtistNames(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT m.artist_names, COUNT(1) AS total
                FROM listen_history h
                JOIN musics m ON h.music_id = m.id
                WHERE h.user_id = ?
                  AND COALESCE(m.artist_names, '') <> ''
                GROUP BY m.artist_names
                ORDER BY total DESC, MAX(COALESCE(m.play_count, 0)) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> firstString(rs.getString("artist_names"), ""),
                userId,
                Math.max(1, limit)
        ).stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    public List<String> findUserTopAlbums(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT m.album_name, COUNT(1) AS total
                FROM listen_history h
                JOIN musics m ON h.music_id = m.id
                WHERE h.user_id = ?
                  AND COALESCE(m.album_name, '') <> ''
                GROUP BY m.album_name
                ORDER BY total DESC, MAX(COALESCE(m.play_count, 0)) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getString("album_name"),
                userId,
                Math.max(1, limit)
        ).stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    public List<String> onboardingGenreOptions(int limit) {
        return jdbcTemplate.query(
                """
                SELECT description
                FROM genres
                WHERE COALESCE(description, '') <> ''
                ORDER BY id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getString("description"),
                Math.max(1, limit)
        ).stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    public List<String> findOnboardingGenres(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        List<String> rows = jdbcTemplate.query(
                """
                SELECT selected_genres
                FROM user_onboarding_preferences
                WHERE user_id = ?
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getString("selected_genres"),
                userId
        );
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rows.get(0), new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public void saveOnboardingGenres(String userId, List<String> genres) {
        List<String> safeGenres = genres == null ? List.of() : genres.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        try {
            String payload = objectMapper.writeValueAsString(safeGenres);
            jdbcTemplate.update(
                    """
                    INSERT INTO user_onboarding_preferences (user_id, selected_genres, source)
                    VALUES (?, ?, 'manual_select')
                    ON DUPLICATE KEY UPDATE selected_genres = VALUES(selected_genres), source = VALUES(source)
                    """,
                    userId,
                    payload
            );
            jdbcTemplate.update(
                    "UPDATE users SET preferred_genres = ?, updated_at = NOW() WHERE user_id = ?",
                    payload,
                    userId
            );
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存初始偏好失败");
        }
    }

    public void updateUserGender(String userId, String gender) {
        jdbcTemplate.update(
                "UPDATE users SET gender = ?, updated_at = NOW() WHERE user_id = ?",
                gender,
                userId
        );
    }

    public void updateUserProfile(String userId, String username, String email, String timezone, String gender,
                                  String ageRange, String province, String avatarUrl, String bio) {
        jdbcTemplate.update(
                """
                UPDATE users
                SET username = ?, email = ?, timezone = ?, gender = ?, age_range = ?, province = ?, avatar_url = ?, bio = ?, updated_at = NOW()
                WHERE user_id = ?
                """,
                username,
                email,
                timezone,
                gender,
                ageRange,
                province,
                avatarUrl,
                bio,
                userId
        );
    }

    public List<TrackDto> recommendTracksByGenres(List<String> genres, int limit) {
        List<String> safeGenres = genres == null ? List.of() : genres.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (safeGenres.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(safeGenres.size(), "?"));
        List<Object> args = new ArrayList<>(safeGenres);
        args.add(Math.max(1, limit));

        return jdbcTemplate.query(
                """
                SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description, m.lyric,
                       COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                       COALESCE(m.play_count, 0) AS play_count
                FROM musics m
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') IN (%s)
                ORDER BY m.play_count DESC, m.rate DESC, m.id DESC
                LIMIT ?
                """.formatted(placeholders),
                (rs, rowNum) -> toTrackDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("artist_names"),
                        rs.getString("album_name"),
                        rs.getString("cover_url"),
                        rs.getObject("duration"),
                        rs.getString("description"),
                        rs.getString("lyric"),
                        rs.getString("genre_name"),
                        rs.getLong("play_count")
                ),
                args.toArray()
        );
    }

    public List<String> findRecentAnchorAlbums(String userId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT m.artist_names, m.album_name, COUNT(1) AS total
                FROM listen_history h
                JOIN musics m ON h.music_id = m.id
                WHERE h.user_id = ?
                  AND COALESCE(m.album_name, '') <> ''
                GROUP BY m.artist_names, m.album_name
                ORDER BY total DESC, MAX(m.play_count) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    String artist = firstString(rs.getString("artist_names"), "");
                    String album = rs.getString("album_name");
                    if (artist == null || artist.isBlank() || album == null || album.isBlank()) {
                        return null;
                    }
                    return artist + " - " + album;
                },
                userId,
                Math.max(1, limit)
        ).stream().filter(Objects::nonNull).toList();
    }

    public List<String> topGenreLabels(int limit) {
        return jdbcTemplate.query(
                """
                SELECT COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name
                FROM musics m
                LEFT JOIN genres g ON m.genre_id = g.id
                GROUP BY COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown')
                ORDER BY COUNT(1) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getString("genre_name"),
                Math.max(1, limit)
        ).stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .toList();
    }

    public Long createPlaylist(String userId, String name) {
        jdbcTemplate.update(
                """
                INSERT INTO playlists
                (user_id, name, cover_url, description, mood_tag, scene_tag, is_public, is_active, play_count, favorite_count, song_count, created_at, updated_at)
                VALUES (?, ?, NULL, ?, ?, ?, 0, 1, 0, 0, 0, NOW(), NOW())
                """,
                userId,
                name,
                "用户自建歌单",
                "自定义",
                "personal"
        );
        Long playlistId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return playlistId == null ? -1L : playlistId;
    }

    public Long createPlaylist(String userId,
                               String name,
                               String description,
                               String moodTag,
                               String sceneTag,
                               String coverUrl) {
        jdbcTemplate.update(
                """
                INSERT INTO playlists
                (user_id, name, cover_url, description, mood_tag, scene_tag, is_public, is_active, play_count, favorite_count, song_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, 1, 0, 0, 0, NOW(), NOW())
                """,
                userId,
                name,
                coverUrl,
                description,
                moodTag,
                sceneTag
        );
        Long playlistId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return playlistId == null ? -1L : playlistId;
    }

    public Optional<Long> findPlaylistIdByUserSceneAndMood(String userId, String sceneTag, String moodTag) {
        List<Long> rows = jdbcTemplate.query(
                """
                SELECT id
                FROM playlists
                WHERE user_id = ?
                  AND scene_tag = ?
                  AND mood_tag = ?
                  AND COALESCE(is_active, 1) = 1
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getLong("id"),
                userId,
                sceneTag,
                moodTag
        );
        return rows.stream().findFirst();
    }

    public void updatePlaylistSnapshot(Long playlistId,
                                       String name,
                                       String description,
                                       String moodTag,
                                       String sceneTag,
                                       String coverUrl) {
        jdbcTemplate.update(
                """
                UPDATE playlists
                SET name = ?,
                    cover_url = ?,
                    description = ?,
                    mood_tag = ?,
                    scene_tag = ?,
                    is_active = 1,
                    updated_at = NOW()
                WHERE id = ?
                """,
                name,
                coverUrl,
                description,
                moodTag,
                sceneTag,
                playlistId
        );
    }

    public void clearPlaylistTracks(Long playlistId) {
        jdbcTemplate.update(
                "UPDATE playlist_musics SET is_deleted = 1 WHERE playlist_id = ?",
                playlistId
        );
        refreshPlaylistSongCount(playlistId);
    }

    public List<StoredPlaylistRecord> listDaypartPlaylists(String userId, String sceneTagPrefix) {
        return jdbcTemplate.query(
                """
                SELECT id, user_id, name, cover_url, description, mood_tag, scene_tag, song_count, updated_at
                FROM playlists
                WHERE user_id = ?
                  AND scene_tag = ?
                  AND COALESCE(is_active, 1) = 1
                ORDER BY updated_at DESC, id DESC
                """,
                (rs, rowNum) -> new StoredPlaylistRecord(
                        rs.getLong("id"),
                        rs.getString("user_id"),
                        rs.getString("name"),
                        rs.getString("cover_url"),
                        rs.getString("description"),
                        rs.getString("mood_tag"),
                        rs.getString("scene_tag"),
                        rs.getInt("song_count"),
                        rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()
                ),
                userId,
                sceneTagPrefix
        );
    }

    public List<TrackDto> listPlaylistTracks(Long playlistId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description, m.lyric,
                       COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                       COALESCE(m.play_count, 0) AS play_count
                FROM playlist_musics pm
                JOIN musics m ON m.id = pm.music_id
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE pm.playlist_id = ?
                  AND COALESCE(pm.is_deleted, 0) = 0
                  AND COALESCE(m.is_active, 1) = 1
                ORDER BY pm.order_index ASC, pm.id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> toTrackDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("artist_names"),
                        rs.getString("album_name"),
                        rs.getString("cover_url"),
                        rs.getObject("duration"),
                        rs.getString("description"),
                        rs.getString("lyric"),
                        rs.getString("genre_name"),
                        rs.getLong("play_count")
                ),
                playlistId,
                Math.max(1, limit)
        );
    }

    public List<String> listRecentActiveUserIds(int limit) {
        int safeLimit = Math.max(1, limit);
        Map<String, Instant> merged = new LinkedHashMap<>();

        jdbcTemplate.query(
                """
                SELECT h.user_id, MAX(h.played_at) AS latest_at
                FROM listen_history h
                WHERE h.user_id IS NOT NULL
                  AND h.user_id <> ''
                  AND h.user_id NOT LIKE 'guest%'
                GROUP BY h.user_id
                ORDER BY latest_at DESC
                LIMIT ?
                """,
                rs -> {
                    String userId = rs.getString("user_id");
                    if (!isValidWarmupUserId(userId)) {
                        return;
                    }
                    Instant latestAt = rs.getTimestamp("latest_at") == null
                            ? Instant.EPOCH
                            : rs.getTimestamp("latest_at").toInstant();
                    merged.merge(userId, latestAt, (left, right) -> left.isAfter(right) ? left : right);
                },
                safeLimit * 2
        );

        jdbcTemplate.query(
                """
                SELECT f.user_id, MAX(f.feedback_at) AS latest_at
                FROM user_music_feedback f
                WHERE f.user_id IS NOT NULL
                  AND f.user_id <> ''
                  AND f.user_id NOT LIKE 'guest%'
                GROUP BY f.user_id
                ORDER BY latest_at DESC
                LIMIT ?
                """,
                rs -> {
                    String userId = rs.getString("user_id");
                    if (!isValidWarmupUserId(userId)) {
                        return;
                    }
                    Instant latestAt = rs.getTimestamp("latest_at") == null
                            ? Instant.EPOCH
                            : rs.getTimestamp("latest_at").toInstant();
                    merged.merge(userId, latestAt, (left, right) -> left.isAfter(right) ? left : right);
                },
                safeLimit * 2
        );

        return merged.entrySet().stream()
                .sorted(Map.Entry.<String, Instant>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(safeLimit)
                .toList();
    }

    private boolean isValidWarmupUserId(String userId) {
        if (userId == null) {
            return false;
        }
        String normalized = userId.trim();
        if (normalized.isBlank()) {
            return false;
        }
        if ("-1".equals(normalized) || "0".equals(normalized) || "null".equalsIgnoreCase(normalized)) {
            return false;
        }
        return normalized.startsWith("u_");
    }

    public void addTrackToPlaylist(String userId, Long playlistId, Long trackId) {
        Integer maxOrder = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(order_index), 0) FROM playlist_musics WHERE playlist_id = ? AND COALESCE(is_deleted, 0) = 0",
                Integer.class,
                playlistId
        );
        jdbcTemplate.update(
                """
                INSERT INTO playlist_musics (music_id, order_index, playlist_id, added_by_user_id, is_deleted)
                VALUES (?, ?, ?, ?, 0)
                ON DUPLICATE KEY UPDATE
                    is_deleted = 0,
                    added_by_user_id = VALUES(added_by_user_id),
                    added_at = CURRENT_TIMESTAMP
                """,
                trackId,
                (maxOrder == null ? 0 : maxOrder) + 1,
                playlistId,
                userId
        );
        refreshPlaylistSongCount(playlistId);
    }

    public void favoritePlaylist(String userId, Long playlistId, boolean favorite) {
        jdbcTemplate.update(
                """
                INSERT INTO playlist_favorites (playlist_id, user_id, favorite_flag, created_at, updated_at)
                VALUES (?, ?, ?, NOW(), NOW())
                ON DUPLICATE KEY UPDATE favorite_flag = VALUES(favorite_flag), updated_at = NOW()
                """,
                playlistId,
                userId,
                favorite ? 1 : 0
        );
        refreshPlaylistFavoriteCount(playlistId);
    }

    public List<PlaylistSummaryDto> listCreatedPlaylists(String userId) {
        return jdbcTemplate.query(
                """
                SELECT p.id, p.name, p.description, p.cover_url, p.mood_tag, p.scene_tag,
                       COALESCE(p.song_count, 0) AS song_count,
                       COALESCE(p.favorite_count, 0) AS favorite_count
                FROM playlists p
                WHERE p.user_id = ?
                  AND COALESCE(p.is_active, 1) = 1
                ORDER BY p.updated_at DESC, p.id DESC
                """,
                (rs, rowNum) -> new PlaylistSummaryDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("cover_url"),
                        rs.getString("mood_tag"),
                        rs.getString("scene_tag"),
                        rs.getInt("song_count"),
                        rs.getLong("favorite_count"),
                        isPlaylistFavorited(rs.getLong("id"), userId),
                        true
                ),
                userId
        );
    }

    public List<PlaylistSummaryDto> listFavoritedPlaylists(String userId) {
        return jdbcTemplate.query(
                """
                SELECT p.id, p.name, p.description, p.cover_url, p.mood_tag, p.scene_tag,
                       COALESCE(p.song_count, 0) AS song_count,
                       COALESCE(p.favorite_count, 0) AS favorite_count,
                       CASE WHEN p.user_id = ? THEN 1 ELSE 0 END AS created_by_current_user
                FROM playlist_favorites pf
                JOIN playlists p ON p.id = pf.playlist_id
                WHERE pf.user_id = ?
                  AND pf.favorite_flag = 1
                  AND COALESCE(p.is_active, 1) = 1
                ORDER BY pf.updated_at DESC, pf.id DESC
                """,
                (rs, rowNum) -> new PlaylistSummaryDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("cover_url"),
                        rs.getString("mood_tag"),
                        rs.getString("scene_tag"),
                        rs.getInt("song_count"),
                        rs.getLong("favorite_count"),
                        true,
                        rs.getInt("created_by_current_user") == 1
                ),
                userId,
                userId
        );
    }

    public List<MusicCommentDto> listMusicComments(Long musicId) {
        return jdbcTemplate.query(
                """
                SELECT mc.id, mc.music_id, mc.user_id, COALESCE(u.username, mc.user_id) AS username,
                       mc.content, COALESCE(mc.like_count, 0) AS like_count, COALESCE(mc.reply_count, 0) AS reply_count,
                       mc.created_at
                FROM music_comments mc
                LEFT JOIN users u ON u.user_id = mc.user_id
                WHERE mc.music_id = ?
                  AND COALESCE(mc.status, 'ACTIVE') = 'ACTIVE'
                ORDER BY mc.created_at DESC, mc.id DESC
                """,
                (rs, rowNum) -> new MusicCommentDto(
                        rs.getLong("id"),
                        rs.getLong("music_id"),
                        rs.getString("user_id"),
                        rs.getString("username"),
                        rs.getString("content"),
                        rs.getInt("like_count"),
                        rs.getInt("reply_count"),
                        rs.getTimestamp("created_at") == null ? "" : rs.getTimestamp("created_at").toLocalDateTime().toString()
                ),
                musicId
        );
    }

    public MusicCommentDto createMusicComment(Long musicId, String userId, String content) {
        jdbcTemplate.update(
                """
                INSERT INTO music_comments (music_id, user_id, parent_comment_id, content, like_count, reply_count, status, created_at, updated_at)
                VALUES (?, ?, NULL, ?, 0, 0, 'ACTIVE', NOW(), NOW())
                """,
                musicId,
                userId,
                content
        );
        Long commentId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (commentId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "comment create failed");
        }
        return jdbcTemplate.queryForObject(
                """
                SELECT mc.id, mc.music_id, mc.user_id, COALESCE(u.username, mc.user_id) AS username,
                       mc.content, COALESCE(mc.like_count, 0) AS like_count, COALESCE(mc.reply_count, 0) AS reply_count,
                       mc.created_at
                FROM music_comments mc
                LEFT JOIN users u ON u.user_id = mc.user_id
                WHERE mc.id = ?
                """,
                (rs, rowNum) -> new MusicCommentDto(
                        rs.getLong("id"),
                        rs.getLong("music_id"),
                        rs.getString("user_id"),
                        rs.getString("username"),
                        rs.getString("content"),
                        rs.getInt("like_count"),
                        rs.getInt("reply_count"),
                        rs.getTimestamp("created_at") == null ? "" : rs.getTimestamp("created_at").toLocalDateTime().toString()
                ),
                commentId
        );
    }

    private void refreshPlaylistSongCount(Long playlistId) {
        jdbcTemplate.update(
                """
                UPDATE playlists p
                SET p.song_count = (
                    SELECT COUNT(1)
                    FROM playlist_musics pm
                    WHERE pm.playlist_id = p.id
                      AND COALESCE(pm.is_deleted, 0) = 0
                ),
                    p.updated_at = NOW()
                WHERE p.id = ?
                """,
                playlistId
        );
    }

    private void refreshPlaylistFavoriteCount(Long playlistId) {
        jdbcTemplate.update(
                """
                UPDATE playlists p
                SET p.favorite_count = (
                    SELECT COUNT(1)
                    FROM playlist_favorites pf
                    WHERE pf.playlist_id = p.id
                      AND pf.favorite_flag = 1
                ),
                    p.updated_at = NOW()
                WHERE p.id = ?
                """,
                playlistId
        );
    }

    private boolean isPlaylistFavorited(Long playlistId, String userId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1)
                FROM playlist_favorites
                WHERE playlist_id = ?
                  AND user_id = ?
                  AND favorite_flag = 1
                """,
                Integer.class,
                playlistId,
                userId
        );
        return count != null && count > 0;
    }

    public List<ChartItemDto> hotCharts(int limit) {
        return jdbcTemplate.query(
                "SELECT id, name FROM musics ORDER BY play_count DESC, rate DESC, id DESC LIMIT ?",
                (rs, rowNum) -> new ChartItemDto(rowNum + 1, rs.getString("name")),
                limit
        );
    }

    public List<ChartItemDto> newCharts(int limit) {
        return jdbcTemplate.query(
                "SELECT id, name FROM musics ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> new ChartItemDto(rowNum + 1, rs.getString("name")),
                limit
        );
    }

    public List<TimelineNodeDto> timeline() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT t.release_year_value,
                       t.genre_name,
                       COUNT(1) AS total
                FROM (
                    SELECT
                        CASE
                            WHEN m.release_year IS NULL THEN NULL
                            WHEN CAST(m.release_year AS CHAR) REGEXP '^[12][0-9]{3}$'
                                THEN CAST(CAST(m.release_year AS CHAR) AS UNSIGNED)
                            WHEN CAST(m.release_year AS CHAR) REGEXP '^[12][0-9]{3}-[0-9]{2}-[0-9]{2}'
                                THEN CAST(SUBSTRING(CAST(m.release_year AS CHAR), 1, 4) AS UNSIGNED)
                            ELSE NULL
                        END AS release_year_value,
                        COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name
                    FROM musics m
                    LEFT JOIN genres g ON m.genre_id = g.id
                    WHERE COALESCE(m.is_active, 1) = 1
                ) t
                WHERE t.release_year_value IS NOT NULL
                GROUP BY t.release_year_value, t.genre_name
                ORDER BY t.release_year_value ASC, total DESC
                """
        );
        Map<Integer, String> yearTopGenreMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Integer releaseYear = safeInteger(row.get("release_year_value"));
            if (releaseYear == null || yearTopGenreMap.containsKey(releaseYear)) {
                continue;
            }
            yearTopGenreMap.put(releaseYear, String.valueOf(row.get("genre_name")));
        }
        List<TimelineNodeDto> result = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : yearTopGenreMap.entrySet()) {
            result.add(new TimelineNodeDto(entry.getKey(), entry.getValue()));
        }
        if (result.isEmpty()) {
            result.add(new TimelineNodeDto(2000, "pop"));
        }
        return result;
    }

    public List<TimeMachineNodeDto> timeMachine() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT t.release_year_value,
                       t.genre_name,
                       COUNT(1) AS total
                FROM (
                    SELECT
                        CASE
                            WHEN m.release_year IS NULL THEN NULL
                            WHEN CAST(m.release_year AS CHAR) REGEXP '^[12][0-9]{3}$'
                                THEN CAST(CAST(m.release_year AS CHAR) AS UNSIGNED)
                            WHEN CAST(m.release_year AS CHAR) REGEXP '^[12][0-9]{3}-[0-9]{2}-[0-9]{2}'
                                THEN CAST(SUBSTRING(CAST(m.release_year AS CHAR), 1, 4) AS UNSIGNED)
                            ELSE NULL
                        END AS release_year_value,
                        COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name
                    FROM musics m
                    LEFT JOIN genres g ON m.genre_id = g.id
                    WHERE COALESCE(m.is_active, 1) = 1
                ) t
                WHERE t.release_year_value IS NOT NULL
                GROUP BY t.release_year_value, t.genre_name
                ORDER BY t.release_year_value ASC, total DESC
                """
        );
        Map<Integer, String> yearTopGenreMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Integer releaseYear = safeInteger(row.get("release_year_value"));
            if (releaseYear == null || yearTopGenreMap.containsKey(releaseYear)) {
                continue;
            }
            yearTopGenreMap.put(releaseYear, String.valueOf(row.get("genre_name")));
        }
        List<TimeMachineNodeDto> result = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : yearTopGenreMap.entrySet()) {
            result.add(new TimeMachineNodeDto(entry.getKey() + "年", entry.getValue()));
        }
        if (result.isEmpty()) {
            result.add(new TimeMachineNodeDto("2000年", "pop"));
        }
        return result;
    }

    public List<TrackDto> listTimeMachineTracks(Integer year, String genre, int limit) {
        if (year == null) {
            return List.of();
        }
        return listTimeMachineTracksByRange(year, year, genre, limit);
    }

    public List<TrackDto> listTimeMachineTracksByRange(Integer startYear, Integer endYear, String genre, int limit) {
        if (startYear == null || endYear == null) {
            return List.of();
        }
        int minYear = Math.min(startYear, endYear);
        int maxYear = Math.max(startYear, endYear);
        int safeLimit = Math.max(1, limit);
        String safeGenre = genre == null ? "" : genre.trim();
        return jdbcTemplate.query(
                """
                SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description, m.lyric,
                       COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                       COALESCE(m.play_count, 0) AS play_count
                FROM musics m
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE COALESCE(m.is_active, 1) = 1
                  AND (
                        CASE
                            WHEN m.release_year IS NULL THEN NULL
                            WHEN CAST(m.release_year AS CHAR) REGEXP '^[12][0-9]{3}$'
                                THEN CAST(CAST(m.release_year AS CHAR) AS UNSIGNED)
                            WHEN CAST(m.release_year AS CHAR) REGEXP '^[12][0-9]{3}-[0-9]{2}-[0-9]{2}'
                                THEN CAST(SUBSTRING(CAST(m.release_year AS CHAR), 1, 4) AS UNSIGNED)
                            ELSE NULL
                        END
                      ) BETWEEN ? AND ?
                  AND (? = '' OR COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') = ?)
                ORDER BY COALESCE(m.play_count, 0) DESC, m.id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> toTrackDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("artist_names"),
                        rs.getString("album_name"),
                        rs.getString("cover_url"),
                        rs.getObject("duration"),
                        rs.getString("description"),
                        rs.getString("lyric"),
                        rs.getString("genre_name"),
                        rs.getLong("play_count")
                ),
                minYear,
                maxYear,
                safeGenre,
                safeGenre,
                safeLimit
        );
    }

    public List<HeatmapPointDto> heatmap(String userId, int days) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT DATE(played_at) AS play_date, COUNT(1) AS total
                FROM listen_history
                WHERE user_id = ?
                  AND played_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                GROUP BY DATE(played_at)
                ORDER BY play_date ASC
                """,
                userId,
                Math.max(1, days)
        );
        Map<String, Integer> dayMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.get("play_date") != null) {
                dayMap.put(String.valueOf(row.get("play_date")), ((Number) row.get("total")).intValue());
            }
        }
        List<HeatmapPointDto> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < Math.max(1, days); i++) {
            LocalDate date = today.minusDays(i);
            result.add(new HeatmapPointDto(date.toString(), dayMap.getOrDefault(date.toString(), 0)));
        }
        return result;
    }

    public List<GenreDistributionDto> genreDistribution(String userId, int days) {
        return jdbcTemplate.query(
                """
                SELECT COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name, COUNT(1) AS total
                FROM listen_history h
                JOIN musics m ON CAST(h.music_id AS CHAR) = CAST(m.id AS CHAR)
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE h.user_id = ?
                  AND h.played_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                GROUP BY COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown')
                ORDER BY total DESC
                LIMIT 8
                """,
                (rs, rowNum) -> new GenreDistributionDto(rs.getString("genre_name"), rs.getInt("total")),
                userId,
                Math.max(1, days)
        );
    }

    public List<CommunityPostDto> listCommunityPosts() {
        return jdbcTemplate.query(
                """
                SELECT p.id,
                       p.user_id,
                       COALESCE(u.username, p.user_id) AS username,
                       p.content,
                       COALESCE(p.like_count, 0) AS like_count,
                       COALESCE(p.comment_count, 0) AS comment_count,
                       p.created_at
                FROM community_posts p
                LEFT JOIN users u ON BINARY u.user_id = BINARY p.user_id
                ORDER BY p.created_at DESC
                LIMIT 20
                """,
                (rs, rowNum) -> new CommunityPostDto(
                        rs.getLong("id"),
                        safeLong(rs.getString("user_id")),
                        rs.getString("username"),
                        rs.getString("content"),
                        rs.getInt("like_count"),
                        rs.getInt("comment_count"),
                        rs.getTimestamp("created_at").toInstant()
                )
        );
    }

    public CommunityPostDto createCommunityPost(String userId, String content) {
        jdbcTemplate.update("INSERT INTO community_posts (user_id, content) VALUES (?, ?)", userId, content);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Long safeId = id == null ? -1L : id;
        return jdbcTemplate.queryForObject(
                """
                SELECT p.id,
                       p.user_id,
                       COALESCE(u.username, p.user_id) AS username,
                       p.content,
                       COALESCE(p.like_count, 0) AS like_count,
                       COALESCE(p.comment_count, 0) AS comment_count,
                       p.created_at
                FROM community_posts p
                LEFT JOIN users u ON BINARY u.user_id = BINARY p.user_id
                WHERE p.id = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new CommunityPostDto(
                        rs.getLong("id"),
                        safeLong(rs.getString("user_id")),
                        rs.getString("username"),
                        rs.getString("content"),
                        rs.getInt("like_count"),
                        rs.getInt("comment_count"),
                        rs.getTimestamp("created_at") == null ? Instant.now() : rs.getTimestamp("created_at").toInstant()
                ),
                safeId
        );
    }

    public List<CommunityCommentDto> listCommunityComments(Long postId) {
        return jdbcTemplate.query(
                """
                SELECT c.id,
                       c.post_id,
                       c.user_id,
                       COALESCE(u.username, c.user_id) AS username,
                       c.content,
                       c.created_at
                FROM community_comments c
                LEFT JOIN users u ON BINARY u.user_id = BINARY c.user_id
                WHERE c.post_id = ?
                ORDER BY c.created_at ASC, c.id ASC
                LIMIT 100
                """,
                (rs, rowNum) -> new CommunityCommentDto(
                        rs.getLong("id"),
                        rs.getLong("post_id"),
                        safeLong(rs.getString("user_id")),
                        rs.getString("username"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at") == null ? Instant.now() : rs.getTimestamp("created_at").toInstant()
                ),
                postId
        );
    }

    public Map<String, Object> createComment(Long postId, String userId, String content) {
        jdbcTemplate.update("INSERT INTO community_comments (post_id, user_id, content) VALUES (?, ?, ?)", postId, userId, content);
        jdbcTemplate.update("UPDATE community_posts SET comment_count = comment_count + 1 WHERE id = ?", postId);
        return Map.of("postId", postId, "content", content);
    }

    public Map<String, Object> likePost(Long postId, String userId) {
        try {
            jdbcTemplate.update("INSERT INTO community_post_likes (post_id, user_id) VALUES (?, ?)", postId, userId);
            jdbcTemplate.update("UPDATE community_posts SET like_count = like_count + 1 WHERE id = ?", postId);
        } catch (DataAccessException error) {
            // keep idempotent behavior for repeated likes
        }
        return Map.of("postId", postId, "liked", true);
    }

    public void insertRecommendationFeedback(String userId, Long musicId, Integer rating, Boolean liked, Boolean skipped, String feedbackSource) {
        jdbcTemplate.update(
                """
                INSERT INTO user_music_feedback
                (user_id, music_id, rating, liked_flag, skipped_flag, feedback_source, feedback_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW())
                """,
                userId,
                musicId,
                rating,
                Boolean.TRUE.equals(liked),
                Boolean.TRUE.equals(skipped),
                feedbackSource
        );
    }

    public List<FavoriteTrackDto> listFavoriteTracks(String userId, int limit) {
        if (userId == null || userId.isBlank() || userId.startsWith("guest")) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT m.id, m.name, m.artist_names, m.album_name, m.cover_url, m.duration, m.description,
                       COALESCE(NULLIF(g.description, ''), CONCAT('genre-', m.genre_id), 'unknown') AS genre_name,
                       uf.rating, uf.feedback_at
                FROM user_music_feedback uf
                JOIN (
                    SELECT music_id, MAX(id) AS latest_id
                    FROM user_music_feedback
                    WHERE user_id = ?
                    GROUP BY music_id
                ) latest ON latest.latest_id = uf.id
                JOIN musics m ON m.id = uf.music_id
                LEFT JOIN genres g ON m.genre_id = g.id
                WHERE uf.user_id = ?
                  AND uf.liked_flag = 1
                  AND COALESCE(m.is_active, 1) = 1
                ORDER BY uf.feedback_at DESC, uf.id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new FavoriteTrackDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        firstString(rs.getString("artist_names"), ""),
                        rs.getString("album_name"),
                        rs.getString("genre_name"),
                        null,
                        rs.getString("cover_url"),
                        safeInteger(rs.getObject("duration")),
                        rs.getString("description"),
                        safeInteger(rs.getObject("rating")),
                        rs.getTimestamp("feedback_at") == null ? "" : rs.getTimestamp("feedback_at").toLocalDateTime().toString()
                ),
                userId,
                userId,
                Math.max(1, limit)
        );
    }

    public void insertRecommendationLog(String userId,
                                        String requestId,
                                        String scene,
                                        String emotion,
                                        boolean coldStart,
                                        double explorationRatio,
                                        Map<String, Double> hybridWeight,
                                        List<TrackDto> tracks,
                                        String strategyVersion,
                                        Integer latencyMs,
                                        String timeSlot,
                                        boolean aiRefined,
                                        boolean aiSuccess,
                                        Integer mcpCandidateCount,
                                        Integer aiFinalCount,
                                        String fallbackReason) {
        String hybridWeightJson = "{}";
        String resultMusicIdsJson = "[]";
        try {
            hybridWeightJson = objectMapper.writeValueAsString(hybridWeight == null ? Map.of() : hybridWeight);
            resultMusicIdsJson = objectMapper.writeValueAsString(
                    tracks == null ? List.of() : tracks.stream().map(TrackDto::id).toList()
            );
        } catch (Exception ignored) {
            // keep persistence resilient even if JSON serialization fails
        }

        jdbcTemplate.update(
                """
                INSERT INTO recommendation_log
                (user_id, request_id, scene, emotion, cold_start_flag, exploration_ratio,
                 hybrid_weight, strategy_version, result_music_ids, result_count, latency_ms,
                 time_slot, ai_refined_flag, ai_success_flag, mcp_candidate_count, ai_final_count, fallback_reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """,
                userId,
                requestId,
                scene,
                emotion,
                coldStart,
                explorationRatio,
                hybridWeightJson,
                strategyVersion,
                resultMusicIdsJson,
                tracks == null ? 0 : tracks.size(),
                latencyMs,
                timeSlot,
                aiRefined,
                aiSuccess,
                mcpCandidateCount == null ? 0 : mcpCandidateCount,
                aiFinalCount == null ? (tracks == null ? 0 : tracks.size()) : aiFinalCount,
                fallbackReason
        );
    }

    public record StoredPlaylistRecord(
            Long id,
            String userId,
            String name,
            String coverUrl,
            String description,
            String moodTag,
            String sceneTag,
            int songCount,
            Instant updatedAt
    ) {
    }

    private TrackDto toTrackDto(Long id,
                                String name,
                                String artistNamesJson,
                                String albumName,
                                String coverUrl,
                                Object duration,
                                String description,
                                String lyric,
                                String genreName,
                                Long playCount) {
        LocalSongAssetService.AudioAsset audioAsset = localSongAssetService.resolveTrackAsset(id, coverUrl);
        TrackDto localTrack = new TrackDto(
                id,
                String.valueOf(id),
                name,
                firstString(artistNamesJson, "Unknown Artist"),
                albumName == null || albumName.isBlank() ? "Unknown Album" : albumName,
                genreName == null || genreName.isBlank() ? "unknown" : genreName,
                audioAsset.audioUrl(),
                audioAsset.artworkUrl(),
                toDurationSeconds(duration),
                resolveTrackDescription(description, genreName),
                splitLyrics(lyric),
                playCount == null ? 0.0 : playCount.doubleValue(),
                "LOCAL_DB"
        );
        return neteaseApiTrackFallbackService.enrichTrack(localTrack, false);
    }

    private String firstString(String json, String fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() {});
            if (values == null || values.isEmpty()) return fallback;
            return values.get(0);
        } catch (Exception ignored) {
            return json.replace("[", "").replace("]", "").replace("\"", "");
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return values == null ? List.of() : values.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Integer queryGenreIdByName(String genreName) {
        if (genreName == null || genreName.isBlank()) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM genres WHERE name = ? OR description = ? LIMIT 1",
                    Integer.class,
                    genreName,
                    genreName
            );
        } catch (DataAccessException error) {
            return null;
        }
    }

    private String normalizeSearchToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("[", "")
                .replace("]", "")
                .replace("\"", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private String resolveTimeSlot(int hour) {
        if (hour < 6) return "late_night";
        if (hour < 12) return "morning";
        if (hour < 18) return "afternoon";
        return "evening";
    }

    private long safeLong(String userId) {
        try {
            return Long.parseLong(userId);
        } catch (Exception ignored) {
            return Math.abs(Objects.hashCode(userId));
        }
    }

    private Integer toDurationSeconds(Object duration) {
        if (duration == null) {
            return null;
        }
        if (duration instanceof Number number) {
            long value = number.longValue();
            if (value <= 0) {
                return null;
            }
            return value > 10000 ? (int) Math.max(1, value / 1000) : (int) value;
        }
        try {
            long value = Long.parseLong(String.valueOf(duration).trim());
            if (value <= 0) {
                return null;
            }
            return value > 10000 ? (int) Math.max(1, value / 1000) : (int) value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer safeInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveTrackDescription(String description, String genreName) {
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        if (genreName != null && !genreName.isBlank()) {
            return "一首偏" + genreName + "氛围的歌曲，适合在当前推荐场景中播放。";
        }
        return "这首歌曲的详细简介暂时缺失，但系统已经保留了基础信息并会尝试补充播放资源。";
    }

    private String normalizeDescription(String description, String genreName) {
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        if (genreName != null && !genreName.isBlank()) {
            return "一首偏" + genreName + "氛围的歌曲，适合在当前推荐场景中播放。";
        }
        return "这首歌曲的详细简介暂时缺失，但已经为你保留了基础信息和本地音频能力。";
    }

    private List<String> splitLyrics(String lyric) {
        if (lyric == null || lyric.isBlank()) {
            return List.of();
        }
        return lyric.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .limit(12)
                .collect(Collectors.toList());
    }
}
