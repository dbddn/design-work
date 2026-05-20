import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { playlistApi, trackApi } from '../api/services';
import { formatDateTime, getErrorMessage, toArray } from '../utils/view';

const formatDuration = (seconds) => {
  const safeSeconds = Number.isFinite(Number(seconds)) ? Math.max(0, Number(seconds)) : 0;
  const mins = Math.floor(safeSeconds / 60);
  const secs = Math.floor(safeSeconds % 60);
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
};

const isValidTag = (value) => {
  const text = String(value || '').trim();
  if (!text) return false;
  const normalized = text.toLowerCase();
  return normalized !== 'unknown' && normalized !== '未分类';
};

const ratingMarks = [1, 2, 3, 4, 5];

export default function TrackDetailPage({ onPlay, currentTrack, trackFeedback = {}, onRateTrack }) {
  const navigate = useNavigate();
  const { trackId } = useParams();
  const [track, setTrack] = useState(null);
  const [comments, setComments] = useState([]);
  const [playlists, setPlaylists] = useState({ created: [], favorites: [] });
  const [loading, setLoading] = useState(true);
  const [submittingComment, setSubmittingComment] = useState(false);
  const [creatingPlaylist, setCreatingPlaylist] = useState(false);
  const [addingPlaylistId, setAddingPlaylistId] = useState(null);
  const [error, setError] = useState('');
  const [commentText, setCommentText] = useState('');
  const [playlistName, setPlaylistName] = useState('');
  const [feedback, setFeedback] = useState('');
  const [playlistPanelOpen, setPlaylistPanelOpen] = useState(false);
  const [selectedPlaylistId, setSelectedPlaylistId] = useState('');

  const ownedPlaylists = useMemo(
    () => toArray(playlists.created).filter((item) => item?.createdByCurrentUser !== false),
    [playlists.created]
  );

  const loadAll = async () => {
    if (!trackId) return;
    setLoading(true);
    setError('');
    try {
      const [trackData, commentData, playlistData] = await Promise.all([
        trackApi.detail(trackId),
        trackApi.comments(trackId),
        playlistApi.mine()
      ]);
      setTrack(trackData || null);
      setComments(toArray(commentData));
      setPlaylists({
        created: toArray(playlistData?.created),
        favorites: toArray(playlistData?.favorites)
      });
    } catch (requestError) {
      setError(getErrorMessage(requestError, '歌曲详情加载失败，请稍后重试'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadAll();
  }, [trackId]);

  const openArtist = async (artistName) => {
    const value = String(artistName || '').trim();
    if (!value) return;
    try {
      const artist = await trackApi.resolveArtist(value);
      if (artist?.id) {
        navigate(`/artists/${artist.id}`);
        return;
      }
    } catch {
      // ignore and fallback to search
    }
    navigate(`/search?q=${encodeURIComponent(value)}`);
  };

  const handleCreateAndAddPlaylist = async () => {
    const name = playlistName.trim();
    if (!name || !track?.id) return;
    setCreatingPlaylist(true);
    setFeedback('');
    try {
      const created = await playlistApi.create(name);
      if (created?.playlistId) {
        await playlistApi.addTrack(created.playlistId, track.id);
      }
      setPlaylistName('');
      setSelectedPlaylistId('');
      setFeedback('已创建新歌单并加入当前歌曲');
      await loadAll();
      setPlaylistPanelOpen(false);
    } catch (requestError) {
      setFeedback(getErrorMessage(requestError, '新建歌单失败，请稍后重试'));
    } finally {
      setCreatingPlaylist(false);
    }
  };

  const handleAddToSelectedPlaylist = async () => {
    if (!track?.id || !selectedPlaylistId) return;
    setAddingPlaylistId(selectedPlaylistId);
    setFeedback('');
    try {
      await playlistApi.addTrack(selectedPlaylistId, track.id);
      setFeedback('已加入所选歌单');
      await loadAll();
      setPlaylistPanelOpen(false);
    } catch (requestError) {
      setFeedback(getErrorMessage(requestError, '加入歌单失败，请稍后重试'));
    } finally {
      setAddingPlaylistId(null);
    }
  };

  const handleSubmitComment = async () => {
    const content = commentText.trim();
    if (!content || !track?.id) return;
    setSubmittingComment(true);
    setFeedback('');
    try {
      const created = await trackApi.comment(track.id, content);
      setComments((current) => [created, ...current]);
      setCommentText('');
    } catch (requestError) {
      setFeedback(getErrorMessage(requestError, '评论发送失败，请稍后重试'));
    } finally {
      setSubmittingComment(false);
    }
  };

  if (loading) {
    return (
      <div className="page">
        <section className="glass-card track-detail-page-shell">
          <p>正在加载歌曲详情...</p>
        </section>
      </div>
    );
  }

  if (error || !track) {
    return (
      <div className="page">
        <section className="glass-card track-detail-page-shell">
          <button type="button" className="btn subtle playlist-back-btn" onClick={() => navigate(-1)}>
            返回上一页
          </button>
          <p>{error || '当前歌曲不存在或已失效'}</p>
        </section>
      </div>
    );
  }

  const isPlaying = currentTrack?.id === track?.id || currentTrack?.mcpTrackId === track?.mcpTrackId;
  const currentRating = Number(trackFeedback[String(track?.id)]?.rating || 0);

  return (
    <div className="page">
      <section className="glass-card track-detail-page-shell">
        <button type="button" className="btn subtle playlist-back-btn" onClick={() => navigate(-1)}>
          返回上一页
        </button>

        <div className="track-detail-hero">
          <div className="track-detail-cover-panel">
            {track.artworkUrl ? (
              <img src={track.artworkUrl} alt={`${track.title} 封面`} className="track-detail-cover-art" />
            ) : (
              <div className="track-detail-cover-fallback" />
            )}
            <div className="track-detail-cover-shadow" />
            <div className="track-detail-cover-copy">
              {isValidTag(track.genre) ? <span className="genre-pill on-cover">{track.genre}</span> : null}
              <strong>{track.title || '未命名歌曲'}</strong>
              <span>{track.artist || '未知歌手'}</span>
            </div>
          </div>

          <div className="track-detail-copy">
            <span className="eyebrow">歌曲详情</span>
            <h2>{track.title || '未命名歌曲'}</h2>
            <p className="track-detail-subtitle">
              <button type="button" className="artist-inline-link" onClick={() => openArtist(track.artist)}>
                {track.artist || '未知歌手'}
              </button>
              {' · '}
              {track.album || '未知专辑'}
            </p>
            <p className="track-detail-description">{track.description || '暂无歌曲简介'}</p>

            <div className="chips">
              <span className="chip soft">时长 {formatDuration(track.durationSec || track.duration)}</span>
              {isPlaying ? <span className="chip soft">当前播放中</span> : null}
            </div>

            <div className="track-detail-rating-panel" aria-label="歌曲评分">
              <span>我的评分</span>
              <div className="track-detail-rating-stars">
                {ratingMarks.map((score) => (
                  <button
                    key={score}
                    type="button"
                    className={`rating-star ${currentRating >= score ? 'active' : ''}`}
                    onClick={() => onRateTrack?.(track, score)}
                    aria-label={`评 ${score} 分`}
                  >
                    ★
                  </button>
                ))}
              </div>
            </div>

            <div className="track-detail-actions">
              <button type="button" className="btn" onClick={() => onPlay?.(track)}>
                立即播放
              </button>
              <button
                type="button"
                className="btn subtle"
                onClick={() => {
                  setPlaylistPanelOpen((open) => !open);
                  setFeedback('');
                }}
              >
                加入歌单
              </button>
            </div>

            {playlistPanelOpen ? (
              <section className="track-playlist-selector" aria-label="加入歌单面板">
                <div className="track-playlist-selector-head">
                  <strong>选择已有歌单</strong>
                  {ownedPlaylists.length > 0 ? (
                    <span>当前账号可用 {ownedPlaylists.length} 个</span>
                  ) : (
                    <span>你还没有创建歌单</span>
                  )}
                </div>

                {ownedPlaylists.length > 0 ? (
                  <div className="track-playlist-selector-list">
                    {ownedPlaylists.map((playlist) => (
                      <label className="track-playlist-selector-item" key={playlist.id}>
                        <input
                          type="radio"
                          name="playlist-id"
                          checked={String(selectedPlaylistId) === String(playlist.id)}
                          onChange={() => setSelectedPlaylistId(String(playlist.id))}
                        />
                        <div>
                          <strong>{playlist.name || `歌单 #${playlist.id}`}</strong>
                          <span>{playlist.songCount || 0} 首歌曲</span>
                        </div>
                      </label>
                    ))}
                  </div>
                ) : null}

                <div className="track-playlist-selector-actions">
                  <button
                    type="button"
                    className="btn subtle small"
                    onClick={handleAddToSelectedPlaylist}
                    disabled={!selectedPlaylistId || addingPlaylistId === selectedPlaylistId}
                  >
                    {addingPlaylistId === selectedPlaylistId ? '加入中...' : '加入已选歌单'}
                  </button>
                </div>

                <div className="track-playlist-create compact">
                  <input
                    value={playlistName}
                    onChange={(event) => setPlaylistName(event.target.value)}
                    placeholder="新建歌单名称，例如：夜间循环"
                  />
                  <button
                    type="button"
                    className="btn"
                    onClick={handleCreateAndAddPlaylist}
                    disabled={creatingPlaylist || !playlistName.trim()}
                  >
                    {creatingPlaylist ? '创建中...' : '新建并加入'}
                  </button>
                </div>
              </section>
            ) : null}
          </div>
        </div>
      </section>

      <div className="track-detail-content-grid">
        <section className="glass-card">
          <div className="section-heading">
            <div>
              <span className="eyebrow">歌词</span>
              <h3>完整歌词内容</h3>
            </div>
          </div>
          <div className="lyrics-block large">
            {(Array.isArray(track.lyrics) && track.lyrics.length ? track.lyrics : ['暂无歌词']).map((line, index) => (
              <p key={`${line}-${index}`}>{line}</p>
            ))}
          </div>
        </section>

        <section className="glass-card">
          <div className="section-heading">
            <div>
              <span className="eyebrow">评论区</span>
              <h3>{comments.length} 条歌曲评论</h3>
            </div>
          </div>

          <div className="track-comment-composer">
            <textarea
              value={commentText}
              onChange={(event) => setCommentText(event.target.value)}
              placeholder="写下你对这首歌的感受..."
            />
            <div className="track-comment-actions">
              <span>评论会写入数据库并实时展示</span>
              <button type="button" className="btn" onClick={handleSubmitComment} disabled={submittingComment}>
                {submittingComment ? '发送中...' : '发布评论'}
              </button>
            </div>
          </div>

          <div className="track-comment-list">
            {comments.length > 0 ? (
              comments.map((comment) => (
                <article className="track-comment-card" key={comment.id || `${comment.userId}-${comment.createdAt}`}>
                  <div className="track-comment-head">
                    <strong>{comment.username || comment.userId || '匿名用户'}</strong>
                    <span>{formatDateTime(comment.createdAt)}</span>
                  </div>
                  <p>{comment.content || ''}</p>
                </article>
              ))
            ) : (
              <p>这首歌还没有评论，来留下第一条吧。</p>
            )}
          </div>
        </section>
      </div>

      {feedback ? <p className="inline-feedback">{feedback}</p> : null}
    </div>
  );
}
