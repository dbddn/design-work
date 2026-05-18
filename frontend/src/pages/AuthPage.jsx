import { useState } from 'react';
import { authApi } from '../api/services';
import { getErrorMessage } from '../utils/view';

export default function AuthPage({ onAuthed }) {
  const [isRegister, setIsRegister] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState({ username: '', email: '', password: '' });
  const [error, setError] = useState('');

  const submit = async () => {
    const username = form.username.trim();
    const email = form.email.trim();
    const password = form.password.trim();

    if (!username || !password || (isRegister && !email)) {
      setError(isRegister ? '请完整填写用户名、邮箱和密码' : '请输入用户名和密码');
      return;
    }

    setError('');
    setSubmitting(true);
    try {
      const data = isRegister
        ? await authApi.register({ username, email, password })
        : await authApi.login({ username, password });
      if (!data?.token) {
        throw new Error('登录结果异常，请稍后重试');
      }
      localStorage.setItem('token', data.token);
      localStorage.setItem('userId', String(data.userId || username));
      localStorage.setItem('username', String(data.username || username));
      onAuthed();
    } catch (requestError) {
      setError(getErrorMessage(requestError, isRegister ? '注册失败，请稍后重试' : '登录失败，请稍后重试'));
    } finally {
      setSubmitting(false);
    }
  };

  const guest = async () => {
    setError('');
    setSubmitting(true);
    try {
      const data = await authApi.guest();
      if (!data?.token) {
        throw new Error('访客登录结果异常，请稍后重试');
      }
      localStorage.setItem('token', data.token);
      localStorage.setItem('userId', String(data.userId || 'guest'));
      localStorage.setItem('username', String(data.username || 'guest'));
      onAuthed();
    } catch (requestError) {
      setError(getErrorMessage(requestError, '访客登录失败，请稍后重试'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-card">
        <h1>ScenePulse 音乐推荐</h1>
        <p>基于 MCP 的混合策略音乐推荐体验。</p>
        <input
          placeholder="请输入用户名"
          value={form.username}
          onChange={(event) => setForm((current) => ({ ...current, username: event.target.value }))}
        />
        {isRegister ? (
          <input
            placeholder="请输入邮箱"
            value={form.email}
            onChange={(event) => setForm((current) => ({ ...current, email: event.target.value }))}
          />
        ) : null}
        <input
          type="password"
          placeholder="请输入密码"
          value={form.password}
          onChange={(event) => setForm((current) => ({ ...current, password: event.target.value }))}
        />
        {error ? <span className="error">{error}</span> : null}
        <div className="row">
          <button className="btn" onClick={submit} disabled={submitting}>
            {submitting ? '提交中...' : isRegister ? '注册' : '登录'}
          </button>
          <button className="btn subtle" onClick={() => setIsRegister((value) => !value)} disabled={submitting}>
            {isRegister ? '切换到登录' : '切换到注册'}
          </button>
          <button className="btn subtle" onClick={guest} disabled={submitting}>
            访客体验
          </button>
        </div>
      </div>
    </div>
  );
}
