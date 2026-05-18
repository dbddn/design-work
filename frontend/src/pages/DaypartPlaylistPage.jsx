import { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { recommendationApi } from '../api/services';
import { getDaypartMeta } from '../utils/dayparts';
import { getErrorMessage, toArray } from '../utils/view';

const emotionLabels = {
  neutral: '平静',
  happy: '愉悦',
  sad: '低落',
  focus: '专注',
  energetic: '活力'
};

const sceneLabels = {
  default: '默认',
  commute: '通勤',
  workout: '运动',
  study: '学习',
  sleep: '睡前'
};

const formatTrackTitle = (track) => track?.title || track?.name || '未命名歌曲';
const formatTrackArtist = (track) => track?.artist || track?.artists || track?.singer || '未知歌手';
const UNKNOWN_GENRE_LABEL = '其他风格';

const isKnownGenre = (value) => {
  const text = String(value || '').trim();
  if (!text) return false;
  const normalized = text.toLowerCase();
  return normalized !== 'unknown' && normalized !== '未分类';
};

const isLocalDbTrack = (track) => Number.isFinite(Number(track?.id)) && Number(track.id) > 0;

const getTrackGenre = (track) => (isKnownGenre(track?.genre) ? String(track.genre).trim() : UNKNOWN_GENRE_LABEL);

const sanitizeTracks = (tracks) =>
  toArray(tracks).filter((track) => isLocalDbTrack(track) || formatTrackTitle(track) !== '未命名歌曲');

const formatDuration = (seconds) => {
  const safe = Number(seconds);
  if (!Number.isFinite(safe) || safe <= 0) return '--:--';
  const mins = Math.floor(safe / 60);
  const secs = Math.floor(safe % 60);
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
};

const uniqueTopValues = (items, mapper, limit = 3) =>
  [...new Set(toArray(items).map(mapper).filter(Boolean))].slice(0, limit);

export default function DaypartPlaylistPage({ onPlay, onOpenDetail, currentTrack }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { daypartKey } = useParams();
  const [searchParams] = useSearchParams();
  const scene = searchParams.get('scene') || 'default';
  const emotion = searchParams.get('emotion') || 'neutral';
  const meta = getDaypartMeta(daypartKey);
  const initialPlaylist = useMemo(() => location.state?.playlist || null, [location.state]);
  const initialTracks = useMemo(
    () => sanitizeTracks(initialPlaylist?.tracks || location.state?.tracks),
    [initialPlaylist, location.state]
  );
  const minimumTracks = 10;

  const [playlistData, setPlaylistData] = useState(initialPlaylist);
  const [tracks, setTracks] = useState(initialTracks);
  const [loading, setLoading] = useState(initialTracks.length < minimumTracks);
  const [error, setError] = useState('');
  const [selectedGenre, setSelectedGenre] = useState('all');

  useEffect(() => {
    let cancelled = false;

    if (initialPlaylist && initialTracks.length >= minimumTracks) {
      setPlaylistData(initialPlaylist);
      setTracks(initialTracks);
      setLoading(false);
      return undefined;
    }

    const run = async () => {
      setLoading(true);
      setError('');
      try {
        const payload = await recommendationApi.dayparts(scene, emotion, minimumTracks);
        const matchedGroup = toArray(payload?.playlists).find((group) => group.key === meta.key);
        if (!cancelled) {
          setPlaylistData(matchedGroup || initialPlaylist);
          setTracks(sanitizeTracks(matchedGroup?.tracks || initialTracks));
        }
      } catch (requestError) {
        if (!cancelled) {
          setPlaylistData(initialPlaylist);
          setTracks(sanitizeTracks(initialTracks));
          setError(getErrorMessage(requestError, '时段歌单加载失败，请稍后重试'));
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    run();
    return () => {
      cancelled = true;
    };
  }, [emotion, initialPlaylist, initialTracks, meta.key, scene]);

  const genreFilters = useMemo(
    () => ['all', ...new Set(toArray(tracks).map(getTrackGenre).filter(Boolean))],
    [tracks]
  );

  useEffect(() => {
    if (selectedGenre === 'all') return;
    if (!genreFilters.includes(selectedGenre)) {
      setSelectedGenre('all');
    }
  }, [genreFilters, selectedGenre]);

  const visibleTracks = useMemo(() => {
    if (selectedGenre === 'all') return tracks;
    return toArray(tracks).filter((track) => getTrackGenre(track) === selectedGenre);
  }, [selectedGenre, tracks]);

  const heroTrack = visibleTracks[0] || tracks[0] || null;
  const topGenres = useMemo(() => uniqueTopValues(tracks, getTrackGenre, 4), [tracks]);
  const tagItems = toArray(playlistData?.tags).length > 0
    ? toArray(playlistData?.tags)
    : [meta.label, meta.mood, emotionLabels[emotion] || '平静', sceneLabels[scene] || '默认'];

  return (
    <div className="page">
      <section className="glass-card playlist-detail-shell">
        <button type="button" className="btn subtle playlist-back-btn" onClick={() => navigate(-1)}>
          返回发现页
        </button>

        <div className="playlist-detail-hero">
          <div className="playlist-cover-panel">
            <div className="playlist-cover-glow playlist-cover-glow-a" aria-hidden="true" />
            <div className="playlist-cover-glow playlist-cover-glow-b" aria-hidden="true" />
            {heroTrack?.artworkUrl ? (
              <img src={heroTrack.artworkUrl} alt={`${formatTrackTitle(heroTrack)} 封面`} className="playlist-cover-art" />
            ) : (
              <div className="playlist-cover-fallback" />
            )}
            <div className="playlist-cover-shadow" aria-hidden="true" />
          </div>

          <div className="playlist-detail-copy">
            <span className="eyebrow">时段歌单</span>
            <h2>{playlistData?.playlistTitle || `${meta.label}歌单`}</h2>
            <p className="playlist-detail-subtitle">
              {playlistData?.playlistSubtitle || playlistData?.description || meta.description}
            </p>

            {playlistData?.playlistReason ? (
              <p className="playlist-detail-reason">{playlistData.playlistReason}</p>
            ) : null}

            <div className="chips playlist-tag-row">
              {tagItems.map((item) => (
                <span key={item} className="genre-pill">
                  {item}
                </span>
              ))}
            </div>

            <div className="playlist-detail-actions">
              <button type="button" className="btn" onClick={() => heroTrack && onPlay?.(heroTrack)} disabled={!heroTrack}>
                播放第一首
              </button>
              <button
                type="button"
                className="btn subtle"
                onClick={() => heroTrack && onOpenDetail?.(heroTrack)}
                disabled={!heroTrack}
              >
                查看头图歌曲
              </button>
            </div>

            <div className="playlist-detail-meta compact">
              <div>
                <strong>{visibleTracks.length}</strong>
                <span>首歌曲</span>
              </div>
              <div>
                <strong>{selectedGenre === 'all' ? topGenres.join(' / ') || meta.mood : selectedGenre}</strong>
                <span>主要风格</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="glass-card playlist-track-shell">
        <div className="section-heading">
          <div>
            <span className="eyebrow">歌曲列表</span>
            <h3>{playlistData?.playlistTitle || `${meta.label}时段推荐`}</h3>
          </div>
          {error ? <p className="error">{error}</p> : null}
        </div>

        {genreFilters.length > 1 ? (
          <div className="playlist-filter-row">
            {genreFilters.map((genre) => (
              <button
                key={genre}
                type="button"
                className={`playlist-filter-chip ${selectedGenre === genre ? 'active' : ''}`}
                onClick={() => setSelectedGenre(genre)}
              >
                {genre === 'all' ? '全部风格' : genre}
              </button>
            ))}
          </div>
        ) : null}

        {loading ? (
          <div className="playlist-table-empty">正在整理歌单...</div>
        ) : visibleTracks.length === 0 ? (
          <div className="playlist-table-empty">当前筛选条件下暂无可展示歌曲。</div>
        ) : (
          <div className="playlist-table">
            <div className="playlist-table-head">
              <span>#</span>
              <span>歌曲标题</span>
              <span>时长</span>
              <span>歌手</span>
              <span>专辑</span>
              <span>操作</span>
            </div>

            {visibleTracks.map((track, index) => {
              const isPlaying = currentTrack?.id === track?.id || currentTrack?.mcpTrackId === track?.mcpTrackId;
              return (
                <div
                  className={`playlist-table-row playlist-table-row-rich ${isPlaying ? 'is-playing' : ''}`}
                  key={track?.id || track?.mcpTrackId || `${meta.key}-${index}`}
                >
                  <span>{index + 1}</span>
                  <div className="playlist-track-main playlist-track-main-rich">
                    <div className="playlist-track-thumb">
                      {track?.artworkUrl ? (
                        <img src={track.artworkUrl} alt={`${formatTrackTitle(track)} 封面`} />
                      ) : (
                        <div className="playlist-track-thumb-fallback" />
                      )}
                    </div>
                    <div className="playlist-track-copy">
                      <strong>{formatTrackTitle(track)}</strong>
                      <small>{track?.album || '未知专辑'}</small>
                      {isPlaying ? <span className="playlist-playing-badge">当前播放中</span> : null}
                    </div>
                  </div>
                  <span>{formatDuration(track?.durationSec || track?.duration)}</span>
                  <span title={formatTrackArtist(track)}>{formatTrackArtist(track)}</span>
                  <span title={track?.album || '未知专辑'}>{track?.album || '未知专辑'}</span>
                  <div className="playlist-table-actions">
                    <button type="button" className="btn subtle small" onClick={() => onPlay?.(track)}>
                      播放
                    </button>
                    <button type="button" className="btn subtle small" onClick={() => onOpenDetail?.(track)}>
                      详情
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}
