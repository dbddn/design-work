import http from './http';

let activeDaypartRequest = null;
let activeDaypartRequestKey = '';

const unwrap = async (promise) => {
  const payload = await promise;
  return payload?.data;
};

const get = (url, params) => unwrap(http.get(url, { params }));
const post = (url, body) => unwrap(http.post(url, body));
const put = (url, body) => unwrap(http.put(url, body));
const postMultipart = (url, formData) =>
  unwrap(http.post(url, formData, {
    timeout: 30000
  }));

// RESTful resource APIs
export const authApi = {
  createUser: (body) => post('/auth/register', body),
  createSession: (body) => post('/auth/login', body),
  createGuestSession: () => post('/auth/guest'),
  // Backward-compatible aliases
  register: (body) => post('/auth/register', body),
  login: (body) => post('/auth/login', body),
  guest: () => post('/auth/guest')
};

export const usersApi = {
  getMe: () => get('/users/me'),
  updateMe: (body) => put('/users/me', body),
  uploadAvatar: (file) => {
    const formData = new FormData();
    formData.append('avatar', file);
    return postMultipart('/users/me/avatar', formData);
  },
  getMyStats: () => get('/users/me/stats')
};

export const tracksApi = {
  list: ({ q, page = 0, size = 20 }) => get('/tracks/search', { q, page, size }),
  get: (id) => get(`/tracks/${id}`),
  listComments: (id) => get(`/tracks/${id}/comments`),
  createComment: (id, body) => post(`/tracks/${id}/comments`, body),
  favorite: (id, body) => post(`/recommendations/feedback`, { trackId: id, ...body }),
  getArtist: (artistId, page = 0, size = 10) => get(`/tracks/artists/${artistId}`, { page, size }),
  refreshPlayback: (id) => post(`/tracks/${id}/refresh-playback`),
  listHistory: ({ limit = 40 } = {}) => get('/history', { limit }),
  createPlayerEvent: (body) => post('/player/events', body)
};

export const playlistsApi = {
  mine: () => get('/playlists/mine'),
  create: (body) => post('/playlists', body),
  listTracks: (playlistId, limit = 100) => get(`/playlists/${playlistId}/tracks`, { limit }),
  createTrackRelation: (playlistId, body) => post(`/playlists/${playlistId}/tracks`, body),
  updateFavoriteState: (playlistId, body) => post(`/playlists/${playlistId}/favorite`, body)
};

export const analyticsApi = {
  listHeatmap: ({ days = 30 } = {}) => get('/analytics/heatmap', { days }),
  listGenres: ({ days = 30 } = {}) => get('/analytics/genres', { days }),
  heatmap: (days = 30) => get('/analytics/heatmap', { days }),
  genres: (days = 30) => get('/analytics/genres', { days })
};

export const communityPostsApi = {
  list: () => get('/community/posts'),
  create: (body) => post('/community/posts', body),
  createComment: (postId, body) => post(`/community/posts/${postId}/comments`, body),
  createLike: (postId) => post(`/community/posts/${postId}/likes`)
};

export const chartsApi = {
  listHot: () => get('/charts/hot'),
  listNew: () => get('/charts/new'),
  listNeteaseToplist: (limit = 4, trackLimit = 5) => get('/charts/netease/toplist', { limit, trackLimit })
};

export const exploreApi = {
  listTimeline: () => get('/explore/timeline'),
  listTimeMachine: () => get('/explore/time-machine'),
  listTimeMachineTracks: ({ year, startYear, endYear, genre, limit = 20 }) =>
    get('/explore/time-machine/tracks', { year, startYear, endYear, genre, limit }),
  // Backward-compatible aliases
  timeline: () => get('/explore/timeline'),
  timeMachine: () => get('/explore/time-machine'),
  timeMachineTracks: ({ year, startYear, endYear, genre, limit = 20 }) =>
    get('/explore/time-machine/tracks', { year, startYear, endYear, genre, limit }),
  hotCharts: () => get('/charts/hot'),
  newCharts: () => get('/charts/new')
};

