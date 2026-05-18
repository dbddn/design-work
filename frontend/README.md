# music-reco-frontend

React + Axios + ECharts web frontend for the MCP hybrid music recommendation prototype.

## Run

```bash
cd frontend
npm install
npm run dev
```

## API Integration

- Proxy `/api` to `http://localhost:8080` via `vite.config.js`
- Request headers:
  - `Authorization: Bearer <token>` (if available)
  - `X-User-Id` (from `localStorage.userId`)

## Pages

- `/` recommendation home with scene + emotion controls and strategy chips
- `/search` fuzzy music search + play
- `/analytics` 30-day heatmap + genre distribution (ECharts)
- `/explore` timeline + time machine visualization
- `/charts` hot/new charts
- `/community` post/comment/like
- `/profile` user info, listening stats, history, playlist creation
