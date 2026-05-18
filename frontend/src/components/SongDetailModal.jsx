import { useEffect } from 'react';

const formatDuration = (seconds) => {
  const safeSeconds = Number.isFinite(Number(seconds)) ? Math.max(0, Number(seconds)) : 0;
  const mins = Math.floor(safeSeconds / 60);
  const secs = Math.floor(safeSeconds % 60);
  return `${mins} 分 ${String(secs).padStart(2, '0')} 秒`;
};

export default function SongDetailModal({ open, track, loading, error, onClose, onPlay, onOpenArtist }) {
  useEffect(() => {
    if (!open) return undefined;
    const handleKeyDown = (event) => {
      if (event.key === 'Escape') onClose?.();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose, open]);

  if (!open) return null;

  return (
    <div className="modal-backdrop" onClick={onClose} role="presentation">
      <section
        className="song-detail-modal glass-panel"
        onClick={(event) => event.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="歌曲详情"
      >
        <div className="song-detail-header">
          <div>
            <span className="eyebrow">歌曲详情</span>
            <h3>{track?.title || '加载中'}</h3>
            <p>
              <button
                type="button"
                className="artist-inline-link"
                onClick={() => track?.artist && onOpenArtist?.(track.artist)}
              >
                {track?.artist || '未知艺术家'}
              </button>
              {' · '}
              {track?.album || '未知专辑'}
            </p>
          </div>
          <button type="button" className="icon-btn" onClick={onClose}>关闭</button>
        </div>

        {loading ? (
          <div className="song-detail-state">
            <p>正在加载歌曲详情...</p>
          </div>
        ) : error ? (
          <div className="song-detail-state">
            <p>{error}</p>
          </div>
        ) : track ? (
          <div className="song-detail-layout">
            <div className="song-detail-cover">
              {track.artworkUrl ? (
                <img src={track.artworkUrl} alt={`${track.title} 封面`} />
              ) : (
                <div className="song-detail-fallback" style={{ background: track.artwork }} />
              )}
              <div className="song-detail-cover-shade" />
              <div className="song-detail-cover-meta">
                <span className="genre-pill on-cover">{track.genre || '未分类'}</span>
                <strong>{track.title || '未命名歌曲'}</strong>
                <span>{track.artist || '未知艺术家'}</span>
              </div>
            </div>

            <div className="song-detail-content">
              <div className="song-detail-actions">
                <button type="button" className="btn" onClick={() => onPlay?.(track)}>立即播放</button>
              </div>

              <div className="song-detail-grid">
                <article className="detail-card">
                  <span className="eyebrow">基础信息</span>
                  <div className="detail-list">
                    <div><strong>歌曲 ID</strong><span>{track.id ?? '--'}</span></div>
                    <div><strong>专辑</strong><span>{track.album || '未知专辑'}</span></div>
                    <div>
                      <strong>歌手</strong>
                      <span>
                        <button
                          type="button"
                          className="artist-inline-link"
                          onClick={() => track?.artist && onOpenArtist?.(track.artist)}
                        >
                          {track.artist || '未知艺术家'}
                        </button>
                      </span>
                    </div>
                    <div><strong>风格</strong><span className="genre-pill">{track.genre || '未分类'}</span></div>
                    <div><strong>时长</strong><span>{formatDuration(track.duration || track.durationSec)}</span></div>
                    <div><strong>来源</strong><span>{track.source || 'LOCAL_DB'}</span></div>
                  </div>
                </article>

                <article className="detail-card">
                  <span className="eyebrow">歌曲简介</span>
                  <p className="detail-copy">{track.description || '暂无歌曲简介。'}</p>
                </article>
              </div>

              <article className="detail-card">
                <span className="eyebrow">歌词</span>
                <div className="lyrics-block large">
                  {(Array.isArray(track.lyrics) && track.lyrics.length ? track.lyrics : ['暂无歌词']).map((line, index) => (
                    <p key={`${line}-${index}`}>{line}</p>
                  ))}
                </div>
              </article>
            </div>
          </div>
        ) : (
          <div className="song-detail-state">
            <p>当前没有可展示的歌曲信息。</p>
          </div>
        )}
      </section>
    </div>
  );
}
