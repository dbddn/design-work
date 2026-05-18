import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { trackApi } from '../api/services';
import { formatDateTime, getErrorMessage, toArray } from '../utils/view';

export default function PlaybackHistoryPage({ onPlay, onOpenDetail, currentTrack }) {
  const navigate = useNavigate();
  const [history, setHistory] = useState([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      setError('');
      try {
        const historyData = await trackApi.history(200);
        setHistory(toArray(historyData));
      } catch (requestError) {
        setHistory([]);
        setError(getErrorMessage(requestError, '播放记录加载失败，请稍后重试'));
      } finally {
        setLoading(false);
      }
    };
    load();
  }, []);

  return (
    <div className="page">
      <section className="glass-card">
        <div className="section-heading">
          <div>
            <span className="eyebrow">播放记录</span>
            <h3>全部历史播放</h3>
          </div>
          <button type="button" className="btn subtle small" onClick={() => navigate('/profile')}>
            返回个人中心
          </button>
        </div>

        {error ? <p>{error}</p> : null}
        {loading ? (
          <p>正在加载历史播放...</p>
        ) : (
          <div className="history-list history-list-detailed">
            {history.length > 0 ? (
              history.map((item) => {
                const isPlaying = currentTrack?.id === item.trackId;
                return (
                  <article
                    className={`history-row history-row-detailed ${isPlaying ? 'is-playing' : ''}`}
                    key={item.id || `${item.trackId}-${item.playedAt}`}
                  >
                    <div className="history-row-main">
                      <strong>{item.title || `歌曲 #${item.trackId || '--'}`}</strong>
                      <p>{item.artist || '未知歌手'} · {item.album || '未知专辑'}</p>
                      <div className="chips">
                        <span className="genre-pill">{item.genre || '未分类'}</span>
                        <span className="chip soft">{formatDateTime(item.playedAt)}</span>
                        {isPlaying ? <span className="chip soft">当前播放中</span> : null}
                      </div>
                    </div>
                    <div className="history-row-actions">
                      <button
                        type="button"
                        className="btn subtle small"
                        onClick={() =>
                          onPlay?.({
                            id: item.trackId,
                            title: item.title,
                            artist: item.artist,
                            album: item.album,
                            artworkUrl: item.artworkUrl,
                            genre: item.genre
                          })
                        }
                      >
                        播放
                      </button>
                      <button
                        type="button"
                        className="btn subtle small"
                        onClick={() =>
                          onOpenDetail?.({
                            id: item.trackId,
                            title: item.title,
                            artist: item.artist,
                            album: item.album,
                            artworkUrl: item.artworkUrl,
                            genre: item.genre
                          })
                        }
                      >
                        详情
                      </button>
                    </div>
                  </article>
                );
              })
            ) : (
              <p>暂无历史播放数据。</p>
            )}
          </div>
        )}
      </section>
    </div>
  );
}
