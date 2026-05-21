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
import { recommendationApi, trackApi, userApi } from './api/services';
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

const playbackLookupId = (track = {}) => track.mcpTrackId || track.id;

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
  const [currentUser, setCurrentUser] = useState(() => ({
    username: localStorage.getItem('username') || localStorage.getItem('userId') || '游客',
    avatarUrl: localStorage.getItem('avatarUrl') || ''
  }));

  const username = currentUser?.username || localStorage.getItem('username') || localStorage.getItem('userId') || '游客';
  const avatarUrl = currentUser?.avatarUrl || localStorage.getItem('avatarUrl') || '';

  const currentFeedback = currentTrack?.id ? trackFeedback[String(currentTrack.id)] || {} : {};
  const currentRating = Number(currentFeedback?.rating || 0);
  const currentLiked = Boolean(currentFeedback?.liked);

  useEffect(() => {
    setMenuOpen(false);
    setNavSuggestions([]);
    setNavSearchOpen(false);
  }, [location.pathname, location.search]);

  useEffect(() => {
    if (!authed) return undefined;
    let cancelled = false;

    const applyUser = (profile) => {
      const nextUser = {
        username: profile?.username || localStorage.getItem('username') || localStorage.getItem('userId') || '游客',
        avatarUrl: profile?.avatarUrl || ''
      };
      setCurrentUser(nextUser);
      localStorage.setItem('username', nextUser.username);
      if (nextUser.avatarUrl) {
        localStorage.setItem('avatarUrl', nextUser.avatarUrl);
      } else {
        localStorage.removeItem('avatarUrl');
      }
    };

    const loadUser = async () => {
      try {
        const profile = await userApi.me();
        if (!cancelled) applyUser(profile);
      } catch {
        if (!cancelled) {
          setCurrentUser({
            username: localStorage.getItem('username') || localStorage.getItem('userId') || '游客',
            avatarUrl: localStorage.getItem('avatarUrl') || ''
          });
        }
      }
    };

    const handleUserUpdated = (event) => applyUser(event.detail);

    loadUser();
    window.addEventListener('music:user-updated', handleUserUpdated);
    return () => {
      cancelled = true;
      window.removeEventListener('music:user-updated', handleUserUpdated);
    };
  }, [authed]);

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

  useEffect(() => {
    if (!authed) return undefined;
    let cancelled = false;

    const syncFavoriteFeedback = async () => {
      try {
        const favoriteTracks = await trackApi.favorites(100);
        if (cancelled) return;
        const rows = toArray(favoriteTracks);
        if (!rows.length) return;

        setTrackFeedback((current) => {
          let changed = false;
          const next = { ...current };
          rows.forEach((track) => {
            if (!track?.id) return;
            const key = String(track.id);
            const currentFeedback = next[key] || {};
            const rating = track.rating ?? currentFeedback.rating ?? null;
            if (currentFeedback.liked !== true || currentFeedback.rating !== rating) {
              next[key] = {
                ...currentFeedback,
                liked: true,
                rating
              };
              changed = true;
            }
          });
          return changed ? next : current;
        });
      } catch (error) {
        console.error('Operation failed', error);
      }
    };

    syncFavoriteFeedback();
    window.addEventListener('music:library-updated', syncFavoriteFeedback);
    return () => {
      cancelled = true;
      window.removeEventListener('music:library-updated', syncFavoriteFeedback);
    };
  }, [authed]);

  if (!authed) {
    return <AuthPage onAuthed={() => setAuthed(true)} />;
  }

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('userId');
    localStorage.removeItem('username');
    localStorage.removeItem('avatarUrl');
    setCurrentTrack(null);
    setCurrentUser({ username: '游客', avatarUrl: '' });
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
        trackId: playbackLookupId(track) || 1,
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

    try {
      const lookupId = playbackLookupId(normalized);
      if (lookupId) {
        const refreshed = await trackApi.refreshPlayback(lookupId);
        const refreshedTrack = normalizeTrack(refreshed || normalized);
        if (refreshedTrack.audioUrl) {
          return refreshedTrack;
        }

        const detail = await trackApi.detail(lookupId);
        const detailTrack = normalizeTrack(detail || refreshedTrack);
        if (detailTrack.audioUrl) {
          return detailTrack;
        }

        return detailTrack;
      }

      return normalized;
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
              {avatarUrl ? <img src={avatarUrl} alt="头像" /> : <span>{String(username).slice(0, 1).toUpperCase()}</span>}
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
                onToggleFavorite={toggleFavoriteTrack}
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
