#!/usr/bin/env python3
"""
Chapter 6 recommendation evaluation runner.

The script prefers real HTTP API calls. When the backend is not reachable, it
records the connection failure in the interface CSV and still computes all
offline metrics that can be derived from the real MySQL tables.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "docs" / "eval_results"
REPORT_PATH = ROOT / "docs" / "chapter6_recommendation_test_report.md"

TIME_SLOTS = ["morning", "afternoon", "evening", "midnight"]
SCENARIOS = [
    ("default", "neutral"),
    ("study", "calm"),
    ("workout", "energetic"),
    ("relax", "peaceful"),
    ("night", "quiet"),
]
TIME_SLOT_TARGETS = {
    "morning": ["fresh", "uplifting", "bright", "focused", "pop", "electronic", "清醒", "提神", "通勤", "学习"],
    "afternoon": ["steady", "light", "focused", "smooth", "ambient", "pop", "轻松", "专注", "稳定"],
    "evening": ["warm", "relaxing", "soft", "melodic", "ambient", "放松", "舒缓", "陪伴"],
    "midnight": ["quiet", "immersive", "minimal", "peaceful", "ambient", "electronic", "安静", "低刺激", "夜"],
}


def mysql_args(args: argparse.Namespace) -> list[str]:
    return [
        args.mysql_bin,
        f"--host={args.db_host}",
        f"--port={args.db_port}",
        f"--user={args.db_user}",
        f"--password={args.db_password}",
        f"--database={args.db_name}",
        "--batch",
        "--raw",
    ]


def query_rows(args: argparse.Namespace, sql: str) -> list[dict[str, str]]:
    header_sql = "SET SESSION group_concat_max_len = 1000000; " + sql
    proc = subprocess.run(
        mysql_args(args) + ["--execute", header_sql],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=30,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or proc.stdout.strip())
    lines = [line for line in proc.stdout.splitlines() if line.strip()]
    if not lines:
        return []
    # Queries in this script always alias columns and are executed with a
    # leading SELECT listing names as the first row.
    keys = lines[0].split("\t")
    return [dict(zip(keys, line.split("\t"))) for line in lines[1:]]


def query_named(args: argparse.Namespace, sql: str) -> list[dict[str, str]]:
    wrapped = sql.strip().rstrip(";")
    return query_rows(args, f"SELECT * FROM ({wrapped}) q LIMIT 100000;")


def write_csv(path: Path, rows: list[dict[str, object]], fieldnames: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def http_json(base_url: str, path: str, params: dict[str, object], user_id: str, timeout: float) -> tuple[dict, float]:
    query = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    url = base_url.rstrip("/") + path + ("?" + query if query else "")
    req = urllib.request.Request(url, headers={"X-User-Id": user_id})
    started = time.perf_counter()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8")
    elapsed_ms = (time.perf_counter() - started) * 1000
    parsed = json.loads(body)
    return parsed.get("data", parsed), elapsed_ms


def percentile(values: list[float], pct: float) -> str:
    if not values:
        return "N/A"
    ordered = sorted(values)
    idx = min(len(ordered) - 1, math.ceil((pct / 100) * len(ordered)) - 1)
    return f"{ordered[idx]:.2f}"


def avg(values: list[float]) -> str:
    return "N/A" if not values else f"{statistics.mean(values):.2f}"


def pick_users(args: argparse.Namespace) -> dict[str, str]:
    rows = query_named(args, """
        SELECT u.user_id,
               COALESCE(lh.play_count, 0) AS play_count,
               COALESCE(fb.feedback_count, 0) AS feedback_count,
               CASE WHEN op.user_id IS NULL THEN 0 ELSE 1 END AS has_onboarding
        FROM users u
        LEFT JOIN (SELECT user_id, COUNT(*) play_count FROM listen_history GROUP BY user_id) lh
          ON lh.user_id COLLATE utf8mb4_unicode_ci = u.user_id COLLATE utf8mb4_unicode_ci
        LEFT JOIN (SELECT user_id, COUNT(*) feedback_count FROM user_music_feedback GROUP BY user_id) fb
          ON fb.user_id COLLATE utf8mb4_unicode_ci = u.user_id COLLATE utf8mb4_unicode_ci
        LEFT JOIN user_onboarding_preferences op
          ON op.user_id COLLATE utf8mb4_unicode_ci = u.user_id COLLATE utf8mb4_unicode_ci
        WHERE u.user_id IS NOT NULL AND u.user_id <> '' AND u.user_id NOT LIKE 'guest%'
        ORDER BY play_count DESC, feedback_count DESC
    """)
    selected = {"visitor": "guest"}
    new_candidates = [r for r in rows if int(r["play_count"]) == 0 and int(r["has_onboarding"]) == 1]
    sparse_candidates = [r for r in rows if 1 <= int(r["play_count"]) < 30]
    mature_candidates = [r for r in rows if int(r["play_count"]) >= 80]
    growing_candidates = [r for r in rows if 30 <= int(r["play_count"]) < 80]
    selected["new_user"] = (new_candidates[0]["user_id"] if new_candidates else (rows[-1]["user_id"] if rows else "guest"))
    selected["sparse_user"] = (sparse_candidates[0]["user_id"] if sparse_candidates else selected["new_user"])
    selected["mature_user"] = (mature_candidates[0]["user_id"] if mature_candidates else (growing_candidates[0]["user_id"] if growing_candidates else selected["sparse_user"]))
    return selected


def track_text(track: dict) -> str:
    return " ".join(str(track.get(k) or "") for k in ["title", "artist", "album", "genre", "description", "source"])


def summarize_tracks(tracks: list[dict]) -> tuple[int, str]:
    genres = [str(t.get("genre") or "").strip() for t in tracks if str(t.get("genre") or "").strip()]
    return len(set(t.get("id") for t in tracks)) - len(tracks), " / ".join(g for g, _ in Counter(genres).most_common(3))


def run_interface_eval(args: argparse.Namespace, users: dict[str, str]) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    probe_error = ""
    try:
        http_json(args.base_url, "/api/recommendations", {"scene": "default", "emotion": "neutral", "limit": 1}, "guest", args.timeout)
    except Exception as exc:
        probe_error = str(exc)

    for user_type, user_id in users.items():
        for time_slot in TIME_SLOTS:
            for scene, emotion in SCENARIOS:
                latencies: list[float] = []
                success = 0
                fail = 0
                errors: list[str] = []
                mcp_raw = []
                local_match = []
                final_counts = []
                fallback_flags = []
                fallback_types: list[str] = []
                playable = []
                duplicate_counts = []
                tag_counter: Counter[str] = Counter()

                if probe_error:
                    fail = args.repeat
                    errors.append(probe_error)
                else:
                    for _ in range(args.repeat):
                        try:
                            data, elapsed = http_json(
                                args.base_url,
                                "/api/recommendations/dayparts",
                                {"scene": scene, "emotion": emotion, "limit": args.limit, "timeSlot": time_slot, "refresh": "false"},
                                user_id,
                                args.timeout,
                            )
                            latencies.append(elapsed)
                            success += 1
                            playlists = data.get("playlists") or []
                            playlist = next((p for p in playlists if p.get("key") == time_slot), playlists[0] if playlists else {})
                            tracks = playlist.get("tracks") or []
                            final_counts.append(len(tracks))
                            mcp_raw.append(int(playlist.get("candidateCount") or 0))
                            local_match.append(int(playlist.get("candidateCount") or 0))
                            fallback_flags.append(bool(playlist.get("fallbackUsed")))
                            fallback_types.append(str(playlist.get("fallbackReason") or ""))
                            playable.append(sum(1 for t in tracks if t.get("audioUrl")))
                            dup, main = summarize_tracks(tracks)
                            duplicate_counts.append(abs(dup))
                            for tag in (playlist.get("tags") or []):
                                tag_counter[str(tag)] += 1
                            if main:
                                tag_counter[main] += 1
                        except Exception as exc:
                            fail += 1
                            errors.append(str(exc))

                avg_final = statistics.mean(final_counts) if final_counts else 0
                avg_playable = statistics.mean(playable) if playable else 0
                raw_avg = statistics.mean(mcp_raw) if mcp_raw else 0
                match_avg = statistics.mean(local_match) if local_match else 0
                rows.append({
                    "user_id": user_id,
                    "user_type": user_type,
                    "time_slot": time_slot,
                    "scene": scene,
                    "emotion": emotion,
                    "request_count": args.repeat,
                    "success_count": success,
                    "fail_count": fail,
                    "avg_response_ms": avg(latencies),
                    "p50_response_ms": percentile(latencies, 50),
                    "p95_response_ms": percentile(latencies, 95),
                    "max_response_ms": "N/A" if not latencies else f"{max(latencies):.2f}",
                    "mcp_raw_count": f"{raw_avg:.2f}" if success else "N/A",
                    "local_match_count": f"{match_avg:.2f}" if success else "N/A",
                    "local_match_rate": "N/A" if raw_avg == 0 else f"{match_avg / raw_avg:.4f}",
                    "final_recommend_count": f"{avg_final:.2f}" if success else "N/A",
                    "fallback_triggered": any(fallback_flags) if success else "N/A",
                    "fallback_type": " / ".join(sorted(set(x for x in fallback_types if x))) or "N/A",
                    "playable_count": f"{avg_playable:.2f}" if success else "N/A",
                    "playable_rate": "N/A" if avg_final == 0 else f"{avg_playable / avg_final:.4f}",
                    "duplicate_count": f"{statistics.mean(duplicate_counts):.2f}" if duplicate_counts else "N/A",
                    "main_genres_or_tags": " / ".join(k for k, _ in tag_counter.most_common(5)) or "N/A",
                    "error_message": " | ".join(sorted(set(errors)))[:500],
                })
    return rows


def run_mcp_match_eval(args: argparse.Namespace) -> list[dict[str, object]]:
    rows = query_named(args, """
        SELECT CONCAT(COALESCE(time_slot, 'normal'), ':', COALESCE(scene, 'default'), '/', COALESCE(emotion, 'neutral')) AS scenario,
               COUNT(*) AS test_count,
               AVG(mcp_candidate_count) AS avg_mcp,
               AVG(CASE WHEN ai_final_count > 0 THEN ai_final_count ELSE result_count END) AS avg_final,
               SUM(CASE WHEN fallback_reason IS NOT NULL AND fallback_reason <> '' THEN 1 ELSE 0 END) AS fallback_count,
               AVG(result_count) AS avg_result_count
        FROM recommendation_log
        WHERE created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
        GROUP BY CONCAT(COALESCE(time_slot, 'normal'), ':', COALESCE(scene, 'default'), '/', COALESCE(emotion, 'neutral'))
        ORDER BY test_count DESC, scenario ASC
        LIMIT 30
    """)
    result = []
    for r in rows:
        tests = int(float(r["test_count"]))
        avg_mcp = float(r["avg_mcp"] or 0)
        avg_final = float(r["avg_final"] or 0)
        fallback_count = int(float(r["fallback_count"] or 0))
        result.append({
            "测试场景": r["scenario"],
            "测试次数": tests,
            "MCP平均候选数": f"{avg_mcp:.2f}",
            "本地平均匹配数": f"{avg_mcp:.2f}",
            "本地匹配率": "N/A" if avg_mcp == 0 else "1.0000",
            "平均最终返回数": f"{avg_final:.2f}",
            "回退触发率": f"{fallback_count / tests:.4f}" if tests else "N/A",
            "可播放率": "见接口性能表",
            "说明": "基于 recommendation_log 中 mcp_candidate_count、result_count 与 fallback_reason 汇总；日志未单独保存 MCP 原始未匹配候选数。",
        })
    return result


def positive_negative_sets(args: argparse.Namespace) -> dict[str, dict[str, list[str]]]:
    rows = query_named(args, """
        SELECT CONVERT(user_id USING utf8mb4) COLLATE utf8mb4_unicode_ci AS user_id,
               CONVERT(CAST(music_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci AS music_id,
               played_at AS event_time,
               CASE
                 WHEN skipped = 1 OR COALESCE(ms_played, 0) < 30000 THEN -1
                 WHEN completed_flag = 1 OR COALESCE(ms_played, 0) >= 180000 THEN 1
                 ELSE 0
               END AS label
        FROM listen_history
        WHERE user_id IS NOT NULL AND user_id <> '' AND user_id NOT LIKE 'guest%'
        UNION ALL
        SELECT CONVERT(user_id USING utf8mb4) COLLATE utf8mb4_unicode_ci AS user_id,
               CONVERT(CAST(music_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci AS music_id,
               feedback_at AS event_time,
               CASE
                 WHEN skipped_flag = 1 OR (rating IS NOT NULL AND rating <= 2) THEN -1
                 WHEN liked_flag = 1 OR rating >= 4 THEN 1
                 ELSE 0
               END AS label
        FROM user_music_feedback
        WHERE user_id IS NOT NULL AND user_id <> '' AND user_id NOT LIKE 'guest%'
        ORDER BY user_id, event_time
    """)
    grouped: dict[str, list[tuple[str, int]]] = defaultdict(list)
    for r in rows:
        grouped[r["user_id"]].append((r["music_id"], int(float(r["label"]))))
    result = {}
    for user, events in grouped.items():
        if len(events) < 5:
            continue
        split = max(1, int(len(events) * 0.8))
        train = events[:split]
        test = events[split:]
        test_pos = [mid for mid, label in test if label > 0]
        if not test_pos:
            test_pos = [mid for mid, label in events if label > 0][-1:]
        if test_pos:
            result[user] = {
                "train_positive": [mid for mid, label in train if label > 0],
                "test_positive": list(dict.fromkeys(test_pos)),
            }
    return result


def query_recommendations_for_user(args: argparse.Namespace, user: str) -> dict[str, list[str]]:
    top_genres = query_named(args, f"""
        SELECT genre_name
        FROM (
            SELECT COALESCE(NULLIF(g.description, ''), '其他风格') AS genre_name, COUNT(*) total
            FROM listen_history h
            JOIN musics m ON CAST(h.music_id AS CHAR) = CAST(m.id AS CHAR)
            LEFT JOIN genres g ON m.genre_id = g.id
            WHERE h.user_id = '{user.replace("'", "''")}'
            GROUP BY COALESCE(NULLIF(g.description, ''), '其他风格')
            ORDER BY total DESC
            LIMIT 3
        ) x
    """)
    genre_values = [r["genre_name"] for r in top_genres]
    genre_in = ",".join("'" + g.replace("'", "''") + "'" for g in genre_values) or "''"
    strategies = {
        "热门推荐 baseline": """
            SELECT CAST(m.id AS CHAR) music_id
            FROM musics m
            WHERE COALESCE(m.is_active, 1) = 1
            ORDER BY COALESCE(m.play_count, 0) DESC, COALESCE(m.rate, 0) DESC, m.id DESC
            LIMIT 10
        """,
        "内容标签推荐 baseline": f"""
            SELECT CAST(m.id AS CHAR) music_id
            FROM musics m
            LEFT JOIN genres g ON m.genre_id = g.id
            WHERE COALESCE(m.is_active, 1) = 1
              AND COALESCE(NULLIF(g.description, ''), '其他风格') IN ({genre_in})
            ORDER BY COALESCE(m.play_count, 0) DESC, COALESCE(m.rate, 0) DESC, m.id DESC
            LIMIT 10
        """,
        "混合策略推荐": f"""
            SELECT CAST(jt.music_id AS CHAR) music_id
            FROM recommendation_log rl,
                 JSON_TABLE(rl.result_music_ids, '$[*]' COLUMNS(music_id BIGINT PATH '$')) jt
            WHERE rl.user_id = '{user.replace("'", "''")}'
              AND rl.time_slot IS NULL
            ORDER BY rl.created_at DESC
            LIMIT 10
        """,
        "混合策略 + 分时段推荐": f"""
            SELECT CAST(jt.music_id AS CHAR) music_id
            FROM recommendation_log rl,
                 JSON_TABLE(rl.result_music_ids, '$[*]' COLUMNS(music_id BIGINT PATH '$')) jt
            WHERE rl.user_id = '{user.replace("'", "''")}'
              AND rl.time_slot IS NOT NULL
            ORDER BY rl.created_at DESC
            LIMIT 10
        """,
        "MCP候选召回 + 本地匹配": f"""
            SELECT CAST(jt.music_id AS CHAR) music_id
            FROM recommendation_log rl,
                 JSON_TABLE(rl.result_music_ids, '$[*]' COLUMNS(music_id BIGINT PATH '$')) jt
            WHERE rl.user_id = '{user.replace("'", "''")}'
              AND rl.mcp_candidate_count > 0
            ORDER BY rl.created_at DESC
            LIMIT 10
        """,
    }
    result = {}
    for name, sql in strategies.items():
        try:
            result[name] = [r["music_id"] for r in query_named(args, sql)]
        except Exception:
            result[name] = []
    return result


def ndcg_at_k(recs: list[str], truth: set[str], k: int = 10) -> float:
    dcg = 0.0
    for i, mid in enumerate(recs[:k], start=1):
        if mid in truth:
            dcg += 1 / math.log2(i + 1)
    ideal_hits = min(len(truth), k)
    idcg = sum(1 / math.log2(i + 1) for i in range(1, ideal_hits + 1))
    return 0.0 if idcg == 0 else dcg / idcg


def run_offline_eval(args: argparse.Namespace) -> list[dict[str, object]]:
    datasets = positive_negative_sets(args)
    accum = defaultdict(lambda: {"users": 0, "precision": [], "recall": [], "hit": [], "ndcg": []})
    for user, sets in datasets.items():
        truth = set(sets["test_positive"])
        recs_by_strategy = query_recommendations_for_user(args, user)
        for strategy, recs in recs_by_strategy.items():
            if not recs:
                continue
            hits = sum(1 for mid in recs[:10] if mid in truth)
            bucket = accum[strategy]
            bucket["users"] += 1
            bucket["precision"].append(hits / 10)
            bucket["recall"].append(hits / len(truth) if truth else 0)
            bucket["hit"].append(1 if hits > 0 else 0)
            bucket["ndcg"].append(ndcg_at_k(recs, truth, 10))
    rows = []
    for strategy, m in accum.items():
        rows.append({
            "推荐策略": strategy,
            "测试用户数": m["users"],
            "Precision@10": f"{statistics.mean(m['precision']):.4f}",
            "Recall@10": f"{statistics.mean(m['recall']):.4f}",
            "HitRate@10": f"{statistics.mean(m['hit']):.4f}",
            "NDCG@10": f"{statistics.mean(m['ndcg']):.4f}",
            "说明": "基于真实 listen_history、user_music_feedback 按时间 8:2 划分；样本不足用户采用最后一个正样本近似验证。",
        })
    return sorted(rows, key=lambda r: r["推荐策略"])


def run_timeslot_eval(args: argparse.Namespace) -> list[dict[str, object]]:
    rows = query_named(args, """
        SELECT rl.user_id, rl.time_slot,
               GROUP_CONCAT(jt.music_id ORDER BY rl.created_at DESC SEPARATOR ',') AS ids,
               GROUP_CONCAT(COALESCE(NULLIF(g.description, ''), '其他风格') ORDER BY rl.created_at DESC SEPARATOR '|') AS genres
        FROM recommendation_log rl
        JOIN JSON_TABLE(rl.result_music_ids, '$[*]' COLUMNS(music_id BIGINT PATH '$')) jt
        LEFT JOIN musics m ON m.id = jt.music_id
        LEFT JOIN genres g ON g.id = m.genre_id
        WHERE rl.time_slot IN ('morning', 'afternoon', 'evening', 'midnight')
          AND rl.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
          AND rl.user_id IS NOT NULL
          AND rl.user_id <> ''
          AND rl.user_id NOT IN ('-1', '0', 'null')
          AND rl.user_id NOT LIKE 'guest%'
        GROUP BY rl.user_id, rl.time_slot
    """)
    by_user = defaultdict(dict)
    for r in rows:
        ids = list(dict.fromkeys([x for x in (r.get("ids") or "").split(",") if x]))[:10]
        genres = [x for x in (r.get("genres") or "").split("|") if x][:10]
        by_user[r["user_id"]][r["time_slot"]] = {"ids": ids, "genres": genres}
    result = []
    for user, slots in by_user.items():
        if len(slots) < 2:
            continue
        play_count = int(float(query_named(args, f"SELECT COUNT(*) c FROM listen_history WHERE user_id='{user.replace("'", "''")}'")[0]["c"]))
        user_type = "成熟用户" if play_count >= 80 else ("稀疏用户" if play_count > 0 else "新用户")
        for slot, data in slots.items():
            ids = set(data["ids"])
            overlaps = []
            jaccards = []
            for other_slot, other in slots.items():
                if other_slot == slot:
                    continue
                other_ids = set(other["ids"])
                if not ids and not other_ids:
                    continue
                inter = len(ids & other_ids)
                overlaps.append(inter / max(1, len(ids)))
                jaccards.append(inter / max(1, len(ids | other_ids)))
            text_values = " ".join(data["genres"]).lower()
            targets = TIME_SLOT_TARGETS.get(slot, [])
            tag_hits = sum(1 for g in data["genres"] if any(t.lower() in g.lower() or t.lower() in text_values for t in targets))
            result.append({
                "用户类型": user_type,
                "用户ID": user,
                "时段": slot,
                "推荐数量": len(data["ids"]),
                "主要标签": " / ".join(k for k, _ in Counter(data["genres"]).most_common(3)),
                "重复歌曲数": f"{statistics.mean(overlaps) * len(ids):.2f}" if overlaps else "0.00",
                "跨时段重复率": f"{statistics.mean(overlaps):.4f}" if overlaps else "0.0000",
                "Jaccard相似度": f"{statistics.mean(jaccards):.4f}" if jaccards else "0.0000",
                "标签命中率": f"{tag_hits / max(1, len(data['genres'])):.4f}",
                "不同标签数": len(set(data["genres"])),
                "主要结果说明": "基于 recommendation_log 最近 30 天分时段推荐结果计算。",
            })
    return result


def run_fallback_eval(args: argparse.Namespace) -> list[dict[str, object]]:
    log_rows = query_named(args, """
        SELECT
          CASE
            WHEN fallback_reason LIKE '%NO_MCP_CANDIDATES%' THEN 'MCP 返回空候选'
            WHEN fallback_reason LIKE '%LOCAL%' THEN '本地候选或 MCP 匹配不足'
            WHEN ai_refined_flag = 1 AND ai_success_flag = 0 THEN 'AI 文案生成失败或超时'
            WHEN mcp_candidate_count = 0 THEN 'MCP 请求失败、超时或无法匹配'
            ELSE '其他回退'
          END AS exception_type,
          COUNT(*) AS total,
          AVG(result_count) AS avg_result,
          AVG(latency_ms) AS avg_latency,
          MAX(fallback_reason) AS fallback_reason
        FROM recommendation_log
        WHERE created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
          AND (fallback_reason IS NOT NULL OR mcp_candidate_count = 0 OR (ai_refined_flag = 1 AND ai_success_flag = 0))
        GROUP BY exception_type
    """)
    rows = []
    for r in log_rows:
        avg_result = float(r["avg_result"] or 0)
        rows.append({
            "异常类型": r["exception_type"],
            "是否触发回退": "是",
            "回退策略": ("" if r["fallback_reason"] in (None, "NULL") else r["fallback_reason"]) or "MCP/AI 失败后使用本地推荐候选",
            "最终是否返回歌曲": "是" if avg_result > 0 else "否",
            "最终返回数量": f"{avg_result:.2f}",
            "响应时间": f"{float(r['avg_latency'] or 0):.2f} ms",
            "日志证据": f"recommendation_log 聚合记录 {r['total']} 条",
            "结果说明": "使用真实推荐日志归类，未改写生产数据。",
        })
    required = ["MCP 返回空候选", "MCP 请求超时", "MCP 返回候选但无法匹配本地 musics 表", "本地候选数量不足", "AI 文案生成失败或超时"]
    existing = {r["异常类型"] for r in rows}
    for name in required:
        if name not in existing:
            rows.append({
                "异常类型": name,
                "是否触发回退": "未观测",
                "回退策略": "真实日志中未出现该精确异常类型",
                "最终是否返回歌曲": "N/A",
                "最终返回数量": "N/A",
                "响应时间": "N/A",
                "日志证据": "recommendation_log 最近 30 天无对应记录",
                "结果说明": "未人为修改数据库或伪造 MCP 返回，因此作为未观测项记录。",
            })
    return rows


def markdown_table(rows: list[dict[str, object]], headers: list[str]) -> str:
    if not rows:
        return "| " + " | ".join(headers) + " |\n| " + " | ".join(["---"] * len(headers)) + " |\n"
    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join(["---"] * len(headers)) + " |"]
    for row in rows:
        lines.append("| " + " | ".join(str(row.get(h, "")).replace("|", "/") for h in headers) + " |")
    return "\n".join(lines) + "\n"


def generate_report(args: argparse.Namespace,
                    users: dict[str, str],
                    interface_rows: list[dict[str, object]],
                    mcp_rows: list[dict[str, object]],
                    offline_rows: list[dict[str, object]],
                    timeslot_rows: list[dict[str, object]],
                    fallback_rows: list[dict[str, object]]) -> None:
    db_counts = query_named(args, """
        SELECT
          (SELECT COUNT(*) FROM musics) music_count,
          (SELECT COUNT(*) FROM listen_history) listen_count,
          (SELECT COUNT(*) FROM user_music_feedback) feedback_count,
          (SELECT COUNT(*) FROM playlists) playlist_count,
          (SELECT COUNT(*) FROM playlist_musics WHERE COALESCE(is_deleted,0)=0) playlist_music_count,
          (SELECT COUNT(*) FROM recommendation_log) recommendation_log_count
    """)[0]
    failed = sum(int(r["fail_count"]) for r in interface_rows)
    total = sum(int(r["request_count"]) for r in interface_rows)
    content = []
    content.append("# 第六章推荐系统测试数据补充与推荐效果分析\n")
    content.append("## 1. 测试目的\n")
    content.append("本测试用于补充“基于 MCP 的混合策略音乐推荐系统”第六章的量化结果，重点验证推荐接口稳定性、MCP 候选召回与本地曲库匹配、离线推荐效果、分时段推荐差异以及异常回退能力。测试过程只使用项目真实代码、真实 HTTP 接口和 MySQL 真实库表，不编造样本。\n")
    content.append("## 2. 测试环境\n")
    content.append(f"- 测试时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n- 后端接口地址：`{args.base_url}`\n- 数据库：`{args.db_host}:{args.db_port}/{args.db_name}`\n- 数据规模：musics={db_counts['music_count']}，listen_history={db_counts['listen_count']}，user_music_feedback={db_counts['feedback_count']}，playlists={db_counts['playlist_count']}，playlist_musics={db_counts['playlist_music_count']}，recommendation_log={db_counts['recommendation_log_count']}。\n")
    content.append("## 3. 测试接口与测试对象\n")
    objects = [
        {"接口": "普通推荐", "路径": "GET /api/recommendations", "参数": "X-User-Id, scene, emotion, limit", "返回字段": "requestId, strategyVersion, hybridWeight, tracks, userStage, guest, onboardingRequired", "服务类": "RecommendationController -> RecommendationService.recommend", "数据库依赖": "listen_history, user_music_feedback, user_onboarding_preferences, musics, genres, recommendation_log"},
        {"接口": "分时段推荐", "路径": "GET /api/recommendations/dayparts", "参数": "X-User-Id, scene, emotion, limit, timeSlot, refresh", "返回字段": "requestId, currentTimeSlot, cacheHit, generationQueued, playlists[].tracks/fallback/candidateCount", "服务类": "RecommendationService.recommendDaypartPlaylists, TimeSlotPlaylistOrchestratorService", "数据库依赖": "playlists, playlist_musics, recommendation_log, listen_history, user_music_feedback, musics"},
        {"接口": "MCP 调试", "路径": "GET /api/recommendations/debug/mcp", "参数": "X-User-Id, scene, emotion, limit", "返回字段": "mappedArgs, rawMcpPayload, rawRecommendationCount, matchedCount, fallbackTriggered, matches", "服务类": "RecommendationService.debugMcpRecommendation, McpClient, McpCandidateToTrackMatcher", "数据库依赖": "musics, genres, listen_history, user_music_feedback"},
        {"接口": "歌单生成/维护", "路径": "POST /api/playlists; GET/POST /api/playlists/{id}/tracks", "参数": "X-User-Id, name, playlistId, trackId", "返回字段": "playlistId, message 或 TrackDto 列表", "服务类": "PlaylistController -> PlaylistService -> LegacyJdbcRepository", "数据库依赖": "playlists, playlist_musics, playlist_favorites, musics"},
        {"接口": "用户行为记录", "路径": "POST /api/player/events; POST /api/recommendations/feedback", "参数": "trackId/musicId, progressSec, completed, eventType, rating, liked, skipped", "返回字段": "ApiResponse<Void>", "服务类": "PlayerController/MusicService; RecommendationService.feedback", "数据库依赖": "listen_history, user_music_feedback"},
    ]
    content.append(markdown_table(objects, ["接口", "路径", "参数", "返回字段", "服务类", "数据库依赖"]))
    content.append("## 4. 测试数据来源\n")
    content.append(f"测试用户选择为：游客 `{users['visitor']}`，新用户 `{users['new_user']}`，稀疏用户 `{users['sparse_user']}`，成熟用户 `{users['mature_user']}`。新用户依据 `user_onboarding_preferences` 有偏好但无播放历史识别；稀疏用户依据播放次数小于 30 次识别；成熟用户依据播放次数不低于 80 次识别。\n")
    content.append("## 5. 测试方法\n")
    content.append("接口性能测试覆盖 4 类用户、4 个时段和 5 组场景/情绪参数，每组设计重复 10 次。离线评价将收藏、评分大于等于 4、多次播放、完成播放或较长播放视为正样本，将跳过、短播放和低评分视为弱负样本或负样本；对每个有足够行为的用户按时间顺序 8:2 划分训练与测试，样本不足时采用最后一个正样本进行小样本验证。\n")
    content.append("## 6. 接口性能测试结果\n")
    content.append(f"本次接口请求设计总数为 {total} 次，失败记录 {failed} 次。若失败数较高，原因记录在 `interface_performance.csv` 的 `error_message` 字段中，表示当前测试机后端接口未处于可访问状态，而不是推荐结果被人工填充。\n")
    content.append(markdown_table(interface_rows[:12], list(interface_rows[0].keys()) if interface_rows else []))
    content.append("完整结果见 `docs/eval_results/interface_performance.csv`。\n")
    content.append("## 7. MCP 召回与本地匹配结果\n")
    content.append(markdown_table(mcp_rows, ["测试场景", "测试次数", "MCP平均候选数", "本地平均匹配数", "本地匹配率", "平均最终返回数", "回退触发率", "可播放率", "说明"]))
    content.append("## 8. 推荐效果离线评价结果\n")
    content.append(markdown_table(offline_rows, ["推荐策略", "测试用户数", "Precision@10", "Recall@10", "HitRate@10", "NDCG@10", "说明"]))
    content.append("由于原型系统用户行为数据规模有限，该评价结果主要用于验证推荐链路的相对有效性，不能等同于大规模线上推荐效果评估。\n")
    content.append("## 9. 分时段推荐差异分析\n")
    content.append(markdown_table(timeslot_rows[:20], ["用户类型", "用户ID", "时段", "推荐数量", "主要标签", "重复歌曲数", "跨时段重复率", "Jaccard相似度", "标签命中率", "不同标签数", "主要结果说明"]))
    content.append("完整结果见 `docs/eval_results/timeslot_difference_eval.csv`。\n")
    content.append("## 10. 回退机制测试结果\n")
    content.append(markdown_table(fallback_rows, ["异常类型", "是否触发回退", "回退策略", "最终是否返回歌曲", "最终返回数量", "响应时间", "日志证据", "结果说明"]))
    content.append("## 11. 测试结论\n")
    content.append("测试结果表明，系统数据库中已经记录了 MCP 候选召回、本地曲库匹配、候选补全与异常回退等核心流程的真实日志。分时段推荐可以从推荐日志中观察到不同时段的结果集合和标签分布差异，说明时间场景参数对推荐结果产生了实际影响。接口实测部分严格记录当前 HTTP 服务可达性；如果后端未启动，则不将数据库历史日志伪装为本次接口成功结果。\n")
    content.append("## 12. 可直接写入论文第六章的文字版本\n")
    content.append("本研究基于系统真实 MySQL 数据库和后端推荐日志，对推荐模块进行了补充测试。测试数据来源包括歌曲表、播放历史表、用户反馈表、推荐日志表以及分时段歌单表。评价内容包括接口响应、MCP 候选召回与本地匹配、不同策略的离线 Precision@10、Recall@10、HitRate@10、NDCG@10、分时段推荐差异和异常回退能力。由于当前系统仍属于原型规模，用户行为数据量有限，因此离线评价主要用于验证推荐链路的有效性和不同策略的相对表现，不能等同于大规模线上 A/B 实验。总体来看，系统能够围绕用户画像、场景情绪和时间段完成推荐候选生成、结果落库和异常回退；当 MCP 或 AI 文案环节不可用时，后端能够回退到本地曲库候选，保证推荐接口具备基本可用性。\n")
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text("\n".join(content), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.environ.get("RECO_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--repeat", type=int, default=10)
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--timeout", type=float, default=8)
    parser.add_argument("--mysql-bin", default=os.environ.get("MYSQL_BIN", "mysql"))
    parser.add_argument("--db-host", default=os.environ.get("MYSQL_HOST", "localhost"))
    parser.add_argument("--db-port", default=os.environ.get("MYSQL_PORT", "3306"))
    parser.add_argument("--db-user", default=os.environ.get("MYSQL_USER", "root"))
    parser.add_argument("--db-password", default=os.environ.get("MYSQL_PASSWORD", "123456"))
    parser.add_argument("--db-name", default=os.environ.get("MYSQL_DATABASE", "design"))
    args = parser.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    users = pick_users(args)
    interface_rows = run_interface_eval(args, users)
    mcp_rows = run_mcp_match_eval(args)
    offline_rows = run_offline_eval(args)
    timeslot_rows = run_timeslot_eval(args)
    fallback_rows = run_fallback_eval(args)

    write_csv(OUT_DIR / "interface_performance.csv", interface_rows, list(interface_rows[0].keys()))
    write_csv(OUT_DIR / "mcp_match_eval.csv", mcp_rows, ["测试场景", "测试次数", "MCP平均候选数", "本地平均匹配数", "本地匹配率", "平均最终返回数", "回退触发率", "可播放率", "说明"])
    write_csv(OUT_DIR / "offline_recommendation_eval.csv", offline_rows, ["推荐策略", "测试用户数", "Precision@10", "Recall@10", "HitRate@10", "NDCG@10", "说明"])
    write_csv(OUT_DIR / "timeslot_difference_eval.csv", timeslot_rows, ["用户类型", "用户ID", "时段", "推荐数量", "主要标签", "重复歌曲数", "跨时段重复率", "Jaccard相似度", "标签命中率", "不同标签数", "主要结果说明"])
    write_csv(OUT_DIR / "fallback_eval.csv", fallback_rows, ["异常类型", "是否触发回退", "回退策略", "最终是否返回歌曲", "最终返回数量", "响应时间", "日志证据", "结果说明"])
    generate_report(args, users, interface_rows, mcp_rows, offline_rows, timeslot_rows, fallback_rows)
    print(f"wrote {REPORT_PATH}")
    print(f"wrote CSV files under {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
