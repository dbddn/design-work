import axios from 'axios';

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
});

http.interceptors.request.use((config) => {
  const nextConfig = { ...config };
  nextConfig.headers = nextConfig.headers || {};

  const token = localStorage.getItem('token');
  const userId = localStorage.getItem('userId') || 'guest';

  if (token) {
    nextConfig.headers.Authorization = `Bearer ${token}`;
  }

  nextConfig.headers['X-User-Id'] = userId;
  return nextConfig;
});

http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const status = err?.response?.status;
    const timeoutMessage = err?.code === 'ECONNABORTED' || String(err?.message || '').includes('timeout')
      ? '请求超时，请确认后端服务和数据库已启动'
      : null;
    const message =
      err?.response?.data?.message ||
      (status === 401 ? '登录状态已失效，请重新登录' : null) ||
      (status === 403 ? '没有权限执行该操作' : null) ||
      (status === 503 ? '服务暂时不可用，请确认数据库连接正常' : null) ||
      (status >= 500 ? '服务暂时不可用，请稍后重试' : null) ||
      timeoutMessage ||
      err?.message ||
      '请求失败';

    return Promise.reject(new Error(message));
  }
);

export default http;
