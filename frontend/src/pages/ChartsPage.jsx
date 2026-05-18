import { useEffect, useState } from 'react';
import { exploreApi } from '../api/services';
import { getErrorMessage, toArray } from '../utils/view';

const ChartList = ({ title, items, artistLabel, onPlay }) => (
  <section className="glass-card">
    <div className="section-heading">
      <div>
        <span className="eyebrow">榜单</span>
        <h3>{title}</h3>
      </div>
    </div>
    <div className="chart-list">
      {items.length > 0 ? (
        items.map((item) => (
          <button
            type="button"
            className="chart-row"
            key={`${title}-${item.rank}`}
            onClick={() => onPlay({ title: item.track, artist: artistLabel })}
          >
            <span className="chart-rank">#{item.rank}</span>
            <span className="chart-track">
              <strong>{item.track || '未命名歌曲'}</strong>
              <small>{artistLabel}</small>
            </span>
            <span className="chart-action">播放</span>
          </button>
        ))
      ) : (
        <p>暂无榜单数据。</p>
      )}
    </div>
  </section>
);

export default function ChartsPage({ onPlay }) {
  const [hot, setHot] = useState([]);
  const [fresh, setFresh] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    const run = async () => {
      setError('');
      try {
        const [hotData, freshData] = await Promise.all([
          exploreApi.hotCharts(),
          exploreApi.newCharts()
        ]);
        setHot(toArray(hotData));
        setFresh(toArray(freshData));
      } catch (requestError) {
        setHot([]);
        setFresh([]);
        setError(getErrorMessage(requestError, '榜单加载失败，请稍后重试'));
      }
    };
    run();
  }, []);

  return (
    <div className="page">
      <section className="hero hero-chart">
        <div className="hero-copy">
          <span className="eyebrow">榜单</span>
          <h2>用更简洁的行列表呈现新歌榜和热歌榜。</h2>
          <p>悬浮时整行微微变亮，并在右侧显示播放提示，让操作更自然。</p>
        </div>
        {error ? <p>{error}</p> : null}
      </section>
      <div className="dashboard-grid">
        <ChartList title="热歌榜" items={hot} artistLabel="榜单歌手" onPlay={onPlay} />
        <ChartList title="新歌榜" items={fresh} artistLabel="新声歌手" onPlay={onPlay} />
      </div>
    </div>
  );
}
