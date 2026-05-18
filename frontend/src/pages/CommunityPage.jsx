import { useEffect, useMemo, useRef, useState } from 'react';
import { communityApi, playlistApi, trackApi } from '../api/services';
import { formatDateTime, getErrorMessage, toArray } from '../utils/view';

const SHARE_SCHEMA = 'community-share-v1';

const parseSharePayload = (rawContent) => {
  try {
    const parsed = JSON.parse(rawContent);
    if (parsed?.schema === SHARE_SCHEMA) {
      return parsed;
    }
    return null;
  } catch {
    return null;
  }
};

export default function CommunityPage({ embedded = false, onPlay, onOpenDetail }) {
  const [posts, setPosts] = useState([]);
  const [commentsByPost, setCommentsByPost] = useState({});
  const [expandedComments, setExpandedComments] = useState({});
  const [feeling, setFeeling] = useState('');
  const [shareType, setShareType] = useState('track');
  const [trackQuery, setTrackQuery] = useState('');
  const [trackSearchResults, setTrackSearchResults] = useState([]);
  const [selectedTrackId, setSelectedTrackId] = useState('');
  const [selectedPlaylistId, setSelectedPlaylistId] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
  const [recentTracks, setRecentTracks] = useState([]);
  const [myPlaylists, setMyPlaylists] = useState([]);
  const [commentText, setCommentText] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const trackOptions = useMemo(() => {
    const unique = new Map();
    [...toArray(trackSearchResults), ...toArray(recentTracks)].forEach((item) => {
      if (!item?.id || unique.has(String(item.id))) return;
      unique.set(String(item.id), item);
    });
    return Array.from(unique.values()).slice(0, 30);
  }, [recentTracks, trackSearchResults]);

  const selectedTrack = useMemo(
    () => trackOptions.find((item) => String(item.id) === String(selectedTrackId)) || null,
    [trackOptions, selectedTrackId]
  );

  const selectedPlaylist = useMemo(
    () => toArray(myPlaylists).find((item) => String(item.id) === String(selectedPlaylistId)) || null,
    [myPlaylists, selectedPlaylistId]
  );

  const selectedShareTitle = shareType === 'track'
    ? selectedTrack?.title || '选择一首最近播放'
    : selectedPlaylist?.name || '选择一个歌单';

  const selectedShareMeta = shareType === 'track'
    ? [selectedTrack?.artist, selectedTrack?.album, selectedTrack?.genre].filter(Boolean).join(' · ') || '从最近播放里分享你的此刻循环'
    : `${selectedPlaylist?.songCount || 0} 首歌曲 · 分享你的歌单审美`;
  const pickerRef = useRef(null);

  const loadPosts = async () => {
    setError('');
    try {
      const nextPosts = toArray(await communityApi.listPosts());
      setPosts(nextPosts);

      const commentPairs = await Promise.all(
        nextPosts.map(async (post) => {
          try {
            const comments = await communityApi.listComments(post.postId);
            return [post.postId, toArray(comments)];
          } catch {
            return [post.postId, []];
          }
        })
      );

      setCommentsByPost(Object.fromEntries(commentPairs));
    } catch (requestError) {
      setPosts([]);
      setCommentsByPost({});
      setError(getErrorMessage(requestError, '社区内容加载失败，请稍后重试'));
    }
  };

  useEffect(() => {
    const init = async () => {
      try {
        const [historyData, playlistData] = await Promise.all([
          trackApi.history(60),
          playlistApi.mine()
        ]);

        setRecentTracks(
          toArray(historyData).map((item) => ({
            id: item.trackId,
            title: item.title,
            artist: item.artist,
            album: item.album,
            genre: item.genre
          }))
        );
        setMyPlaylists(toArray(playlistData?.created).filter((item) => item?.createdByCurrentUser !== false));
      } catch (requestError) {
        setRecentTracks([]);
        setMyPlaylists([]);
        setError(getErrorMessage(requestError, '社区初始化失败，请稍后重试'));
      }

      await loadPosts();
    };

    init();
  }, []);

  useEffect(() => {
    let cancelled = false;
    const keyword = trackQuery.trim();
    if (!keyword) {
      setTrackSearchResults([]);
      return undefined;
    }

    const timer = window.setTimeout(async () => {
      try {
        const payload = await trackApi.search(keyword, 0, 12);
        if (!cancelled) {
          setTrackSearchResults(toArray(payload?.items));
        }
      } catch {
        if (!cancelled) {
          setTrackSearchResults([]);
        }
      }
    }, 260);

    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [trackQuery]);

  useEffect(() => {
    const closePicker = (event) => {
      if (!pickerRef.current?.contains(event.target)) {
        setPickerOpen(false);
      }
    };

    document.addEventListener('mousedown', closePicker);
    return () => document.removeEventListener('mousedown', closePicker);
  }, []);

  useEffect(() => {
    setPickerOpen(false);
  }, [shareType]);

  const publish = async () => {
    if (!feeling.trim()) return;

    if (shareType === 'track' && !selectedTrack) {
      setError('请选择要分享的单曲');
      return;
    }
    if (shareType === 'playlist' && !selectedPlaylist) {
      setError('请选择要分享的歌单');
      return;
    }

    const payload = {
      schema: SHARE_SCHEMA,
      shareType,
      feeling: feeling.trim(),
      shareTrack: shareType === 'track' ? selectedTrack : null,
      sharePlaylist: shareType === 'playlist' ? selectedPlaylist : null
    };

    setSubmitting(true);
    setError('');
    try {
      await communityApi.createPost(JSON.stringify(payload));
      setFeeling('');
      setSelectedTrackId('');
      setSelectedPlaylistId('');
      await loadPosts();
    } catch (requestError) {
      setError(getErrorMessage(requestError, '发布失败，请稍后重试'));
    } finally {
      setSubmitting(false);
    }
  };

  const comment = async (postId) => {
    const text = commentText[postId];
    if (!text?.trim()) return;

    setSubmitting(true);
    setError('');
    try {
      await communityApi.comment(postId, text.trim());
      setCommentText((state) => ({ ...state, [postId]: '' }));
      await loadPosts();
    } catch (requestError) {
      setError(getErrorMessage(requestError, '评论失败，请稍后重试'));
    } finally {
      setSubmitting(false);
    }
  };

  const like = async (postId) => {
    try {
      await communityApi.like(postId);
      await loadPosts();
    } catch (requestError) {
      setError(getErrorMessage(requestError, '点赞失败，请稍后重试'));
    }
  };

  const getUserName = (item) => item?.username || item?.userId || 'Music Fan';

  const getUserInitial = (item) => {
    const name = String(getUserName(item));
    return name.slice(0, 1).toUpperCase();
  };

  const toggleComments = (postId) => {
    setExpandedComments((state) => ({ ...state, [postId]: !state[postId] }));
  };

  const selectTrack = (track) => {
    if (!track?.id) return;
    setSelectedTrackId(String(track.id));
    setPickerOpen(false);
  };

  const selectPlaylist = (playlist) => {
    if (!playlist?.id) return;
    setSelectedPlaylistId(String(playlist.id));
    setPickerOpen(false);
  };

  const openSharedTrack = (track) => {
    if (!track) return;
    if (track.id) {
      onOpenDetail?.(track);
      return;
    }
    onPlay?.(track);
  };

  const content = (
    <>
      <section className="glass-card community-composer-card">
        {error ? <p>{error}</p> : null}
        <div className="community-composer-header">
          <div>
            <span className="eyebrow">音乐动态</span>
            <h3>分享你正在听的声音</h3>
            <p>把单曲、歌单和这一刻的心情发到社区。</p>
          </div>
          <span className="community-live-badge">{posts.length} 条动态</span>
        </div>

        <div className="community-composer-panel">
          <div className="community-share-tabs" aria-label="选择分享类型">
            <button
              type="button"
              className={shareType === 'track' ? 'active' : ''}
              onClick={() => setShareType('track')}
            >
              单曲
            </button>
            <button
              type="button"
              className={shareType === 'playlist' ? 'active' : ''}
              onClick={() => setShareType('playlist')}
            >
              歌单
            </button>
          </div>

          <div className="community-composer-row split share-select-row">
            <div className="community-selector-copy">
              <span>{shareType === 'track' ? '最近播放' : '我的歌单'}</span>
              <p>{shareType === 'track' ? '点击右侧卡片，从最近播放或搜索结果里选择一首歌。' : '点击右侧卡片，选择要分享到社区的歌单。'}</p>
            </div>

            <div className="community-selector-wrap" ref={pickerRef}>
              <button
                type="button"
                className={`community-share-preview selector ${pickerOpen ? 'open' : ''}`}
                aria-haspopup="listbox"
                aria-expanded={pickerOpen}
                onClick={() => setPickerOpen((state) => !state)}
              >
                <div className="community-share-cover">
                  {shareType === 'track' ? '♪' : '♫'}
                </div>
                <div>
                  <span>{shareType === 'track' ? '单曲分享' : '歌单分享'}</span>
                  <strong>{selectedShareTitle}</strong>
                  <small>{selectedShareMeta}</small>
                </div>
                <i aria-hidden="true">⌄</i>
              </button>

              {pickerOpen ? (
                <div className="community-picker-dropdown" role="listbox">
                  {shareType === 'track' ? (
                    <>
                      <input
                        value={trackQuery}
                        placeholder="搜索歌曲，或从最近播放中选择"
                        onChange={(event) => setTrackQuery(event.target.value)}
                        autoFocus
                      />
                      <div className="community-picker-list">
                        {trackOptions.length > 0 ? (
                          trackOptions.map((track) => (
                            <button
                              type="button"
                              key={`track-${track.id}`}
                              className={`community-picker-item ${String(selectedTrackId) === String(track.id) ? 'active' : ''}`}
                              onClick={() => selectTrack(track)}
                            >
                              <strong>{track.title || '未命名歌曲'}</strong>
                              <span>{track.artist || '未知歌手'}{track.album ? ` · ${track.album}` : ''}</span>
                            </button>
                          ))
                        ) : (
                          <div className="community-picker-empty">暂无可选歌曲，先播放一首歌或搜索歌曲名。</div>
                        )}
                      </div>
                    </>
                  ) : (
                    <div className="community-picker-list playlist">
                      {toArray(myPlaylists).length > 0 ? (
                        toArray(myPlaylists).map((playlist) => (
                          <button
                            type="button"
                            key={`playlist-${playlist.id}`}
                            className={`community-picker-item ${String(selectedPlaylistId) === String(playlist.id) ? 'active' : ''}`}
                            onClick={() => selectPlaylist(playlist)}
                          >
                            <strong>{playlist.name || `歌单 #${playlist.id}`}</strong>
                            <span>{playlist.songCount || 0} 首歌曲</span>
                          </button>
                        ))
                      ) : (
                        <div className="community-picker-empty">暂无可分享歌单，可以先去“我的音乐”创建。</div>
                      )}
                    </div>
                  )}
                </div>
              ) : null}
            </div>
          </div>

          <div className="community-composer-row">
            <label className="full-width">
              <span>动态内容</span>
              <textarea
                value={feeling}
                maxLength={240}
                placeholder="写下今天为什么想分享它，例如：这首歌的鼓点很上头，适合夜里散步。"
                onChange={(event) => setFeeling(event.target.value)}
              />
            </label>
          </div>

          <div className="community-composer-actions">
            <span>{feeling.trim().length}/240</span>
            <button className="btn" onClick={publish} disabled={submitting || !feeling.trim()}>
              {submitting ? '发布中...' : '发布动态'}
            </button>
          </div>
        </div>
      </section>

      <div className="community-feed">
        {posts.length > 0 ? (
          posts.map((post) => {
            const payload = parseSharePayload(post.content);
            const comments = toArray(commentsByPost[post.postId]);
            const heading =
              payload?.shareType === 'playlist'
                ? '分享了一个歌单'
                : payload?.shareType === 'track'
                  ? '分享了一首单曲'
                  : '社区动态';

            return (
              <section className="community-post-card" key={post.postId}>
                <div className="community-post-avatar">{getUserInitial(post)}</div>
                <div className="community-post-body">
                  <div className="community-post-head">
                    <div>
                      <strong>@{getUserName(post)}</strong>
                      <span>{heading} · {formatDateTime(post.createdAt)}</span>
                    </div>
                    <button className="community-action-btn" onClick={() => like(post.postId)}>
                      喜欢 {post.likeCount || 0}
                    </button>
                  </div>

                  <p className="community-post-text">{payload?.feeling || post.content || '暂无内容'}</p>

                  {payload?.shareType === 'track' && payload?.shareTrack ? (
                    <div className="community-shared-card">
                      <div className="community-share-cover compact">♪</div>
                      <div>
                        <span>正在推荐一首歌</span>
                        <strong>{payload.shareTrack.title || '未命名歌曲'}</strong>
                        <small>{payload.shareTrack.artist || '未知歌手'}{payload.shareTrack.album ? ` · ${payload.shareTrack.album}` : ''}</small>
                      </div>
                      <div className="community-shared-actions">
                        <button type="button" className="btn subtle small" onClick={() => onPlay?.(payload.shareTrack)}>
                          播放
                        </button>
                        <button type="button" className="btn subtle small" onClick={() => openSharedTrack(payload.shareTrack)}>
                          详情
                        </button>
                      </div>
                    </div>
                  ) : null}

                  {payload?.shareType === 'playlist' && payload?.sharePlaylist ? (
                    <div className="community-shared-card">
                      <div className="community-share-cover compact">♫</div>
                      <div>
                        <span>正在推荐一张歌单</span>
                        <strong>{payload.sharePlaylist.name || `歌单 #${payload.sharePlaylist.id}`}</strong>
                        <small>{payload.sharePlaylist.songCount || 0} 首歌曲</small>
                      </div>
                    </div>
                  ) : null}

                  <div className="community-post-actions">
                    <button type="button" onClick={() => like(post.postId)}>喜欢</button>
                    <button type="button" onClick={() => toggleComments(post.postId)}>
                      {expandedComments[post.postId] ? '收起评论' : `评论 ${post.commentCount || comments.length}`}
                    </button>
                    <span>#{post.postId}</span>
                  </div>

                  <div className="community-comment-panel">
                    {comments.length > 0 ? (
                      <div className="community-comment-list">
                        {(expandedComments[post.postId] ? comments : comments.slice(0, 2)).map((item) => (
                          <article className="community-comment-card" key={`comment-${item.commentId}`}>
                            <div className="community-comment-avatar">{getUserInitial(item)}</div>
                            <div>
                              <div className="community-comment-head">
                                <strong>@{getUserName(item)}</strong>
                                <span>{formatDateTime(item.createdAt)}</span>
                              </div>
                              <p>{item.content || ''}</p>
                            </div>
                          </article>
                        ))}
                        {!expandedComments[post.postId] && comments.length > 2 ? (
                          <button type="button" className="community-more-comments" onClick={() => toggleComments(post.postId)}>
                            展开全部 {comments.length} 条评论
                          </button>
                        ) : null}
                      </div>
                    ) : (
                      <p className="community-empty-comment">还没有评论，成为第一个回应的人。</p>
                    )}

                    <div className="community-reply-box">
                      <input
                        placeholder="回复这条动态"
                        value={commentText[post.postId] || ''}
                        onChange={(event) => setCommentText((state) => ({ ...state, [post.postId]: event.target.value }))}
                      />
                      <button className="btn subtle small" onClick={() => comment(post.postId)} disabled={submitting || !commentText[post.postId]?.trim()}>
                        发送
                      </button>
                    </div>
                  </div>
                </div>
              </section>
            );
          })
        ) : (
          <section className="glass-card community-empty-state">
            <h3>社区还没有动态</h3>
            <p>分享一首最近播放，给这里放第一首歌。</p>
          </section>
        )}
      </div>
    </>
  );

  if (embedded) {
    return content;
  }

  return <div className="page community-page">{content}</div>;
}
