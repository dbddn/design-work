import { useEffect, useMemo, useState } from 'react';
import TimelineStrip from '../components/TimelineStrip';
import { exploreApi } from '../api/services';
import { getErrorMessage, toArray } from '../utils/view';

const START_YEAR = 1970;
const END_YEAR = 2026;
const STEP = 5;
const ALL_TAG = '全部标签';

const buildIntervals = () => {
  const nodes = [];
  for (let start = START_YEAR; start <= END_YEAR; start += STEP) {
    const end = Math.min(start + STEP - 1, END_YEAR);
    nodes.push({
      key: `${start}-${end}`,
      period: `${start}-${end}`,
      startYear: start,
      endYear: end,
      topGenre: ''
    });
  }
  return nodes;
};

const pickDefaultIntervalKey = (intervals, years) => {
  if (intervals.length === 0) return null;
  if (!years.length) return intervals[0].key;
  const maxYear = Math.max(...years);
  const hit = intervals.find((item) => maxYear >= item.startYear && maxYear <= item.endYear);
  return hit?.key || intervals[0].key;
};

export default function ExplorePage({ onPlay, onOpenDetail }) {
  const [timeline, setTimeline] = useState([]);
  const [selectedEra, setSelectedEra] = useState(null);
  const [eraTracks, setEraTracks] = useState([]);
  const [activeTag, setActiveTag] = useState(ALL_TAG);
  const [loadingTracks, setLoadingTracks] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const run = async () => {
      setError('');
      try {
        const intervals = buildIntervals();
        const timelineData = toArray(await exploreApi.timeline());

        const years = timelineData
          .map((item) => Number(item?.year))
          .filter((value) => Number.isFinite(value));

        const genreCountByInterval = new Map();
        timelineData.forEach((item) => {
          const year = Number(item?.year);
          const genre = item?.genre;
          if (!Number.isFinite(year) || !genre) return;
          const interval = intervals.find((node) => year >= node.startYear && year <= node.endYear);
          if (!interval) return;
          const key = interval.key;
          if (!genreCountByInterval.has(key)) {
            genreCountByInterval.set(key, new Map());
          }
          const counter = genreCountByInterval.get(key);
          counter.set(genre, (counter.get(genre) || 0) + 1);
        });

        const enriched = intervals.map((node) => {
          const counter = genreCountByInterval.get(node.key);
          if (!counter || counter.size === 0) {
            return node;
          }
          let topGenre = '';
          let maxCount = -1;
          counter.forEach((count, genre) => {
            if (count > maxCount) {
              maxCount = count;
              topGenre = genre;
            }
          });
          return { ...node, topGenre };
        });

        const defaultKey = pickDefaultIntervalKey(enriched, years);
        const defaultEra = enriched.find((item) => item.key === defaultKey) || enriched[0] || null;

        setTimeline(enriched);
        setSelectedEra(defaultEra);
      } catch (requestError) {
        setTimeline([]);
        setSelectedEra(null);
        setEraTracks([]);
        setError(getErrorMessage(requestError, '时间轴加载失败'));
      }
    };

    run();
  }, []);

  useEffect(() => {
    const loadTracks = async () => {
      if (!selectedEra?.startYear || !selectedEra?.endYear) {
        setEraTracks([]);
        return;
      }

      setLoadingTracks(true);
      setActiveTag(ALL_TAG);
      try {
        const data = await exploreApi.timeMachineTracks({
          startYear: selectedEra.startYear,
          endYear: selectedEra.endYear,
          limit: 120
        });
        setEraTracks(toArray(data));
      } catch {
        setEraTracks([]);
      } finally {
        setLoadingTracks(false);
      }
    };

    loadTracks();
  }, [selectedEra]);

  const eraTitle = useMemo(() => selectedEra?.period || '未知区间', [selectedEra]);

  const tagOptions = useMemo(() => {
    const tags = [...new Set(eraTracks.map((track) => track.genre).filter(Boolean))];
    return [ALL_TAG, ...tags];
  }, [eraTracks]);

  const filteredTracks = useMemo(() => {
    if (activeTag === ALL_TAG) return eraTracks;
    return eraTracks.filter((track) => track.genre === activeTag);
  }, [activeTag, eraTracks]);

  return (
    <div className="page">
      <section className="glass-card">
        <h3>时间轴</h3>
        {error ? <p>{error}</p> : null}
        <TimelineStrip items={timeline} activeKey={selectedEra?.period} onSelect={setSelectedEra} />
      </section>

      <section className="glass-card">
        <div className="section-heading">
          <h3>{eraTitle}</h3>
        </div>

        <div className="genre-tabs">
          {tagOptions.map((tag) => (
            <button
              key={tag}
              type="button"
              className={activeTag === tag ? 'active' : ''}
              onClick={() => setActiveTag(tag)}
            >
              {tag}
            </button>
          ))}
        </div>

        {loadingTracks ? (
          <p>加载中...</p>
        ) : filteredTracks.length > 0 ? (
          <div className="era-track-list">
            {filteredTracks.map((track) => (
              <article className="era-track-row" key={`era-track-${track.id || track.mcpTrackId || track.title}`}>
                <button type="button" className="era-track-main" onClick={() => onOpenDetail?.(track)}>
                  <strong>{track.title || '未知歌曲'}</strong>
                  <span>{track.artist || '未知歌手'} · {track.album || '未知专辑'} · {track.genre || '未分类'}</span>
                </button>
                <button
                  type="button"
                  className="btn subtle small"
                  onClick={() =>
                    onPlay?.({
                      ...track,
                      album: track.album || '音乐时光机'
                    })
                  }
                >
                  播放
                </button>
              </article>
            ))}
          </div>
        ) : (
          <p>暂无歌曲</p>
        )}
      </section>

    </div>
  );
}
