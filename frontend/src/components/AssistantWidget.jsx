import { useMemo, useRef, useState } from 'react';
import { assistantApi } from '../api/services';
import { getErrorMessage, toArray } from '../utils/view';

const quickPrompts = [
  '给我一份适合深夜写代码、偏安静和怀旧的歌单',
  '想听适合通勤路上的轻快中文歌',
  '帮我做一份适合下雨天独处的推荐歌单'
];

const inferScene = () => {
  const hour = new Date().getHours();
  if (hour < 6) return 'late_night';
  if (hour < 12) return 'morning';
  if (hour < 18) return 'afternoon';
  return 'evening';
};

const readAssistantPosition = () => {
  try {
    const saved = JSON.parse(localStorage.getItem('assistant:floating-position') || 'null');
    if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) {
      return saved;
    }
  } catch {
    // ignore invalid saved position
  }
  return { x: Math.max(24, window.innerWidth - 88), y: Math.max(96, window.innerHeight - 168) };
};

const getAssistantPanelPosition = (position) => {
  const panelWidth = Math.min(460, window.innerWidth - 32);
  const panelHeight = Math.min(620, window.innerHeight - 112);
  const left = Math.min(Math.max(12, position.x), window.innerWidth - panelWidth - 12);
  const belowTop = position.y + 68;
  const aboveTop = position.y - panelHeight - 12;
  const hasRoomBelow = belowTop + panelHeight <= window.innerHeight - 16;
  const top = hasRoomBelow ? belowTop : Math.max(16, aboveTop);

  return {
    left,
    top,
    maxHeight: panelHeight
  };
};

