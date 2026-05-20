import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { artistApi } from '../api/services';
import { getErrorMessage, toArray } from '../utils/view';

export default function ArtistPage({ onPlay, onOpenDetail, currentTrack }) {
  const navigate = useNavigate();
  const { artistId } = useParams();
  const [artist, setArtist] = useState(null);
  const [tracks, setTracks] = useState([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;

    const run = async () => {
      setLoading(true);
      setError('');
      try {
        const payload = await artistApi.detail(artistId, 0, 10);
        if (cancelled) return;
        setArtist(payload || null);
        setTracks(toArray(payload?.hotTracks));
        setPage(0);
        setHasMore(Boolean(payload?.hasMore));
      } catch (requestError) {
        if (cancelled) return;
        setArtist(null);
        setTracks([]);
        setError(getErrorMessage(requestError, '歌手详情加载失败，请稍后重试'));
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
  }, [artistId]);

  const loadMore = async () => {
    if (loadingMore || !hasMore) return;
    setLoadingMore(true);
    try {
      const nextPage = page + 1;
      const payload = await artistApi.detail(artistId, nextPage, 5);
      const nextTracks = toArray(payload?.hotTracks);
      setTracks((current) => [...current, ...nextTracks]);
      setPage(nextPage);
      setHasMore(Boolean(payload?.hasMore));
    } catch (requestError) {
      setError(getErrorMessage(requestError, '加载更多热门歌曲失败，请稍后重试'));
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <div className="page">
      <section className="glass-card artist-hero artist-hero-premium">
        <button type="button" className="btn subtle artist-back-btn" onClick={() => navigate(-1)}>
          返回上一页
        </button>

        {loading ? (
          <div className="artist-state">正在加载歌手信息...</div>
        ) : error ? (
          <div className="artist-state">{error}</div>
        ) : (
          <div className="artist-hero-layout">
            <div className="artist-portrait-panel">
              {artist?.avatarUrl ? (
                <img src={artist.avatarUrl} alt={`${artist?.name || '歌手'} 照片`} className="artist-portrait-img" />
              ) : (
                <div className="artist-portrait-fallback">
                  <span>{String(artist?.name || '?').trim().slice(0, 1)}</span>
                </div>
              )}
            </div>
            <div className="artist-copy">
              <span className="eyebrow">歌手详情</span>
              <h2>{artist?.name || '未知歌手'}</h2>
              <p className="artist-desc">{artist?.description || '暂无歌手简介。'}</p>
              <div className="chips">
                <span className="genre-pill">热门歌曲 {tracks.length} 首</span>
                {tracks[0]?.genre ? <span className="chip soft">{tracks[0].genre}</span> : null}
              </div>
            </div>
          </div>
        )}
      </section>

      <section className="glass-card">
        <div className="section-heading">
          <div>
            <span className="eyebrow">热门歌曲</span>
            <h3>数据库内热门作品</h3>
          </div>
        </div>

        {tracks.length > 0 ? (
          <div className="artist-song-list">
            {tracks.map((track, index) => {
              const isPlaying = currentTrack?.id === track?.id || currentTrack?.mcpTrackId === track?.mcpTrackId;
              return (
                <article
                  key={track?.id || `${track?.title}-${index}`}
                  className={`artist-song-row ${isPlaying ? 'is-playing' : ''}`}
                >
                  <div className="artist-song-rank">
                    <span>#{index + 1}</span>
                  </div>
                  <div className="artist-song-copy">
                    <strong>{track?.title || '未命名歌曲'}</strong>
                    <p>{track?.album || '未知专辑'}</p>
                    <div className="artist-song-meta">
                      <span className="genre-pill">{track?.genre || '未分类'}</span>
                      {isPlaying ? <span className="artist-playing-pill">当前播放中</span> : null}
                    </div>
                  </div>
                  <div className="artist-song-actions">
                    <button type="button" className="btn subtle small" onClick={() => onPlay?.(track)}>
                      播放
                    </button>
                    <button type="button" className="btn subtle small" onClick={() => onOpenDetail?.(track)}>
                      详情
                    </button>
                  </div>
                </article>
              );
            })}
          </div>
        ) : (
          !loading && <div className="artist-state">当前歌手还没有可展示的热门歌曲。</div>
        )}

        {hasMore ? (
          <div className="artist-load-more">
            <button type="button" className="btn subtle" onClick={loadMore} disabled={loadingMore}>
              {loadingMore ? '加载中...' : '加载更多'}
            </button>
          </div>
        ) : null}
      </section>
    </div>
  );
}
