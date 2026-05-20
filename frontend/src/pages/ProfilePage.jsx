import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import EChartCard from '../components/EChartCard';
import { analyticsApi, playlistApi, trackApi, userApi } from '../api/services';
import { toGenrePieOption, toHeatmapOption, toRadarOption } from '../utils/chartTransform';
import { formatDateTime, getErrorMessage, toArray } from '../utils/view';

const genderLabels = {
  male: '男',
  female: '女',
  unknown: '暂未设置'
};

const chartRanges = [7, 30, 90];

export default function ProfilePage({
  onPlay,
  onOpenDetail,
  onToggleFavorite,
  currentTrack,
  trackFeedback = {}
}) {
  const navigate = useNavigate();
  const [profile, setProfile] = useState(null);
  const [stats, setStats] = useState(null);
  const [history, setHistory] = useState([]);
  const [favoriteTracks, setFavoriteTracks] = useState([]);
  const [heatmap, setHeatmap] = useState([]);
  const [genres, setGenres] = useState([]);
  const [playlists, setPlaylists] = useState({ created: [], favorites: [] });
  const [playlistName, setPlaylistName] = useState('');
  const [selectedPlaylist, setSelectedPlaylist] = useState(null);
  const [selectedPlaylistTracks, setSelectedPlaylistTracks] = useState([]);
  const [loadingPlaylistTracks, setLoadingPlaylistTracks] = useState(false);
  const [creatingPlaylist, setCreatingPlaylist] = useState(false);
  const [msg, setMsg] = useState('');
  const [error, setError] = useState('');
  const [savingProfile, setSavingProfile] = useState(false);
  const [chartDays, setChartDays] = useState(30);
  const [form, setForm] = useState({
    username: '',
    email: '',
    timezone: 'Asia/Shanghai',
    gender: 'unknown',
    ageRange: '',
    province: '',
    avatarUrl: '',
    bio: ''
  });

  const loadBase = async () => {
    const [profileData, statsData, historyData, favoriteData, playlistData] = await Promise.all([
      userApi.me(),
      userApi.stats(),
      trackApi.history(40),
      trackApi.favorites(40),
      playlistApi.mine()
    ]);

    const nextProfile = profileData || null;
    setProfile(nextProfile);
    setStats(statsData || null);
    setHistory(toArray(historyData).slice(0, 40));
    setFavoriteTracks(toArray(favoriteData));
    setPlaylists({
      created: toArray(playlistData?.created),
      favorites: toArray(playlistData?.favorites)
    });
    setForm({
      username: nextProfile?.username || '',
      email: nextProfile?.email || '',
      timezone: nextProfile?.timezone || 'Asia/Shanghai',
      gender: nextProfile?.gender || 'unknown',
      ageRange: nextProfile?.ageRange || '',
      province: nextProfile?.province || '',
      avatarUrl: nextProfile?.avatarUrl || '',
      bio: nextProfile?.bio || ''
    });

    if (nextProfile?.username) {
      localStorage.setItem('username', nextProfile.username);
    }
  };

  const loadCharts = async (days) => {
    const [heatmapData, genreData] = await Promise.all([
      analyticsApi.heatmap(days),
      analyticsApi.genres(days)
    ]);
    setHeatmap(toArray(heatmapData));
    setGenres(toArray(genreData));
  };

  const loadAll = async (days = chartDays) => {
    setError('');
    try {
      await Promise.all([loadBase(), loadCharts(days)]);
    } catch (requestError) {
      setProfile(null);
      setStats(null);
      setHistory([]);
      setFavoriteTracks([]);
      setHeatmap([]);
      setGenres([]);
      setPlaylists({ created: [], favorites: [] });
      setError(getErrorMessage(requestError, '个人中心加载失败，请稍后重试'));
    }
  };

  useEffect(() => {
    loadAll(chartDays);
  }, []);

  useEffect(() => {
    loadCharts(chartDays).catch((requestError) => {
      setError(getErrorMessage(requestError, '图表数据加载失败，请稍后重试'));
    });
  }, [chartDays]);

  useEffect(() => {
    const handler = () => {
      loadBase().catch((requestError) => {
        setError(getErrorMessage(requestError, '个人中心数据刷新失败，请稍后重试'));
      });
    };
    window.addEventListener('music:library-updated', handler);
    return () => window.removeEventListener('music:library-updated', handler);
  }, []);

  const saveProfile = async () => {
    setSavingProfile(true);
    setError('');
    setMsg('');

    try {
      const saved = await userApi.update({
        username: form.username.trim(),
        email: form.email.trim(),
        timezone: form.timezone.trim(),
        gender: form.gender,
        ageRange: form.ageRange.trim(),
        province: form.province.trim(),
        avatarUrl: form.avatarUrl.trim(),
        bio: form.bio.trim()
      });

      setProfile(saved || null);
      if (saved?.username) {
        localStorage.setItem('username', saved.username);
      }
      setMsg('个人资料已更新。');
    } catch (requestError) {
      setError(getErrorMessage(requestError, '个人资料保存失败，请稍后重试'));
    } finally {
      setSavingProfile(false);
    }
  };

  const createPlaylist = async () => {
    const name = playlistName.trim();
    if (!name) return;
    setCreatingPlaylist(true);
    setMsg('');
    setError('');
    try {
      await playlistApi.create(name);
      setPlaylistName('');
      setMsg('新歌单已创建。');
      await loadBase();
    } catch (requestError) {
      setError(getErrorMessage(requestError, '新建歌单失败，请稍后重试'));
    } finally {
      setCreatingPlaylist(false);
    }
  };

  const toggleFavoritePlaylist = async (playlist) => {
    try {
      await playlistApi.favorite(playlist.id, !playlist.favorited);
      await loadBase();
    } catch (requestError) {
      setError(getErrorMessage(requestError, '歌单收藏状态更新失败，请稍后重试'));
    }
  };

  const openPlaylist = async (playlist) => {
    if (!playlist?.id) return;
    setSelectedPlaylist(playlist);
    setSelectedPlaylistTracks([]);
    setLoadingPlaylistTracks(true);
    setError('');
    try {
      const rows = await playlistApi.tracks(playlist.id, 100);
      setSelectedPlaylistTracks(toArray(rows));
    } catch (requestError) {
      setError(getErrorMessage(requestError, '歌单歌曲加载失败，请稍后重试'));
    } finally {
      setLoadingPlaylistTracks(false);
    }
  };

  const favoriteCount = useMemo(
    () => favoriteTracks.filter((item) => trackFeedback[String(item?.id)]?.liked === true).length,
    [favoriteTracks, trackFeedback]
  );

  const manualCreatedPlaylists = useMemo(
    () => toArray(playlists.created).filter((playlist) => {
      const sceneTag = String(playlist?.sceneTag || '').toLowerCase();
      return sceneTag === 'personal' || !sceneTag;
    }),
    [playlists.created]
  );

  const favoritedPlaylists = useMemo(
    () => toArray(playlists.favorites).filter((playlist) => Boolean(playlist?.favorited)),
    [playlists.favorites]
  );

  const visibleFavoriteTracks = useMemo(
    () => favoriteTracks.filter((item) => trackFeedback[String(item?.id)]?.liked === true),
    [favoriteTracks, trackFeedback]
  );

  const historyPreview = useMemo(() => history.slice(0, 3), [history]);

  return (
    <div className="page profile-page">
      <section className="hero hero-profile compact profile-hero-compact">
        <div className="profile-head glass-panel profile-head-compact">
          <div className="profile-head-main">
            <span className="eyebrow">个人中心</span>
            <strong>@{profile?.username || '听歌用户'}</strong>
            <p>
              {profile?.email || '未绑定邮箱'} · {profile?.timezone || 'Asia/Shanghai'}
            </p>
          </div>
          <div className="chips profile-head-chips">
            <span className="genre-pill">{genderLabels[profile?.gender] || '暂未设置'}</span>
            {profile?.province ? <span className="chip soft">{profile.province}</span> : null}
          </div>
        </div>
      </section>

      {error ? (
        <section className="glass-card">
          <p>{error}</p>
        </section>
      ) : null}

      <div className="metric-grid profile-metric-grid">
        <section className="metric-card profile-metric-card">
          <span className="eyebrow">7 天</span>
          <h3>{stats?.playCount7d || 0}</h3>
          <p>近一周播放</p>
        </section>
        <section className="metric-card profile-metric-card">
          <span className="eyebrow">30 天</span>
          <h3>{stats?.playCount30d || 0}</h3>
          <p>近一月播放</p>
        </section>
        <section className="metric-card profile-metric-card">
          <span className="eyebrow">收藏</span>
          <h3>{favoriteCount}</h3>
          <p>已收藏歌曲</p>
        </section>
        <section className="metric-card profile-metric-card">
          <span className="eyebrow">跳过率</span>
          <h3>{((stats?.skipRate30d || 0) * 100).toFixed(1)}%</h3>
          <p>近期跳过占比</p>
        </section>
      </div>

      <div className="dashboard-grid profile-playlist-grid">
        <section className="glass-card">
          <div className="section-heading">
            <div>
              <span className="eyebrow">歌单管理</span>
              <h3>创建与收藏歌单</h3>
            </div>
          </div>

          <div className="track-playlist-create">
            <input
              value={playlistName}
              onChange={(event) => setPlaylistName(event.target.value)}
              placeholder="输入歌单名称，例如：今晚循环"
            />
            <button type="button" className="btn" onClick={createPlaylist} disabled={creatingPlaylist}>
              {creatingPlaylist ? '创建中...' : '创建歌单'}
            </button>
          </div>

          <div className="profile-playlist-list">
            {manualCreatedPlaylists.length > 0 ? (
              manualCreatedPlaylists.map((playlist) => (
                <article className="profile-playlist-item" key={`created-${playlist.id}`}>
                  <div className="profile-playlist-copy">
                    <strong>{playlist.name}</strong>
                    <p>{playlist.description || '用户自建歌单'}</p>
                    <div className="chips">
                      <span className="genre-pill">{playlist.songCount || 0} 首歌曲</span>
                      <span className="chip soft">{playlist.favoriteCount || 0} 人收藏</span>
                    </div>
                  </div>
                  <button type="button" className="btn subtle small" onClick={() => toggleFavoritePlaylist(playlist)}>
                    {playlist.favorited ? '取消收藏' : '收藏歌单'}
                  </button>
                  <button type="button" className="btn subtle small" onClick={() => openPlaylist(playlist)}>
                    查看
                  </button>
                </article>
              ))
            ) : (
              <p>还没有自建歌单，先创建一个吧。</p>
            )}
          </div>
        </section>

        <section className="glass-card">
          <div className="section-heading">
            <div>
              <span className="eyebrow">收藏歌曲</span>
              <h3>收藏歌曲列表</h3>
            </div>
          </div>
          <div className="profile-favorite-list">
            {visibleFavoriteTracks.length > 0 ? (
              visibleFavoriteTracks.map((track) => {
                const isPlaying = currentTrack?.id === track?.id || currentTrack?.mcpTrackId === track?.mcpTrackId;
                const likedState = trackFeedback[String(track?.id)]?.liked === true;
                return (
                  <article className={`profile-favorite-item ${isPlaying ? 'is-playing' : ''}`} key={`favorite-track-${track.id}`}>
                    <div className="profile-favorite-copy">
                      <strong>{track.title || '未命名歌曲'}</strong>
                      <p>{track.artist || '未知歌手'} · {track.album || '未知专辑'}</p>
                      <div className="chips">
                        <span className="genre-pill">{track.genre || '未分类'}</span>
                        {track.rating ? <span className="chip soft">{track.rating} 分</span> : null}
                        {isPlaying ? <span className="chip soft">当前播放中</span> : null}
                      </div>
                    </div>
                    <div className="profile-favorite-actions">
                      <button type="button" className="btn subtle small" onClick={() => onPlay?.(track)}>
                        播放
                      </button>
                      <button type="button" className="btn subtle small" onClick={() => onOpenDetail?.(track)}>
                        详情
                      </button>
                      <button type="button" className={`icon-btn heart-btn ${likedState ? 'active' : ''}`} onClick={() => onToggleFavorite?.(track)}>
                        {likedState ? '♥' : '♡'}
                      </button>
                    </div>
                  </article>
                );
              })
            ) : (
              <p>你还没有收藏歌曲，点亮红心后会在这里展示。</p>
            )}
          </div>
        </section>
      </div>

      {selectedPlaylist ? (
        <section className="glass-card">
          <div className="section-heading">
            <div>
              <span className="eyebrow">歌单详情</span>
              <h3>{selectedPlaylist.name || `歌单 #${selectedPlaylist.id}`}</h3>
              <p>{selectedPlaylist.description || '用户自建歌单'}</p>
            </div>
            <button type="button" className="btn subtle small" onClick={() => setSelectedPlaylist(null)}>
              收起
            </button>
          </div>
          <div className="history-list history-list-detailed">
            {loadingPlaylistTracks ? (
              <p>正在加载歌单歌曲...</p>
            ) : selectedPlaylistTracks.length > 0 ? (
              selectedPlaylistTracks.map((track) => (
                <article className="history-row history-row-detailed" key={`playlist-track-${selectedPlaylist.id}-${track.id}`}>
                  <div className="history-row-main">
                    <strong>{track.title || '未命名歌曲'}</strong>
                    <p>{track.artist || '未知歌手'} · {track.album || '未知专辑'}</p>
                    <div className="chips">
                      <span className="genre-pill">{track.genre || '未分类'}</span>
                    </div>
                  </div>
                  <div className="history-row-actions">
                    <button type="button" className="btn subtle small" onClick={() => onPlay?.(track)}>
                      播放
                    </button>
                    <button type="button" className="btn subtle small" onClick={() => onOpenDetail?.(track)}>
                      详情
                    </button>
                  </div>
                </article>
              ))
            ) : (
              <p>这个歌单还没有歌曲，可以在歌曲详情页添加歌曲。</p>
            )}
          </div>
        </section>
      ) : null}

      <section className="glass-card">
        <div className="section-heading">
          <div>
            <span className="eyebrow">个人资料</span>
            <h3>修改账号信息</h3>
          </div>
        </div>

        <div className="profile-form-grid">
          <input
            value={form.username}
            onChange={(event) => setForm((current) => ({ ...current, username: event.target.value }))}
            placeholder="用户名"
          />
          <input
            value={form.email}
            onChange={(event) => setForm((current) => ({ ...current, email: event.target.value }))}
            placeholder="邮箱"
          />
          <select
            value={form.gender}
            onChange={(event) => setForm((current) => ({ ...current, gender: event.target.value }))}
          >
            <option value="unknown">暂未设置</option>
            <option value="male">男</option>
            <option value="female">女</option>
          </select>
          <input
            value={form.ageRange}
            onChange={(event) => setForm((current) => ({ ...current, ageRange: event.target.value }))}
            placeholder="年龄段，例如 18-24"
          />
          <input
            value={form.province}
            onChange={(event) => setForm((current) => ({ ...current, province: event.target.value }))}
            placeholder="地区 / 省份"
          />
          <input
            value={form.timezone}
            onChange={(event) => setForm((current) => ({ ...current, timezone: event.target.value }))}
            placeholder="时区"
          />
          <input
            value={form.avatarUrl}
            onChange={(event) => setForm((current) => ({ ...current, avatarUrl: event.target.value }))}
            placeholder="头像链接"
          />
          <input
            value={form.bio}
            onChange={(event) => setForm((current) => ({ ...current, bio: event.target.value }))}
            placeholder="个性签名"
          />
        </div>

        <div className="state-actions" style={{ marginTop: 16 }}>
          <div className="chips">
            {toArray(profile?.preferredGenres).map((genre) => (
              <span key={genre} className="genre-pill">
                {genre}
              </span>
            ))}
          </div>
          <button className="btn" onClick={saveProfile} disabled={savingProfile}>
            {savingProfile ? '保存中...' : '保存资料'}
          </button>
        </div>

        {msg ? <p className="inline-feedback">{msg}</p> : null}
      </section>

      <div className="dashboard-grid">
        <section className="glass-card">
          <div className="section-heading">
            <div>
              <span className="eyebrow">历史播放</span>
              <h3>最近 40 首播放记录</h3>
            </div>
            <button type="button" className="btn subtle small" onClick={() => navigate('/history')}>
              展开全部
            </button>
          </div>
          <div className="history-list history-list-detailed">
            {historyPreview.length > 0 ? (
              historyPreview.map((item) => {
                const isPlaying = currentTrack?.id === item.trackId;
                return (
                  <article className={`history-row history-row-detailed ${isPlaying ? 'is-playing' : ''}`} key={item.id || `${item.trackId}-${item.playedAt}`}>
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
        </section>

        <EChartCard
          title="私人听感雷达"
          subtitle="查看偏好稳定度、专注倾向、探索欲望和夜间聆听状态。"
          option={toRadarOption(stats)}
        />
      </div>

      <section className="glass-card profile-chart-card">
        <div className="section-heading">
          <div>
            <span className="eyebrow">播放洞察</span>
            <h3>热力图与流派分布</h3>
          </div>
          <div className="chips chart-range-switch">
            {chartRanges.map((days) => (
              <button
                key={days}
                type="button"
                className={`chip soft interactive ${chartDays === days ? 'active' : ''}`}
                onClick={() => setChartDays(days)}
              >
                {days} 天
              </button>
            ))}
          </div>
        </div>
        <div className="profile-chart-grid">
          <EChartCard
            title={`近 ${chartDays} 天播放热力图`}
            subtitle="查看近期每天的播放强度。"
            option={toHeatmapOption(heatmap)}
          />
          <EChartCard
            title="流派分布"
            subtitle="看看最近偏好的流派构成。"
            option={toGenrePieOption(genres)}
          />
        </div>
      </section>
    </div>
  );
}