export default function AssistantWidget({ currentTrack, onPlay, onOpenDetail }) {
  const [open, setOpen] = useState(false);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [messages, setMessages] = useState([
    {
      role: 'assistant',
      content: '你好，我是你的智能选歌助手。你可以直接告诉我想听的场景、情绪或风格，我会结合系统候选歌曲帮你生成一份可播放的歌单。'
    }
  ]);
  const [playlist, setPlaylist] = useState([]);
  const [playlistTitle, setPlaylistTitle] = useState('');
  const [playlistSummary, setPlaylistSummary] = useState('');
  const [reasoningSummary, setReasoningSummary] = useState([]);
  const [modelName, setModelName] = useState('');
  const [usedFallback, setUsedFallback] = useState(false);
  const [position, setPosition] = useState(readAssistantPosition);
  const dragRef = useRef({ dragging: false, moved: false, offsetX: 0, offsetY: 0 });
  const suppressClickRef = useRef(false);
  const panelPosition = getAssistantPanelPosition(position);

  const historyPayload = useMemo(
    () =>
      messages
        .slice(-6)
        .map((item) => ({ role: item.role, content: item.content }))
        .filter((item) => item.content && item.content.trim()),
    [messages]
  );

  const submit = async (presetMessage) => {
    const message = (presetMessage ?? input).trim();
    if (!message || loading) return;

    const nextMessages = [...messages, { role: 'user', content: message }];
    setMessages(nextMessages);
    setInput('');
    setLoading(true);
    setError('');

    try {
      const data = await assistantApi.chat({
        message,
        history: historyPayload,
        scene: inferScene(),
        emotion: 'neutral',
        style: currentTrack?.genre || '',
        currentTrackId: currentTrack?.id || null
      });
      setMessages((current) => [
        ...current,
        {
          role: 'assistant',
          content: data?.assistantReply || data?.reply || '我已经先为你整理好一份推荐歌单。'
        }
      ]);
      setPlaylistTitle(data?.playlistTitle || '推荐歌单');
      setPlaylistSummary(data?.recommendationReason || data?.playlistSummary || '');
      const playlistItems = toArray(data?.playlist);
      const songItems = toArray(data?.songs).map((track) => ({ track, reason: data?.recommendationReason || '' }));
      setPlaylist(playlistItems.length ? playlistItems : songItems);
      setReasoningSummary(toArray(data?.reasoningSummary));
      setModelName(data?.model || '');
      setUsedFallback(Boolean(data?.fallback ?? data?.usedFallback));
    } catch (requestError) {
      setError(getErrorMessage(requestError, '智能助手暂时不可用，请稍后重试'));
    } finally {
      setLoading(false);
    }
  };

  const startDrag = (event) => {
    dragRef.current = {
      dragging: true,
      moved: false,
      offsetX: event.clientX - position.x,
      offsetY: event.clientY - position.y
    };
    suppressClickRef.current = false;
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };

  const moveDrag = (event) => {
    if (!dragRef.current.dragging) return;
    const nextX = Math.min(Math.max(12, event.clientX - dragRef.current.offsetX), window.innerWidth - 68);
    const nextY = Math.min(Math.max(72, event.clientY - dragRef.current.offsetY), window.innerHeight - 68);
    if (Math.abs(nextX - position.x) > 2 || Math.abs(nextY - position.y) > 2) {
      dragRef.current.moved = true;
      suppressClickRef.current = true;
    }
    const nextPosition = { x: nextX, y: nextY };
    setPosition(nextPosition);
    localStorage.setItem('assistant:floating-position', JSON.stringify(nextPosition));
  };

  const endDrag = () => {
    window.setTimeout(() => {
      dragRef.current.dragging = false;
      dragRef.current.moved = false;
    }, 120);
  };

  return (
    <>
      <button
        type="button"
        className="assistant-fab"
        style={{ left: position.x, top: position.y }}
        title="拖动调整位置，点击打开 AI 助手"
        aria-label="打开智能推荐助手"
        onPointerDown={startDrag}
        onPointerMove={moveDrag}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onClick={() => {
          if (dragRef.current.moved || suppressClickRef.current) {
            suppressClickRef.current = false;
            return;
          }
          setOpen((value) => !value);
        }}
      >
        <svg
          t="1779090656925"
          className="icon assistant-fab-icon"
          viewBox="0 0 1024 1024"
          version="1.1"
          xmlns="http://www.w3.org/2000/svg"
          aria-hidden="true"
        >
          <path d="M683.7 922.7h-345c-73.5 0-133.3-59.8-133.3-133.3V459.8c0-73.5 59.8-133.3 133.3-133.3h345c73.5 0 133.3 59.8 133.3 133.3v329.6c0 73.5-59.8 133.3-133.3 133.3z m-345-506.9c-24.3 0-44.1 19.8-44.1 44.1v329.6c0 24.3 19.8 44.1 44.1 44.1h345c24.3 0 44.1-19.8 44.1-44.1V459.8c0-24.3-19.8-44.1-44.1-44.1h-345zM914.3 759.6c-24.6 0-44.6-20-44.6-44.6V534.3c0-24.6 20-44.6 44.6-44.6s44.6 20 44.6 44.6V715c0 24.7-20 44.6-44.6 44.6zM111.7 759.6c-24.6 0-44.6-20-44.6-44.6V534.3c0-24.6 20-44.6 44.6-44.6s44.6 20 44.6 44.6V715c0 24.7-19.9 44.6-44.6 44.6z" fill="#2c2c2c" />
          <path d="M511.2 415.8c-24.6 0-44.6-20-44.6-44.6V239.3c0-24.6 20-44.6 44.6-44.6s44.6 20 44.6 44.6v131.9c0 24.6-20 44.6-44.6 44.6z" fill="#2c2c2c" />
          <path d="M511.2 276.6c-49.2 0-89.2-40-89.2-89.2s40-89.2 89.2-89.2 89.2 40 89.2 89.2-40 89.2-89.2 89.2z m0-89.2h0.2-0.2z m0 0h0.2-0.2z m0 0h0.2-0.2z m0 0h0.2-0.2z m0 0z m0 0h0.2-0.2z m0 0h0.2-0.2z m0-0.1h0.2-0.2zM399 675.5c-28.1 0-50.9-22.8-50.9-50.9 0-28.1 22.8-50.9 50.9-50.9s50.9 22.8 50.9 50.9c0 28.1-22.8 50.9-50.9 50.9zM622.9 675.5c-28.1 0-50.9-22.8-50.9-50.9 0-28.1 22.8-50.9 50.9-50.9 28.1 0 50.9 22.8 50.9 50.9 0 28.1-22.8 50.9-50.9 50.9z" fill="#2c2c2c" />
        </svg>
      </button>

      {open ? (
        <section
          className="assistant-panel glass-panel"
          style={{
            left: panelPosition.left,
            top: panelPosition.top,
            maxHeight: panelPosition.maxHeight
          }}
        >
          <div className="assistant-panel-glow assistant-panel-glow-a" />
          <div className="assistant-panel-glow assistant-panel-glow-b" />

          <div className="assistant-head">
            <div className="assistant-head-copy">
              <span className="eyebrow">智能推荐助手</span>
              <strong>把你的需求变成一份可直接播放的歌单</strong>
              <p>基于当前候选歌曲、用户偏好和场景信息，为你生成更贴近当下状态的播放建议。</p>
            </div>
            <button type="button" className="btn subtle assistant-close-btn" onClick={() => setOpen(false)}>
              收起
            </button>
          </div>

          <div className="assistant-status-row">
            <span className="assistant-status-pill primary">{modelName || 'qwen3.5-35b-a3b'}</span>
            <span className={`assistant-status-pill ${usedFallback ? 'warn' : 'success'}`}>
              {usedFallback ? '系统兜底' : '模型直连'}
            </span>
          </div>

          <div className="assistant-quick-prompts">
            {quickPrompts.map((prompt) => (
              <button
                key={prompt}
                type="button"
                className="assistant-quick-chip"
                onClick={() => submit(prompt)}
                disabled={loading}
              >
                {prompt}
              </button>
            ))}
          </div>

          <div className="assistant-messages">
            {messages.map((item, index) => (
              <div key={`${item.role}-${index}`} className={`assistant-bubble ${item.role === 'user' ? 'user' : 'assistant'}`}>
                {item.content}
              </div>
            ))}
            {loading ? <div className="assistant-bubble assistant">正在整理你的歌单和推荐理由...</div> : null}
          </div>

          {reasoningSummary.length > 0 ? (
            <div className="assistant-reasoning-card">
              <div className="assistant-section-head">
                <strong>本次思考摘要</strong>
                <span>模型筛选逻辑</span>
              </div>
              <div className="assistant-reasoning-list">
                {reasoningSummary.slice(0, 3).map((item, index) => (
                  <p key={`${item}-${index}`}>{item}</p>
                ))}
              </div>
            </div>
          ) : null}

          {playlist.length > 0 ? (
            <div className="assistant-playlist">
              <div className="assistant-playlist-head">
                <div>
                  <strong>{playlistTitle || '为你生成的推荐歌单'}</strong>
                  {playlistSummary ? <p>{playlistSummary}</p> : null}
                </div>
              </div>
              <div className="assistant-playlist-list">
                {playlist.map((item, index) => (
                  <article className="assistant-track-card" key={`${item?.track?.id || index}-${index}`}>
                    <div className="assistant-track-main">
                      <div className="assistant-track-rank">#{index + 1}</div>
                      <div className="assistant-track-copy">
                        <strong>{item?.track?.title || '未命名歌曲'}</strong>
                        <p>{item?.track?.artist || '未知歌手'}</p>
                        <small>{item?.reason || '这首歌和你当前的需求比较贴合。'}</small>
                      </div>
                    </div>
                    <div className="assistant-track-actions">
                      <button type="button" className="btn subtle small" onClick={() => onPlay?.(item?.track)}>
                        播放
                      </button>
                      <button type="button" className="btn subtle small" onClick={() => onOpenDetail?.(item?.track)}>
                        详情
                      </button>
                    </div>
                  </article>
                ))}
              </div>
            </div>
          ) : null}

          {error ? <p className="error">{error}</p> : null}

          <div className="assistant-input">
            <textarea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder="例如：给我一份适合深夜写代码、偏宁静和怀旧的中文歌单"
              rows={4}
            />
            <div className="assistant-input-actions">
              <span>描述越具体，生成的歌单越贴近你的场景。</span>
              <button type="button" className="btn" onClick={() => submit()} disabled={loading}>
                {loading ? '生成中...' : '生成歌单'}
              </button>
            </div>
          </div>
        </section>
      ) : null}
    </>
  );
}
