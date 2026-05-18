import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { artistApi, trackApi } from '../api/services';
import { getErrorMessage, toArray } from '../utils/view';

const getArtworkStyle = (track) => (
  track?.artworkUrl
    ? {
        backgroundImage: `linear-gradient(180deg, rgba(7,13,24,0.08) 0%, rgba(7,13,24,0.18) 35%, rgba(7,13,24,0.72) 100%), url("${track.artworkUrl}")`,
        backgroundSize: 'cover',
        backgroundPosition: 'center'
      }
    : undefined
);

export default function SearchPage({ onPlay, onOpenDetail }) {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const query = (searchParams.get('q') || '').trim();
  const [result, setResult] = useState([]);
  const [artistCard, setArtistCard] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const run = async () => {
      if (!query) {
        setResult([]);
        setArtistCard(null);
        setError('');
        return;
      }

      setLoading(true);
      setError('');
      try {
        const requests = [trackApi.search(query, 0, 20)];
        if (typeof artistApi.resolve === 'function') {
          requests.push(artistApi.resolve(query));
        }

        const [trackData, artistData] = await Promise.allSettled(requests);

        if (trackData.status === 'fulfilled') {
          setResult(toArray(trackData.value?.items));
          setArtistCard(trackData.value?.artistCard || null);
        } else {
          setResult([]);
        }

        if (artistData?.status === 'fulfilled' && artistData.value?.id) {
          setArtistCard(artistData.value);
        }

        if (trackData.status === 'rejected' && (!artistData || artistData.status === 'rejected')) {
          throw trackData.reason || artistData.reason;
        }
      } catch (requestError) {
        setResult([]);
        setArtistCard(null);
        setError(getErrorMessage(requestError, '搜索失败，请稍后重试'));
      } finally {
        setLoading(false);
      }
    };

    run();
  }, [query]);

  return (
    <div className="page">
      <section className="hero hero-search compact">
        <div className="hero-copy centered">
          <span className="eyebrow">搜索结果</span>
          <h2>{query ? `“${query}” 的搜索结果` : '请先在顶部搜索框输入关键词'}</h2>
        </div>
      </section>

      {artistCard ? (
        <section className="glass-card artist-search-card">
          <div className="artist-search-card-copy">
            <span className="eyebrow">歌手匹配</span>
            <h3>{artistCard.name}</h3>
            <p>{artistCard.description || '暂无歌手简介。'}</p>
          </div>
          <button type="button" className="btn" onClick={() => navigate(`/artists/${artistCard.id}`)}>
            查看歌手详情
          </button>
        </section>
      ) : null}

      <section className="glass-card">
        <div className="section-heading">
          <div>
            <span className="eyebrow">结果列表</span>
            <h3>{result.length} 首歌曲匹配</h3>
          </div>
          {error ? <p>{error}</p> : null}
        </div>
      </section>

      <section className="track-grid">
        {result.length > 0 ? (
          result.map((track, index) => (
            <article
              className="track-card fade-card"
              key={track.mcpTrackId || track.id || index}
              style={{ animationDelay: `${index * 55}ms` }}
            >
              <div className="card-artwork-shell">
                <div className="card-artwork soft" style={getArtworkStyle(track)}>
                  <div className="card-artwork-shade" />
                  <div className="card-artwork-meta">
                    <span className="cover-kicker">搜索命中</span>
                    <strong>{track.title || '未命名歌曲'}</strong>
                    <span>{track.artist || '未知艺术家'}</span>
                  </div>
                  <button
                    type="button"
                    className="cover-play-btn"
                    onClick={() => onPlay(track)}
                    aria-label={`播放 ${track.title || '歌曲'}`}
                  >
                    播放
                  </button>
                </div>
              </div>
              <div className="track-card-body">
                <h4>{track.title || '未命名歌曲'}</h4>
                <p>{track.artist || '未知艺术家'}</p>
                <div className="track-card-meta-line">
                  <small>{track.album || '专辑信息暂缺'}</small>
                  <span className="genre-pill">{track.genre || '未分类'}</span>
                </div>
              </div>
              <div className="track-card-actions">
                <button type="button" className="btn subtle" onClick={() => onPlay(track)}>
                  播放
                </button>
                <button type="button" className="btn subtle" onClick={() => onOpenDetail?.(track)}>
                  详情
                </button>
              </div>
            </article>
          ))
        ) : (
          !loading && (
            <section className="glass-card">
              <p>{query ? '没有找到匹配结果，试试换一个关键词。' : '顶部搜索后，结果会显示在这里。'}</p>
            </section>
          )
        )}
      </section>
    </div>
  );
}
