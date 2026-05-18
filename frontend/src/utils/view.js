export const toArray = (value) => (Array.isArray(value) ? value : []);

export const getErrorMessage = (error, fallback = '请求失败，请稍后重试') => {
  if (!error) return fallback;
  if (typeof error === 'string') return error;
  if (error instanceof Error && error.message) return error.message;
  return fallback;
};

export const formatDateTime = (value) => {
  if (!value) return '--';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '--';
  return date.toLocaleString('zh-CN', { hour12: false });
};