export const recommendationsApi = {
  list: ({ scene = 'default', emotion = 'neutral', limit = 20 } = {}) =>
    get('/recommendations', { scene, emotion, limit }),
  dayparts: async ({ scene = 'default', emotion = 'neutral', limit = 10, timeSlot, refresh = false } = {}) => {
    const requestKey = JSON.stringify({ scene, emotion, limit, timeSlot: timeSlot || '', refresh: Boolean(refresh) });
    if (activeDaypartRequest && activeDaypartRequestKey === requestKey) {
      return activeDaypartRequest;
    }

    activeDaypartRequestKey = requestKey;
    activeDaypartRequest = http.get('/recommendations/dayparts', {
      params: { scene, emotion, limit, timeSlot, refresh },
      timeout: 30000
    })
      .then((payload) => payload?.data)
      .finally(() => {
        if (activeDaypartRequestKey === requestKey) {
          activeDaypartRequest = null;
          activeDaypartRequestKey = '';
        }
      });

    return activeDaypartRequest;
  },
  saveOnboardingPreferences: (genres = [], gender = 'unknown') =>
    post('/recommendations/onboarding/preferences', { genres, gender }),
  createFeedback: (body) => post('/recommendations/feedback', body),
  getStrategy: () => get('/recommendations/strategy'),
  recalculateStrategy: () => post('/recommendations/strategy/recalculate')
};

export const assistantApi = {
  chat: async (body) => {
    const payload = await http.post('/assistant/chat', body, { timeout: 90000 });
    return payload?.data;
  }
};

// Backward-compatible aliases used by current pages
export const userApi = {
  me: usersApi.getMe,
  stats: usersApi.getMyStats,
  update: usersApi.updateMe,
  uploadAvatar: usersApi.uploadAvatar
};

export const trackApi = {
  search: (q, page = 0, size = 20) => tracksApi.list({ q, page, size }),
  detail: tracksApi.get,
  comments: tracksApi.listComments,
  comment: (trackId, content) => tracksApi.createComment(trackId, { content }),
  favorites: (limit = 40) => get('/tracks/favorites', { limit }),
  favorite: (trackId, favorite = true, rating = null) => tracksApi.favorite(trackId, { liked: favorite, rating, skipped: false }),
  artist: tracksApi.getArtist,
  resolveArtist: (name) => get('/tracks/artists/resolve', { name }),
  refreshPlayback: tracksApi.refreshPlayback,
  history: (limit = 40) => tracksApi.listHistory({ limit }),
  playerEvent: tracksApi.createPlayerEvent
};

export const artistApi = {
  detail: (artistId, page = 0, size = 10) => tracksApi.getArtist(artistId, page, size),
  resolve: (name) => get('/tracks/artists/resolve', { name })
};

export const playlistApi = {
  mine: playlistsApi.mine,
  create: (name) => playlistsApi.create({ name }),
  tracks: playlistsApi.listTracks,
  addTrack: (playlistId, trackId) => playlistsApi.createTrackRelation(playlistId, { trackId }),
  favorite: (playlistId, favorite = true) => playlistsApi.updateFavoriteState(playlistId, { favorite })
};

export const communityApi = {
  listPosts: communityPostsApi.list,
  listComments: (postId) => get(`/community/posts/${postId}/comments`),
  createPost: (content) => communityPostsApi.create({ content }),
  comment: (postId, content) => communityPostsApi.createComment(postId, { content }),
  like: communityPostsApi.createLike
};

export const recommendationApi = {
  list: (scene = 'default', emotion = 'neutral', limit = 20) =>
    recommendationsApi.list({ scene, emotion, limit }),
  dayparts: (scene = 'default', emotion = 'neutral', limit = 10, options = {}) =>
    recommendationsApi.dayparts({ scene, emotion, limit, ...options }),
  saveOnboardingPreferences: recommendationsApi.saveOnboardingPreferences,
  feedback: recommendationsApi.createFeedback,
  strategy: recommendationsApi.getStrategy
};
