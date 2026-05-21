import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import CommunityPage from './CommunityPage';
import { chartsApi, recommendationApi, trackApi } from '../api/services';
import { buildDaypartGroups } from '../utils/dayparts';
import { getErrorMessage, toArray } from '../utils/view';
import toplistIcon1 from '../../icon/1.svg';
import toplistIcon2 from '../../icon/2.svg';
import toplistIcon3 from '../../icon/3.svg';
import toplistIcon4 from '../../icon/4.svg';
import playIcon from '../../icon/bofang.svg';
import detailIcon from '../../icon/xiangqing.svg';

const sceneOptions = [
  ['default', '默认'],
  ['commute', '通勤'],
  ['workout', '运动'],
  ['study', '学习'],
  ['sleep', '助眠']
];

const emotionOptions = [
  ['neutral', '平静'],
  ['happy', '开心'],
  ['sad', '伤感'],
  ['focus', '专注'],
  ['energetic', '活力']
];

const genderOptions = [
  ['male', '男'],
  ['female', '女'],
  ['unknown', '不透露']
];

const heroThemes = [
  'linear-gradient(180deg, rgba(24,72,173,0.10) 0%, rgba(15,35,84,0.78) 100%), linear-gradient(135deg, #4f7dff, #183b96)',
  'linear-gradient(180deg, rgba(104,25,25,0.06) 0%, rgba(64,7,7,0.84) 100%), linear-gradient(135deg, #7d100f, #2d0202)',
  'linear-gradient(180deg, rgba(21,25,38,0.08) 0%, rgba(60,68,88,0.82) 100%), linear-gradient(135deg, #9da8c3, #4f5a73)',
  'linear-gradient(180deg, rgba(32,70,111,0.08) 0%, rgba(12,37,66,0.86) 100%), linear-gradient(135deg, #7db1ff, #2f5e9b)',
  'linear-gradient(180deg, rgba(96,82,102,0.08) 0%, rgba(59,43,67,0.86) 100%), linear-gradient(135deg, #d7c7c7, #5f4652)'
];

const chartThemes = [
  'linear-gradient(180deg, rgba(34,82,191,0.12), rgba(19,47,108,0.88))',
  'linear-gradient(180deg, rgba(109,19,19,0.10), rgba(74,7,7,0.90))'
];

const onboardingStepMeta = [
  { title: '欢迎来到 ScenePulse', desc: '快速完成引导，生成你的初始偏好。' },
  { title: '选择你的偏好', desc: '所选风格将影响首批推荐结果。' },
  { title: '准备开始', desc: '保存后即可生成个性化歌单与推荐卡片。' }
];

const normalizeText = (value, fallback) => {
  if (typeof value !== 'string') return fallback;
  const next = value.trim();
  return next || fallback;
};

const formatTrackArtist = (track, fallback = '未知歌手') =>
  normalizeText(track?.artist || track?.artists || track?.singer, fallback);

const formatTrackTitle = (track, fallback = '未命名歌曲') =>
  normalizeText(track?.title || track?.name || track?.track, fallback);

const isLocalDbTrack = (track) => Number.isFinite(Number(track?.id)) && Number(track.id) > 0;

const getArtworkStyle = (track, fallback) => ({
  backgroundImage: track?.artworkUrl
    ? `linear-gradient(180deg, rgba(7,13,24,0.05) 0%, rgba(7,13,24,0.18) 38%, rgba(7,13,24,0.76) 100%), url("${track.artworkUrl}")`
    : fallback,
  backgroundSize: 'cover',
  backgroundPosition: 'center'
});

const getChartTrack = (item, title) => ({
  id: item?.id || `${title}-${item?.rank || item?.track || item?.title || 'item'}`,
  title: normalizeText(item?.track || item?.title || item?.name, title),
  artist: normalizeText(item?.artist || item?.subtitle || item?.album || title, title),
  album: normalizeText(item?.album, ''),
  artworkUrl: item?.artworkUrl || item?.coverUrl || item?.cover || '',
  audioUrl: item?.audioUrl || '',
  genre: normalizeText(item?.genre, '')
});

const buildPersonalHotCharts = (historyRows) => {
  const grouped = new Map();
  toArray(historyRows).forEach((item) => {
    const trackId = item?.trackId || item?.id || '';
    const title = formatTrackTitle(item, '');
    if (!title) return;
    const artist = formatTrackArtist(item, '未知歌手');
    const key = trackId ? `id:${trackId}` : `${title}::${artist}`;
    const current = grouped.get(key) || {
      id: trackId || key,
      track: title,
      title,
      artist,
      album: item?.album || '',
      artworkUrl: item?.artworkUrl || '',
      genre: item?.genre || '',
      playCount: 0
    };
    current.playCount += 1;
    grouped.set(key, current);
  });

  return [...grouped.values()]
    .sort((left, right) => right.playCount - left.playCount || String(left.title).localeCompare(String(right.title), 'zh-Hans-CN'))
    .slice(0, 5)
    .map((item, index) => ({ ...item, rank: index + 1 }));
};

