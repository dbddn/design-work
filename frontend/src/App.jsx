import { Navigate, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useEffect, useMemo, useState } from 'react';
import AuthPage from './pages/AuthPage';
import HomePage from './pages/HomePage';
import SearchPage from './pages/SearchPage';
import ExplorePage from './pages/ExplorePage';
import ProfilePage from './pages/ProfilePage';
import DaypartPlaylistPage from './pages/DaypartPlaylistPage';
import ArtistPage from './pages/ArtistPage';
import TrackDetailPage from './pages/TrackDetailPage';
import PlaybackHistoryPage from './pages/PlaybackHistoryPage';
import CommunityPage from './pages/CommunityPage';
import PlayerBar from './components/PlayerBar';
import AssistantWidget from './components/AssistantWidget';
import { recommendationApi, trackApi } from './api/services';
import { toArray } from './utils/view';

const navItems = [
  ['/', '发现'],
  ['/explore', '时光机'],
  ['/profile', '我的音乐']
];

const LAST_TRACK_KEY = 'player:last-track';
const PLAY_QUEUE_KEY = 'player:queue';
const PLAY_QUEUE_INDEX_KEY = 'player:queue-index';
const TRACK_FEEDBACK_KEY = 'player:track-feedback';

const defaultLyrics = [
  'ScenePulse 记录你的每一段音乐时光。',
  '推荐会随着你的听歌偏好持续变化。',
  '每一次播放，都可能开启新的情绪节奏。'
];

const readStoredJson = (key, fallback) => {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return fallback;
    return JSON.parse(raw);
  } catch {
    return fallback;
  }
};

const normalizeTrack = (track = {}) => ({
  ...track,
  id: track.id || track.mcpTrackId || Date.now(),
  title: track.title || track.track || '未命名歌曲',
  artist: track.artist || track.singer || '未知歌手',
  album: track.album || 'MCP 歌单',
  genre: typeof track.genre === 'string' ? track.genre : '',
  duration:
    Number.isFinite(Number(track.durationSec)) && Number(track.durationSec) > 0
      ? Number(track.durationSec)
      : Number.isFinite(Number(track.duration)) && Number(track.duration) > 0
        ? Number(track.duration)
        : 214,
  audioUrl: typeof track.audioUrl === 'string' ? track.audioUrl : '',
  artworkUrl: typeof track.artworkUrl === 'string' ? track.artworkUrl : '',
  description:
    typeof track.description === 'string' && track.description.trim()
      ? track.description.trim()
      : '暂无歌曲简介。',
  artwork:
    track.artwork ||
    'linear-gradient(135deg, rgba(0,122,255,0.96), rgba(97,189,255,0.82) 55%, rgba(255,255,255,0.94))',
  lyrics:
    Array.isArray(track.lyrics) && track.lyrics.length
      ? track.lyrics.filter((line) => typeof line === 'string' && line.trim())
      : defaultLyrics,
  source: track.source || 'LOCAL_DB'
});

const normalizeTrackOrNull = (track) => {
  if (!track || typeof track !== 'object') return null;
  return normalizeTrack(track);
};