const getOptionLabel = (options, value, fallback) =>
  options.find(([key]) => key === value)?.[1] || fallback;

const buildSongShelfTags = (result, scene, emotion) => {
  const selectedGenres = toArray(result?.selectedGenres).slice(0, 3);
  const sceneLabel = getOptionLabel(sceneOptions, scene, '日常');
  const emotionLabel = getOptionLabel(emotionOptions, emotion, '平静');
  return ['每日推荐', sceneLabel, emotionLabel, ...selectedGenres];
};

const daypartCopy = {
  morning: {
    title: '晨间唤醒歌单',
    description:
      '用轻盈、明亮的旋律把状态慢慢打开，优先挑选节奏不急、情绪干净的歌曲，适合洗漱、通勤前和刚坐到桌前的第一段时间。'
  },
  afternoon: {
    title: '午后续航歌单',
    description:
      '在容易分神的下午补上一点稳定推力，歌曲会兼顾旋律记忆点和适度节奏感，适合工作、学习、整理任务时持续播放。'
  },
  evening: {
    title: '夜幕松弛歌单',
    description:
      '把白天的紧绷感慢慢降下来，选择更有叙事感和空间感的曲目，适合晚饭后、回家路上或一个人安静放空的时候。'
  },
  midnight: {
    title: '深夜漫游歌单',
    description:
      '偏向安静、细腻和沉浸的听感，减少过强的鼓点刺激，让旋律陪你写东西、想事情，或者把情绪轻轻放回夜色里。'
  }
};

const getDaypartCopy = (group = {}) => {
  const preset = daypartCopy[group.key] || {};
  return {
    title: normalizeText(group.playlistTitle, preset.title || normalizeText(group.mood, '专属时段歌单'))
      .replace('上午个性化歌单', '晨间唤醒歌单')
      .replace('下午个性化歌单', '午后续航歌单')
      .replace('晚上个性化歌单', '夜幕松弛歌单')
      .replace('深夜个性化歌单', '深夜漫游歌单'),
    description: preset.description || '用更贴近当前时段的旋律、节奏和情绪铺底，让这组歌曲自然贴合当下的环境。'
  };
};

const toplistIcons = [toplistIcon1, toplistIcon2, toplistIcon3, toplistIcon4];

function ChartPanel({ title, items, onPlay, themeIndex }) {
  const safeItems = toArray(items).slice(0, 5);

  return (
    <section className="chart-feature-card" style={{ backgroundImage: chartThemes[themeIndex % chartThemes.length] }}>
      <div className="chart-feature-head">
        <span className="cover-kicker">{title}</span>
      </div>
      <div className="chart-feature-list">
        {safeItems.length > 0 ? (
          safeItems.map((item, index) => {
            const track = getChartTrack(item, title);
            return (
              <button
                type="button"
                className="chart-feature-row"
                key={`${title}-${track.id}-${index}`}
                onClick={() => onPlay(track)}
              >
                <span className="chart-feature-rank">#{item?.rank || index + 1}</span>
                <strong title={track.title}>{track.title}</strong>
                <small title={track.artist}>{track.artist}</small>
              </button>
            );
          })
        ) : (
          <div className="chart-feature-empty">暂无数据</div>
        )}
      </div>
    </section>
  );
}

export default function HomePage({ onPlay, onOpenDetail }) {
  const navigate = useNavigate();
  const [scene, setScene] = useState('default');
  const [emotion, setEmotion] = useState('neutral');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState({
    tracks: [],
    hybridWeight: {},
    userStage: 'VISITOR',
    guest: true,
    onboardingRequired: false,
    selectedGenres: [],
    onboardingOptions: [],
    playCount30d: 0,
    summary: ''
  });
  const [daypartResult, setDaypartResult] = useState({
    playlists: [],
    summary: '',
    guest: true,
    onboardingRequired: false
  });
  const [neteaseToplists, setNeteaseToplists] = useState([]);
  const [hotCharts, setHotCharts] = useState([]);
  const [newCharts, setNewCharts] = useState([]);
  const [activeHeroIndex, setActiveHeroIndex] = useState(0);
  const [selectedGenres, setSelectedGenres] = useState([]);
  const [selectedGender, setSelectedGender] = useState('unknown');
  const [savingPreferences, setSavingPreferences] = useState(false);
  const [onboardingStep, setOnboardingStep] = useState(0);
  const [recommendationOffset, setRecommendationOffset] = useState(0);

  const loadDaypartRecommendations = async (sceneValue = scene, emotionValue = emotion, limit = 10) => {
    try {
      const data = await recommendationApi.dayparts(sceneValue, emotionValue, limit);
      const nextState = {
        playlists: toArray(data?.playlists),
        summary: normalizeText(data?.summary, ''),
        guest: Boolean(data?.guest),
        onboardingRequired: Boolean(data?.onboardingRequired)
      };
      setDaypartResult(nextState);
      return nextState;
    } catch {
      const emptyState = {
        playlists: [],
        summary: '',
        guest: false,
        onboardingRequired: false
      };
      setDaypartResult(emptyState);
      return emptyState;
    }
  };

  const loadRecommendations = async (limit = 12) => {
    setLoading(true);
    setError('');
    try {
      const [data] = await Promise.all([
        recommendationApi.list(scene, emotion, limit),
        loadDaypartRecommendations(scene, emotion, 10)
      ]);
      const nextResult = {
        tracks: toArray(data?.tracks),
        hybridWeight: data?.hybridWeight || {},
        userStage: data?.userStage || 'VISITOR',
        guest: Boolean(data?.guest),
        onboardingRequired: Boolean(data?.onboardingRequired),
        selectedGenres: toArray(data?.selectedGenres),
        onboardingOptions: toArray(data?.onboardingOptions),
        playCount30d: Number(data?.playCount30d || 0),
        summary: normalizeText(data?.summary, '')
      };
      setResult(nextResult);
      setSelectedGenres(nextResult.selectedGenres);
      setSelectedGender('unknown');
      setOnboardingStep(nextResult.onboardingRequired ? 0 : 2);
      setRecommendationOffset(0);
    } catch (requestError) {
      setResult((current) => ({ ...current, tracks: [] }));
      setDaypartResult((current) => ({ ...current, playlists: [] }));
      setError(getErrorMessage(requestError, '推荐加载失败，请稍后重试'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadRecommendations();
  }, []);

  useEffect(() => {
    let cancelled = false;

    const enrichChartItems = async (rows) => {
      const safeRows = toArray(rows);
      const enriched = await Promise.all(
        safeRows.slice(0, 8).map(async (item) => {
          const title = normalizeText(item?.track || item?.title || item?.name, '');
          if (!title) return item;
          try {
            const payload = await trackApi.search(title, 0, 3);
            const candidates = toArray(payload?.items);
            const matched =
              candidates.find((track) => normalizeText(track?.title, '') === title) ||
              candidates.find((track) => normalizeText(track?.title, '').includes(title) || title.includes(normalizeText(track?.title, ''))) ||
              candidates[0];

            return matched
              ? {
                  ...item,
                  id: matched.id || item?.id,
                  title: matched.title || item?.title || item?.track,
                  track: matched.title || item?.track || item?.title,
                  artist: matched.artist || item?.artist,
                  album: matched.album || item?.album,
                  artworkUrl: matched.artworkUrl || item?.artworkUrl,
                  audioUrl: matched.audioUrl || item?.audioUrl,
                  genre: matched.genre || item?.genre
                }
              : item;
          } catch {
            return item;
          }
        })
      );

      return [...enriched, ...safeRows.slice(8)];
    };

    const run = async () => {
      try {
        const [historyData, newData, neteaseData] = await Promise.all([
          trackApi.history(100),
          chartsApi.listNew(),
          chartsApi.listNeteaseToplist(4, 5)
        ]);
        const personalHot = buildPersonalHotCharts(historyData);
        const enrichedNew = await enrichChartItems(newData);
        if (!cancelled) {
          setHotCharts(personalHot);
          setNewCharts(enrichedNew);
          setNeteaseToplists(toArray(neteaseData));
        }
      } catch {
        if (!cancelled) {
          setHotCharts([]);
          setNewCharts([]);
          setNeteaseToplists([]);
        }
      }
    };

    run();
    return () => {
      cancelled = true;
    };
  }, []);

  const recommendedTracks = useMemo(() => toArray(result?.tracks), [result]);
  const visibleRecommendedTracks = useMemo(() => {
    if (recommendedTracks.length <= 5) {
      return recommendedTracks;
    }
    const safeStart = recommendationOffset % recommendedTracks.length;
    const rotated = [...recommendedTracks.slice(safeStart), ...recommendedTracks.slice(0, safeStart)];
    return rotated.slice(0, 5);
  }, [recommendationOffset, recommendedTracks]);

  const curatedDayparts = useMemo(() => {
    const sanitizeTracks = (tracks) =>
      toArray(tracks).filter((track) => isLocalDbTrack(track) || formatTrackTitle(track, '').trim());

    const withSanitizedTracks = (group) => {
      const tracks = sanitizeTracks(group?.tracks);
      return {
        ...group,
        tracks,
        candidateCount: tracks.length,
        finalCount: tracks.length
      };
    };

    const serverPlaylists = toArray(daypartResult?.playlists);
    if (serverPlaylists.length > 0) {
      return serverPlaylists.map((group, index) => {
        const nextGroup = withSanitizedTracks(group);
        if (nextGroup.tracks.length > 0) {
          return nextGroup;
        }
        const fallbackTracks = recommendedTracks.slice(index * 3, index * 3 + 3);
        return withSanitizedTracks({ ...group, tracks: fallbackTracks });
      });
    }
    return buildDaypartGroups(recommendedTracks, 3).map((group) => withSanitizedTracks({
      ...group,
      playlistTitle: `${group.label}歌单`,
      playlistSubtitle: group.mood,
      playlistReason: group.description,
      tags: [group.mood],
      explanations: {},
      aiEnabled: false,
      aiSuccess: false,
      fallbackUsed: true,
      fallbackReason: 'DAYPART_ENDPOINT_UNAVAILABLE'
    }));
  }, [daypartResult?.playlists, recommendedTracks]);

  const isGuest = Boolean(result?.guest);
  const onboardingRequired = Boolean(result?.onboardingRequired);
  const songShelfTags = useMemo(
    () => buildSongShelfTags(result, scene, emotion),
    [emotion, result, scene]
  );

  const displayToplists = useMemo(() => {
    const personalHotList = hotCharts.length > 0
      ? {
          id: 'personal-hot',
          name: '热歌榜',
          coverImgUrl: '',
          updateFrequency: '来自我的听歌记录 Top5',
          tracks: hotCharts.slice(0, 5).map((item) => ({
            localTrackId: item?.id,
            title: item?.track || item?.title || '未知歌曲',
            artist: item?.artist || '未知歌手',
            album: item?.album || '',
            artworkUrl: item?.artworkUrl || '',
            playCount: item?.playCount || 0
          }))
        }
      : null;

    const fromApi = toArray(neteaseToplists).filter((item) => item?.name !== '热歌榜');
    const hasTrackData = fromApi.some((item) => toArray(item?.tracks).length > 0);
    if (fromApi.length > 0 && hasTrackData) {
      const preferredNames = ['飙升榜', '新歌榜', '原创榜'];
      const preferred = preferredNames
        .map((name) => fromApi.find((item) => item?.name === name))
        .filter(Boolean);
      const merged = personalHotList ? [personalHotList, ...preferred] : [...preferred];
      for (const item of fromApi) {
        if (merged.length >= 4) break;
        if (!merged.some((entry) => String(entry?.id) === String(item?.id))) {
          merged.push(item);
        }
      }
      return merged.slice(0, 4);
    }

    const fallbackCards = [];
    if (personalHotList) {
      fallbackCards.push(personalHotList);
    }
    if (newCharts.length > 0) {
      fallbackCards.push({
        id: 2,
        name: '新歌榜',
        coverImgUrl: '',
        updateFrequency: '本地更新',
        tracks: newCharts.slice(0, 5).map((item) => ({
          title: item?.track || item?.title || '未知歌曲',
          artist: item?.artist || '未知歌手'
        }))
      });
    }
    return fallbackCards;
  }, [neteaseToplists, hotCharts, newCharts]);

  const heroCards = useMemo(() => {
    const baseTracks = recommendedTracks.slice(0, 3).map((track, index) => ({
      type: 'track',
      key: `track-${track?.id || track?.mcpTrackId || index}`,
      title: formatTrackTitle(track, '未命名歌曲'),
      subtitle: formatTrackArtist(track),
      meta: '精选推荐',
      track,
      style: getArtworkStyle(track, heroThemes[index % heroThemes.length])
    }));

    const cards = [...baseTracks];
    if (hotCharts[0]) {
      const hotTrack = getChartTrack(hotCharts[0], '热歌榜');
      cards.push({
        type: 'chart',
        key: 'hot-chart',
        title: '热歌榜',
        subtitle: hotTrack.title,
        meta: '听歌记录 Top5',
        track: hotTrack,
        style: getArtworkStyle(hotTrack, chartThemes[0])
      });
    }
    if (newCharts[0]) {
      const newTrack = getChartTrack(newCharts[0], '新歌榜');
      cards.push({
        type: 'chart',
        key: 'new-chart',
        title: '新歌榜',
        subtitle: newTrack.title,
        meta: '最新发布',
        track: newTrack,
        style: getArtworkStyle(newTrack, chartThemes[1])
      });
    }

    const listCovers = displayToplists.slice(0, 3).map((list, index) => {
      const firstTrack = toArray(list?.tracks)[0] || null;
      return {
        type: 'toplist',
        key: `toplist-cover-${list?.id || list?.name || index}`,
        title: firstTrack?.title || list?.name || '榜单精选',
        subtitle: firstTrack?.artist || list?.name || '网易云榜单',
        meta: '榜单推荐',
        track: {
          title: firstTrack?.title || list?.name || '榜单歌曲',
          artist: firstTrack?.artist || '未知歌手',
          album: list?.name || '榜单精选',
          genre: '榜单推荐'
        },
        style: getArtworkStyle(
          { artworkUrl: list?.coverImgUrl },
          heroThemes[(index + 2) % heroThemes.length]
        )
      };
    });
    cards.push(...listCovers);

    let normalized = cards.filter(Boolean).slice(0, 5);
    if (normalized.length === 0) {
      normalized = [
        {
          type: 'placeholder',
          key: 'placeholder-0',
          title: '场景推荐',
          subtitle: '发现你的今日歌单',
          meta: '推荐',
          track: { title: '场景推荐', artist: 'ScenePulse', album: '推荐', genre: '推荐' },
          style: { backgroundImage: heroThemes[0], backgroundSize: 'cover', backgroundPosition: 'center' }
        }
      ];
    }

    const filled = [...normalized];
    let cursor = 0;
    while (filled.length < 5) {
      const seed = normalized[cursor % normalized.length];
      filled.push({
        ...seed,
        key: `${seed.key}-dup-${filled.length}`
      });
      cursor += 1;
    }

    return filled.slice(0, 5);
  }, [hotCharts, newCharts, recommendedTracks, displayToplists]);

  useEffect(() => {
    if (heroCards.length <= 1) {
      setActiveHeroIndex(0);
      return undefined;
    }
    const timer = window.setInterval(() => setActiveHeroIndex((current) => (current + 1) % heroCards.length), 4200);
    return () => window.clearInterval(timer);
  }, [heroCards.length]);

  const visibleHeroCards = useMemo(() => {
    if (heroCards.length === 0) return [];
    if (heroCards.length === 1) return [{ position: 'active', card: heroCards[0], index: 0 }];

    const total = heroCards.length;
    const farPrevIndex = (activeHeroIndex - 2 + total) % total;
    const prevIndex = (activeHeroIndex - 1 + total) % total;
    const nextIndex = (activeHeroIndex + 1) % total;
    const farNextIndex = (activeHeroIndex + 2) % total;

    if (total === 2) {
      return [
        { position: 'active', card: heroCards[activeHeroIndex], index: activeHeroIndex },
        { position: 'next', card: heroCards[nextIndex], index: nextIndex }
      ];
    }

    if (total === 3) {
      return [
        { position: 'prev', card: heroCards[prevIndex], index: prevIndex },
        { position: 'active', card: heroCards[activeHeroIndex], index: activeHeroIndex },
        { position: 'next', card: heroCards[nextIndex], index: nextIndex }
      ];
    }

    return [
      { position: 'far-prev', card: heroCards[farPrevIndex], index: farPrevIndex },
      { position: 'prev', card: heroCards[prevIndex], index: prevIndex },
      { position: 'active', card: heroCards[activeHeroIndex], index: activeHeroIndex },
      { position: 'next', card: heroCards[nextIndex], index: nextIndex },
      { position: 'far-next', card: heroCards[farNextIndex], index: farNextIndex }
    ];
  }, [activeHeroIndex, heroCards]);

  const activeHeroCard = heroCards[activeHeroIndex] || null;

  const rotateRecommendationCards = () => {
    if (recommendedTracks.length <= 5) {
      loadRecommendations();
      return;
    }
    setRecommendationOffset((current) => (current + 5) % recommendedTracks.length);
  };

  const playToplistTrack = (toplist, track) => {
    if (!track) return;
    onPlay?.({
      id: track.localTrackId || undefined,
      mcpTrackId: track.neteaseTrackId ? String(track.neteaseTrackId) : undefined,
      title: track.title || '未知歌曲',
      artist: track.artist || '未知歌手',
      album: track.album || toplist?.name || '榜单推荐',
      artworkUrl: track.artworkUrl || toplist?.coverImgUrl || '',
      genre: '榜单推荐'
    });
  };

  const resolveToplistTrackForDetail = async (toplist, track) => {
    if (!track) return null;

    const localId = Number(track.localTrackId || 0);
    if (Number.isFinite(localId) && localId > 0) {
      try {
        const detail = await trackApi.detail(localId);
        if (detail?.id) return detail;
      } catch {
        // fallback to search below
      }
    }

    const keyword = `${track.title || ''} ${track.artist || ''}`.trim();
    if (keyword) {
      try {
        const payload = await trackApi.search(keyword, 0, 10);
        const items = toArray(payload?.items);
        const exact = items.find((item) => (item?.title || '') === (track.title || ''));
        const fuzzy = items.find((item) => (item?.title || '').includes(track.title || ''));
        const hit = exact || fuzzy || items[0];
        if (hit?.id) return hit;
      } catch {
        // ignore
      }
    }

    return {
      id: null,
      title: track.title || '未知歌曲',
      artist: track.artist || '未知歌手',
      album: track.album || toplist?.name || '榜单推荐'
    };
  };

  const openToplistTrackDetail = async (toplist, track) => {
    if (!track) return;
    const resolved = await resolveToplistTrackForDetail(toplist, track);
    if (resolved?.id) {
      onOpenDetail?.({ id: resolved.id });
      return;
    }
    if (resolved) {
      onPlay?.(resolved);
    }
  };

  const toFiveToplistRows = (list) => {
    const rows = toArray(list?.tracks).slice(0, 5);
    while (rows.length < 5) {
      rows.push({ __placeholder: true, title: '暂无歌曲', artist: '' });
    }
    return rows;
  };

  const openDaypartPlaylist = (group) => {
    if (!group?.key) return;
    navigate(`/playlists/daypart/${group.key}?scene=${encodeURIComponent(scene)}&emotion=${encodeURIComponent(emotion)}`, {
      state: {
        playlist: group,
        tracks: toArray(group.tracks),
        scene,
        emotion
      }
    });
  };

  const toggleGenre = (genre) => {
    setSelectedGenres((current) =>
      current.includes(genre) ? current.filter((item) => item !== genre) : [...current, genre].slice(0, 6)
    );
  };

  const savePreferences = async () => {
    if (!selectedGenres.length) {
      setError('请至少选择一个偏好标签');
      return;
    }
    setSavingPreferences(true);
    setError('');
    try {
      const data = await recommendationApi.saveOnboardingPreferences(selectedGenres, selectedGender);
      setResult({
        tracks: toArray(data?.tracks),
        hybridWeight: data?.hybridWeight || {},
        userStage: data?.userStage || 'NEW_USER',
        guest: Boolean(data?.guest),
        onboardingRequired: Boolean(data?.onboardingRequired),
        selectedGenres: toArray(data?.selectedGenres),
        onboardingOptions: toArray(data?.onboardingOptions),
        playCount30d: Number(data?.playCount30d || 0),
        summary: normalizeText(data?.summary, '')
      });
      await loadDaypartRecommendations(scene, emotion, 10);
      setRecommendationOffset(0);
      setOnboardingStep(2);
    } catch (requestError) {
      setError(getErrorMessage(requestError, '保存偏好失败，请稍后重试'));
    } finally {
      setSavingPreferences(false);
    }
  };

  const logoutGuest = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('userId');
    window.location.reload();
  };

  return (
    <div className="page">
      <section className="hero hero-discovery compact">
        <div className="hero-carousel-shell">
          {visibleHeroCards.length > 0 ? (
            <>
              <div className="hero-carousel-stage">
                {visibleHeroCards.map(({ position, card, index }) => (
                  <article
                    key={`${card.key}-${position}`}
                    className={`hero-slide-card hero-stage-card ${position === 'active' ? 'is-active' : 'is-side'} hero-pos-${position}`}
                    style={card.style}
                  >
                    <button
                      type="button"
                      className="hero-stage-hitbox"
                      onClick={() => setActiveHeroIndex(index)}
                      aria-label={`切换到卡片：${card.title}`}
                    />
                    <div className="hero-slide-overlay" />
                    <div className="hero-slide-copy">
                      <span className="cover-kicker">{card.meta}</span>
                      <h3>{card.title}</h3>
                      <p>{card.subtitle}</p>
                    </div>
                    <div className="hero-slide-actions">
                      <button type="button" className="hero-play-btn" onClick={() => onPlay(card.track)}>
                        播放
                      </button>
                      {card.type === 'track' ? (
                        <button type="button" className="hero-detail-btn" onClick={() => onOpenDetail?.(card.track)}>
                          详情
                        </button>
                      ) : null}
                    </div>
                  </article>
                ))}
              </div>

              {heroCards.length > 1 ? (
                <div className="hero-dots" aria-label="杞挱鍒嗛〉">
                  {heroCards.map((item, index) => (
                    <button
                      key={item.key}
                      type="button"
                      className={`hero-dot ${index === activeHeroIndex ? 'active' : ''}`}
                      aria-label={`切换到第 ${index + 1} 张`}
                      onClick={() => setActiveHeroIndex(index)}
                    />
                  ))}
                </div>
              ) : null}
            </>
          ) : (
            <article className="hero-slide-card hero-stage-card is-active" style={{ backgroundImage: heroThemes[0] }}>
              <div className="hero-slide-overlay" />
              <div className="hero-slide-copy">
                <span className="cover-kicker">为你推荐</span>
                <h3>你的个性化精选</h3>
                <p>根据当前场景与情绪，实时匹配合适的歌曲。</p>
              </div>
            </article>
          )}
        </div>

      </section>

      {isGuest ? (
        <section className="glass-card state-panel">
          <div>
            <span className="eyebrow">游客模式</span>
            <h3>登录后可解锁个性化推荐</h3>
            <p>当前仍可浏览排行榜与社区内容。</p>
          </div>
          <div className="state-actions">
            <button type="button" className="btn" onClick={logoutGuest}>杩斿洖鐧诲綍</button>
          </div>
        </section>
      ) : null}

      {!isGuest && onboardingRequired ? (
        <div className="modal-backdrop onboarding-backdrop">
          <section className="glass-card state-panel onboarding-modal">
            <div className="onboarding-hero">
              <div className="onboarding-copy">
                <span className="eyebrow">鏂版墜寮曞</span>
                <h3>{onboardingStepMeta[onboardingStep].title}</h3>
                <p>{onboardingStepMeta[onboardingStep].desc}</p>
              </div>
            </div>

            <div className="onboarding-stage">
              <div className="onboarding-section">
                <span className="chip soft">鎬у埆</span>
                <div className="row">
                  {genderOptions.map(([value, label]) => (
                    <button
                      type="button"
                      key={value}
                      className={`preference-chip ${selectedGender === value ? 'active' : ''}`}
                      onClick={() => setSelectedGender(value)}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="onboarding-section">
                <span className="chip soft">鍋忓ソ椋庢牸</span>
                <div className="preference-chip-grid">
                  {toArray(result?.onboardingOptions).map((genre) => (
                    <button
                      type="button"
                      key={genre}
                      className={`preference-chip ${selectedGenres.includes(genre) ? 'active' : ''}`}
                      onClick={() => toggleGenre(genre)}
                    >
                      {genre}
                    </button>
                  ))}
                </div>
              </div>

              <div className="state-actions">
                <div className="chips">
                  {selectedGenres.length > 0 ? (
                    selectedGenres.map((genre) => <span key={genre} className="genre-pill">{genre}</span>)
                  ) : (
                    <span className="chip soft">请至少选择一个标签</span>
                  )}
                </div>
                <button type="button" className="btn" onClick={savePreferences} disabled={savingPreferences}>
                  {savingPreferences ? '保存中...' : '保存偏好'}
                </button>
              </div>
            </div>
          </section>
        </div>
      ) : null}

      {!isGuest && !onboardingRequired ? (
        <>
          <section className="discover-block">
            <div className="discover-block-head">
              <h2>歌单推荐</h2>
            </div>
            <div className="glass-card">
            <div className="section-heading">
              <div>
                <span className="eyebrow">精选歌单</span>
                <h3>按时段推荐</h3>
              </div>
              {daypartResult?.summary ? <p className="section-supporting-text">{daypartResult.summary}</p> : null}
            </div>
            <div className="playlist-scroller">
              {curatedDayparts.map((group) => {
                const copy = getDaypartCopy(group);
                return (
                <article
                  className="playlist-card playlist-card-enhanced"
                  key={group.key}
                  role="button"
                  tabIndex={0}
                  onClick={() => openDaypartPlaylist(group)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      openDaypartPlaylist(group);
                    }
                  }}
                >
                  <div className="playlist-card-head">
                    <div>
                      <span className="eyebrow">{group.label}</span>
                      <h4>{copy.title}</h4>
                    </div>
                    <span className="tag">今日精选</span>
                  </div>
                  <p className="playlist-card-description">{copy.description}</p>
                  <div className="mini-track-list">
                    {toArray(group.tracks).length > 0 ? (
                      toArray(group.tracks).slice(0, 3).map((track) => (
                        <button
                          type="button"
                          className="mini-track"
                          key={track?.mcpTrackId || track?.id || `${group.key}-${track?.title}`}
                          onClick={(event) => {
                            event.stopPropagation();
                            onPlay(track);
                          }}
                        >
                          <span>{formatTrackTitle(track)}</span>
                          <small>{formatTrackArtist(track)}</small>
                        </button>
                      ))
                    ) : (
                      <div className="mini-track mini-track-empty"><span>正在生成更多歌曲</span></div>
                    )}
                  </div>
                </article>
                );
              })}
            </div>
            </div>
          </section>

          <section className="discover-block">
            <div className="discover-block-head">
              <div>
                <h2>歌曲推荐</h2>
                <span>为你挑选适合现在播放的歌曲</span>
              </div>
              <button
                type="button"
                className="batch-refresh-btn"
                onClick={rotateRecommendationCards}
                disabled={loading || visibleRecommendedTracks.length === 0}
              >
                换一批 · 点击刷新
              </button>
            </div>
            <div className="song-shelf-toolbar">
              <div className="song-shelf-tags">
                {songShelfTags.map((tag) => (
                  <span className="music-filter-chip" key={tag}>{tag}</span>
                ))}
              </div>
              <span>{visibleRecommendedTracks.length} 首</span>
            </div>
          </section>

          <section className="track-grid track-row-scroller">
            {visibleRecommendedTracks.length > 0 ? (
              visibleRecommendedTracks.map((track, index) => (
                <article className="track-card fade-card" key={track?.mcpTrackId || track?.id || index} style={{ animationDelay: `${index * 60}ms` }}>
                  <div className="card-artwork-shell">
                    <div className="card-artwork" style={getArtworkStyle(track, heroThemes[index % heroThemes.length])}>
                      <div className="card-artwork-shade" />
                      <div className="card-artwork-meta">
                        <span className="cover-kicker">推荐</span>
                        <strong>{formatTrackTitle(track)}</strong>
                        <span>{formatTrackArtist(track)}</span>
                      </div>
                      <button type="button" className="cover-play-btn" onClick={() => onPlay(track)} aria-label={`播放 ${formatTrackTitle(track)}`}>
                        播放
                      </button>
                    </div>
                  </div>
                  <div className="track-card-body">
                    <h4>{formatTrackTitle(track)}</h4>
                    <p>{formatTrackArtist(track)}</p>
                  </div>
                  <div className="track-card-actions">
                    <button type="button" className="btn subtle" onClick={() => onPlay(track)}>播放</button>
                    <button type="button" className="btn subtle" onClick={() => onOpenDetail?.(track)}>详情</button>
                  </div>
                </article>
              ))
            ) : !loading ? (
              <section className="glass-card"><p>当前暂无推荐歌曲</p></section>
            ) : null}
          </section>
        </>
      ) : null}

      <section className="discover-block">
        <div className="discover-block-head">
          <h2>榜单精选</h2>
        </div>
        {displayToplists.length > 0 ? (
          <div className="toplist-grid">
            {displayToplists.map((list, listIndex) => (
              <article className="toplist-card" key={`toplist-${list.id || list.name}`}>
                <div className="toplist-head">
                  <div className="toplist-cover-wrap">
                    <img
                      className="toplist-cover-icon"
                      src={toplistIcons[listIndex % toplistIcons.length]}
                      alt=""
                      aria-hidden="true"
                    />
                  </div>
                  <div className="toplist-meta">
                    <h3>{list.name || '未命名榜单'}</h3>
                  </div>
                </div>

                <div className="toplist-track-list">
                  {toFiveToplistRows(list).map((track, index) => (
                    <div
                      key={`${list.id || list.name}-${index}-${track.title || 'track'}`}
                      className={`toplist-track-row ${track.__placeholder ? 'is-placeholder' : ''}`}
                    >
                      <span className="toplist-rank">{index + 1}</span>
                      <span className="toplist-track-copy">
                        <strong>{track.title || '未知歌曲'}</strong>
                        <small>{track.artist || '未知歌手'}</small>
                      </span>
                      <span className="toplist-track-actions">
                        <button
                          type="button"
                          className="btn subtle small icon-only"
                          onClick={() => playToplistTrack(list, track)}
                          disabled={Boolean(track.__placeholder)}
                          aria-label={`播放 ${track.title || '未知歌曲'}`}
                        >
                          <img src={playIcon} alt="" aria-hidden="true" />
                        </button>
                        <button
                          type="button"
                          className="btn subtle small icon-only"
                          onClick={() => openToplistTrackDetail(list, track)}
                          disabled={Boolean(track.__placeholder)}
                          aria-label={`查看 ${track.title || '未知歌曲'} 详情`}
                        >
                          <img src={detailIcon} alt="" aria-hidden="true" />
                        </button>
                      </span>
                    </div>
                  ))}
                </div>
              </article>
            ))}
          </div>
        ) : (
          <section className="glass-card"><p>暂时无法获取榜单数据</p></section>
        )}
      </section>


      <section className="discover-block">
        <div className="discover-block-head">
          <h2>探索社区</h2>
          <span>看看大家在听什么</span>
        </div>
        <CommunityPage embedded onPlay={onPlay} onOpenDetail={onOpenDetail} />
      </section>
    </div>
  );
}