export default function App() {
  const navigate = useNavigate();
  const location = useLocation();
  const [authed, setAuthed] = useState(Boolean(localStorage.getItem('token')));
  const [currentTrack, setCurrentTrack] = useState(() => normalizeTrackOrNull(readStoredJson(LAST_TRACK_KEY, null)));
  const [playQueue, setPlayQueue] = useState(() => toArray(readStoredJson(PLAY_QUEUE_KEY, [])).map(normalizeTrack));
  const [queueIndex, setQueueIndex] = useState(() => Number(readStoredJson(PLAY_QUEUE_INDEX_KEY, 0)) || 0);
  const [trackFeedback, setTrackFeedback] = useState(() => readStoredJson(TRACK_FEEDBACK_KEY, {}));
  const [navQuery, setNavQuery] = useState('');
  const [navSuggestions, setNavSuggestions] = useState([]);
  const [navSearchOpen, setNavSearchOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);

  const username = useMemo(
    () => localStorage.getItem('username') || localStorage.getItem('userId') || '游客',
    [authed]
  );

  const currentFeedback = currentTrack?.id ? trackFeedback[String(currentTrack.id)] || {} : {};
  const currentRating = Number(currentFeedback?.rating || 0);
  const currentLiked = Boolean(currentFeedback?.liked);

  useEffect(() => {
    setMenuOpen(false);
    setNavSuggestions([]);
    setNavSearchOpen(false);
  }, [location.pathname, location.search]);

  useEffect(() => {
    const timer = window.setTimeout(async () => {
      if (!navSearchOpen) {
        setNavSuggestions([]);
        return;
      }
      const keyword = navQuery.trim();
      if (!keyword) {
        setNavSuggestions([]);
        return;
      }
      try {
        const payload = await trackApi.search(keyword, 0, 6);
        setNavSuggestions(toArray(payload?.items).slice(0, 6));
      } catch (error) {
        console.error('Operation failed', error);
        setNavSuggestions([]);
      }
    }, 180);
    return () => window.clearTimeout(timer);
  }, [navQuery, navSearchOpen]);

  useEffect(() => {
    if (currentTrack) {
      localStorage.setItem(LAST_TRACK_KEY, JSON.stringify(currentTrack));
    }
  }, [currentTrack]);

  useEffect(() => {
    localStorage.setItem(PLAY_QUEUE_KEY, JSON.stringify(playQueue));
  }, [playQueue]);

  useEffect(() => {
    localStorage.setItem(PLAY_QUEUE_INDEX_KEY, JSON.stringify(queueIndex));
  }, [queueIndex]);

  useEffect(() => {
    localStorage.setItem(TRACK_FEEDBACK_KEY, JSON.stringify(trackFeedback));
  }, [trackFeedback]);

  if (!authed) {
    return <AuthPage onAuthed={() => setAuthed(true)} />;
  }

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('userId');
    localStorage.removeItem('username');
    setCurrentTrack(null);
    setAuthed(false);
  };

  const submitNavSearch = (keyword = navQuery) => {
    const value = keyword.trim();
    if (!value) return;
    setNavSuggestions([]);
    setNavSearchOpen(false);
    navigate(`/search?q=${encodeURIComponent(value)}`);
  };

  const rememberTrackInQueue = (track) => {
    const normalized = normalizeTrack(track);
    setPlayQueue((current) => {
      const next = [...current.filter((item) => String(item?.id) !== String(normalized.id)), normalized].slice(-30);
      setQueueIndex(next.length - 1);
      return next;
    });
  };

  const recordPlayEvent = async (track) => {
    try {
      await trackApi.playerEvent({
        trackId: track?.id || 1,
        eventType: 'play',
        progressSec: 0,
        completed: false
      });
    } catch (error) {
      console.error('Operation failed', error);
    }
  };

  const resolvePlayableTrack = async (track) => {
    const normalized = normalizeTrack(track);

    const findPlayableBySearch = async () => {
      const keyword = `${normalized.title || ''} ${normalized.artist || ''}`.trim();
      if (!keyword) return null;

      const searchPayload = await trackApi.search(keyword, 0, 10);
      const candidates = toArray(searchPayload?.items);

      for (const item of candidates) {
        if (!item?.id) continue;
        try {
          const detail = await trackApi.detail(item.id);
          const detailTrack = normalizeTrack(detail || item);
          if (detailTrack.audioUrl) {
            return detailTrack;
          }
        } catch {
          // ignore and continue
        }
      }

      const first = candidates[0];
      if (!first) return null;
      if (first.id) {
        const detail = await trackApi.detail(first.id);
        return normalizeTrack(detail || first);
      }
      return normalizeTrack(first);
    };

    try {
      if (normalized.id) {
        const refreshed = await trackApi.refreshPlayback(normalized.id);
        const refreshedTrack = normalizeTrack(refreshed || normalized);
        if (refreshedTrack.audioUrl) {
          return refreshedTrack;
        }

        const detail = await trackApi.detail(normalized.id);
        const detailTrack = normalizeTrack(detail || refreshedTrack);
        if (detailTrack.audioUrl) {
          return detailTrack;
        }

        const searchedTrack = await findPlayableBySearch();
        return searchedTrack || detailTrack;
      }

      if (normalized.title) {
        const searchedTrack = await findPlayableBySearch();
        if (searchedTrack) {
          return searchedTrack;
        }
      }
    } catch (error) {
      console.error('Operation failed', error);
    }

    return normalized;
  };

  const playTrack = async (track, options = {}) => {
    const resolvedTrack = await resolvePlayableTrack(track);
    setCurrentTrack(resolvedTrack);
    if (options.appendToQueue !== false) {
      rememberTrackInQueue(resolvedTrack);
    }
    await recordPlayEvent(resolvedTrack);
  };

  const openTrackDetail = (track) => {
    const normalized = normalizeTrack(track);
    if (!normalized.id) return;
    navigate(`/tracks/${normalized.id}`);
  };

  const navigateQueue = async (direction) => {
    if (playQueue.length === 0) return;
    const nextIndex = (queueIndex + direction + playQueue.length) % playQueue.length;
    const nextTrack = playQueue[nextIndex];
    if (!nextTrack) return;
    setQueueIndex(nextIndex);
    await playTrack(nextTrack, { appendToQueue: false });
  };

  const updateTrackFeedbackState = (trackId, patch) => {
    setTrackFeedback((current) => ({
      ...current,
      [String(trackId)]: {
        ...(current[String(trackId)] || {}),
        ...patch
      }
    }));
    window.dispatchEvent(new CustomEvent('music:library-updated'));
  };

  const toggleFavoriteTrack = async (track = currentTrack) => {
    if (!track?.id) return;
    const nextLiked = !(trackFeedback[String(track.id)]?.liked);
    const rating = trackFeedback[String(track.id)]?.rating || null;
    try {
      await recommendationApi.feedback({
        trackId: track.id,
        rating,
        liked: nextLiked,
        skipped: false
      });
      updateTrackFeedbackState(track.id, { liked: nextLiked, rating });
    } catch (error) {
      console.error('Operation failed', error);
    }
  };

  const rateTrack = async (rating) => {
    if (!currentTrack?.id || !rating) return;
    const liked = Boolean(trackFeedback[String(currentTrack.id)]?.liked);
    try {
      await recommendationApi.feedback({
        trackId: currentTrack.id,
        rating,
        liked,
        skipped: false
      });
      updateTrackFeedbackState(currentTrack.id, { rating, liked });
    } catch (error) {
      console.error('Operation failed', error);
    }
  };

  const rateSpecificTrack = async (track, rating) => {
    if (!track?.id || !rating) return;
    const liked = Boolean(trackFeedback[String(track.id)]?.liked);
    try {
      await recommendationApi.feedback({
        trackId: track.id,
        rating,
        liked,
        skipped: false
      });
      updateTrackFeedbackState(track.id, { rating, liked });
    } catch (error) {
      console.error('Operation failed', error);
    }
  };

  const skipTrack = async () => {
    if (!currentTrack) return;
    try {
      await recommendationApi.feedback({
        trackId: currentTrack.id || 1,
        rating: 1,
        liked: false,
        skipped: true
      });
      updateTrackFeedbackState(currentTrack.id, { ...(trackFeedback[String(currentTrack.id)] || {}), skipped: true });
    } catch (error) {
      console.error('Operation failed', error);
    }
    await navigateQueue(1);
  };

  return (
    <div className="app-shell">
      <header className="topbar glass-panel">
        <div className="brand-lockup" onClick={() => navigate('/')} role="button" tabIndex={0}>
          <div className="brand-mark">S</div>
          <div className="brand-copy">
            <strong>ScenePulse</strong>
          </div>
        </div>

        <nav className="topnav">
          {navItems.map(([to, label]) => (
            <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => (isActive ? 'active' : '')}>
              {label}
            </NavLink>
          ))}
        </nav>

        <div className="nav-search-wrap">
          <form
            className="nav-search"
            onSubmit={(event) => {
              event.preventDefault();
              submitNavSearch();
            }}
          >
            <input
              value={navQuery}
              onChange={(event) => {
                setNavQuery(event.target.value);
                setNavSearchOpen(Boolean(event.target.value.trim()));
              }}
              onFocus={() => {
                if (navQuery.trim()) {
                  setNavSearchOpen(true);
                }
              }}
              placeholder="搜索歌曲 / 歌手 / 专辑"
            />
            <button className="nav-search-btn" type="submit">搜索</button>
          </form>
          {navSearchOpen && navSuggestions.length > 0 && (
            <div className="nav-search-panel">
              {navSuggestions.map((track) => (
                <button
                  type="button"
                  className="nav-search-item"
                  key={track.mcpTrackId || track.id}
                  onClick={() => {
                    setNavQuery(track.title || '');
                    setNavSearchOpen(false);
                    submitNavSearch(track.title || '');
                  }}
                >
                  <strong>{track.title || '未命名歌曲'}</strong>
                  <span>{track.artist || '未知歌手'}</span>
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="topbar-actions">
          <div className="avatar-menu">
            <button className="avatar-btn" onClick={() => setMenuOpen((value) => !value)} aria-label="用户菜单">
              <span>{String(username).slice(0, 1).toUpperCase()}</span>
            </button>
            {menuOpen && (
              <div className="avatar-panel">
                <strong>{username}</strong>
                <button className="btn subtle" onClick={logout}>退出登录</button>
              </div>
            )}
          </div>
        </div>
      </header>

      <main className="content-shell">
        <Routes>
          <Route path="/" element={<HomePage onPlay={playTrack} onOpenDetail={openTrackDetail} />} />
          <Route path="/search" element={<SearchPage onPlay={playTrack} onOpenDetail={openTrackDetail} />} />
          <Route
            path="/artists/:artistId"
            element={<ArtistPage onPlay={playTrack} onOpenDetail={openTrackDetail} currentTrack={currentTrack} />}
          />
          <Route
            path="/tracks/:trackId"
            element={
              <TrackDetailPage
                onPlay={playTrack}
                currentTrack={currentTrack}
                trackFeedback={trackFeedback}
                onRateTrack={rateSpecificTrack}
              />
            }
          />
          <Route path="/analytics" element={<Navigate to="/profile" replace />} />
          <Route path="/explore" element={<ExplorePage onPlay={playTrack} onOpenDetail={openTrackDetail} />} />
          <Route path="/charts" element={<Navigate to="/" replace />} />
          <Route path="/community" element={<CommunityPage onPlay={playTrack} onOpenDetail={openTrackDetail} />} />
          <Route
            path="/profile"
            element={
              <ProfilePage
                onPlay={playTrack}
                onOpenDetail={openTrackDetail}
                onToggleFavorite={toggleFavoriteTrack}
                currentTrack={currentTrack}
                trackFeedback={trackFeedback}
              />
            }
          />
          <Route
            path="/history"
            element={<PlaybackHistoryPage onPlay={playTrack} onOpenDetail={openTrackDetail} currentTrack={currentTrack} />}
          />
          <Route
            path="/playlists/daypart/:daypartKey"
            element={<DaypartPlaylistPage onPlay={playTrack} onOpenDetail={openTrackDetail} currentTrack={currentTrack} />}
          />
        </Routes>
      </main>

      <PlayerBar
        track={currentTrack}
        onPrev={() => navigateQueue(-1)}
        onNext={() => navigateQueue(1)}
        onSkip={skipTrack}
        onToggleFavorite={() => toggleFavoriteTrack(currentTrack)}
        onOpenDetail={openTrackDetail}
        isFavorited={currentLiked}
      />
      <AssistantWidget currentTrack={currentTrack} onPlay={playTrack} onOpenDetail={openTrackDetail} />
    </div>
  );
}
