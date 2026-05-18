#!/usr/bin/env python3
"""
Export the local MySQL music data into grant-mcp-main/data/library.json.

Why this script exists:
- The current MCP server does not read the `design` MySQL database directly.
- It reads a JSON personal-library dataset instead.
- This script adapts the track-level schema in `design` into the release-level
  JSON structure expected by `grant-mcp-main`.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib.parse import parse_qs, urlparse


def load_mysql_driver():
    try:
        import pymysql  # type: ignore

        return "pymysql", pymysql
    except Exception:
        pass

    try:
        import mysql.connector  # type: ignore

        return "mysql.connector", mysql.connector
    except Exception:
        pass

    raise RuntimeError(
        "No MySQL driver found. Install one of these first:\n"
        "  pip install pymysql\n"
        "or\n"
        "  pip install mysql-connector-python"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export MySQL music data to MCP library.json")
    parser.add_argument(
        "--jdbc-url",
        default=os.getenv(
            "SPRING_DATASOURCE_URL",
            "jdbc:mysql://127.0.0.1:3306/design?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf8",
        ),
        help="JDBC URL of the MySQL database",
    )
    parser.add_argument("--user", default=os.getenv("SPRING_DATASOURCE_USERNAME", "root"), help="MySQL username")
    parser.add_argument("--password", default=os.getenv("SPRING_DATASOURCE_PASSWORD", "123456"), help="MySQL password")
    parser.add_argument(
        "--output",
        default="C:/Users/LIUHENGRU/Desktop/design-1/data/library.json",
        help="Output JSON path for grant-mcp-main",
    )
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="Pretty-print JSON output",
    )
    return parser.parse_args()


def parse_jdbc_url(jdbc_url: str) -> Dict[str, Any]:
    if not jdbc_url.startswith("jdbc:mysql://"):
        raise ValueError(f"Unsupported JDBC URL: {jdbc_url}")

    stripped = jdbc_url[len("jdbc:") :]
    parsed = urlparse(stripped)
    query = parse_qs(parsed.query)

    return {
        "host": parsed.hostname or "127.0.0.1",
        "port": parsed.port or 3306,
        "database": parsed.path.lstrip("/") or "design",
        "charset": query.get("characterEncoding", ["utf8mb4"])[0],
    }


class ConnectionWrapper:
    def __init__(self, driver_name: str, module: Any, config: Dict[str, Any], user: str, password: str):
        self.driver_name = driver_name
        self.module = module
        self.config = config
        self.user = user
        self.password = password
        self.conn = None

    def __enter__(self):
        if self.driver_name == "pymysql":
            self.conn = self.module.connect(
                host=self.config["host"],
                port=self.config["port"],
                user=self.user,
                password=self.password,
                database=self.config["database"],
                charset=self.config["charset"],
                cursorclass=self.module.cursors.DictCursor,
            )
        else:
            self.conn = self.module.connect(
                host=self.config["host"],
                port=self.config["port"],
                user=self.user,
                password=self.password,
                database=self.config["database"],
                charset=self.config["charset"],
            )
        return self

    def __exit__(self, exc_type, exc, tb):
        if self.conn:
            self.conn.close()

    def query(self, sql: str, params: Optional[Tuple[Any, ...]] = None) -> List[Dict[str, Any]]:
        assert self.conn is not None
        cursor = self.conn.cursor(dictionary=True) if self.driver_name == "mysql.connector" else self.conn.cursor()
        try:
            cursor.execute(sql, params or ())
            rows = cursor.fetchall()
            if self.driver_name == "mysql.connector":
                return rows
            return list(rows)
        finally:
            cursor.close()


GENRE_PROFILES = [
    {
        "keywords": ["ambient", "氛围", "环境"],
        "genre": "ambient",
        "secondary": ["electronic"],
        "descriptors": ["calm", "peaceful", "immersive", "minimal", "soft", "dreamy"],
        "tags": ["relaxing", "meditative"],
    },
    {
        "keywords": ["electronic", "电子", "idm", "dubstep", "techno", "house"],
        "genre": "electronic",
        "secondary": ["ambient"],
        "descriptors": ["energetic", "driving", "futuristic", "textured", "bright", "rhythmic"],
        "tags": ["dynamic", "modern"],
    },
    {
        "keywords": ["pop", "流行"],
        "genre": "pop",
        "secondary": ["melodic"],
        "descriptors": ["uplifting", "bright", "catchy", "light", "fresh", "melodic"],
        "tags": ["accessible", "hooky"],
    },
    {
        "keywords": ["jazz", "爵士"],
        "genre": "jazz",
        "secondary": ["melodic"],
        "descriptors": ["smooth", "warm", "sophisticated", "relaxed", "timeless"],
        "tags": ["elegant", "organic"],
    },
    {
        "keywords": ["rock", "摇滚"],
        "genre": "rock",
        "secondary": ["melodic"],
        "descriptors": ["driving", "emotional", "anthemic", "intense", "warm"],
        "tags": ["band", "guitar"],
    },
    {
        "keywords": ["民谣", "folk", "acoustic"],
        "genre": "folk",
        "secondary": ["melodic"],
        "descriptors": ["warm", "organic", "storytelling", "gentle", "intimate"],
        "tags": ["acoustic", "lyrical"],
    },
    {
        "keywords": ["古典", "classical", "orchestral", "piano"],
        "genre": "classical",
        "secondary": ["ambient"],
        "descriptors": ["peaceful", "delicate", "cinematic", "refined", "emotional"],
        "tags": ["instrumental", "orchestral"],
    },
    {
        "keywords": ["rap", "hip hop", "hip-hop", "说唱"],
        "genre": "hip hop",
        "secondary": ["electronic"],
        "descriptors": ["rhythmic", "confident", "urban", "energetic", "focused"],
        "tags": ["beats", "lyrical"],
    },
    {
        "keywords": ["r&b", "rnb", "soul", "蓝调"],
        "genre": "r&b",
        "secondary": ["melodic"],
        "descriptors": ["smooth", "sensual", "warm", "soft", "melodic"],
        "tags": ["groove", "soulful"],
    },
]


KEYWORD_DESCRIPTOR_MAP = {
    "清新": ["fresh", "bright"],
    "治愈": ["calm", "peaceful", "warm"],
    "轻松": ["light", "smooth", "relaxing"],
    "舒缓": ["soft", "relaxing", "warm"],
    "安静": ["quiet", "peaceful", "minimal"],
    "深夜": ["quiet", "immersive", "nocturnal"],
    "夜晚": ["warm", "soft", "melodic"],
    "学习": ["focused", "steady", "minimal"],
    "专注": ["focused", "steady"],
    "通勤": ["energetic", "driving"],
    "运动": ["energetic", "driving", "uplifting"],
    "放松": ["calm", "peaceful", "relaxing"],
    "浪漫": ["melodic", "warm", "emotional"],
    "梦": ["dreamy", "ethereal"],
    "怀旧": ["nostalgic", "warm"],
    "沉浸": ["immersive", "textured"],
    "明亮": ["bright", "uplifting"],
    "dark": ["dark", "immersive"],
    "calm": ["calm", "peaceful"],
    "focus": ["focused", "steady"],
    "study": ["focused", "minimal"],
    "sleep": ["soft", "peaceful", "minimal"],
    "uplifting": ["uplifting", "bright"],
    "bright": ["bright", "fresh"],
    "warm": ["warm", "smooth"],
    "soft": ["soft", "peaceful"],
    "melodic": ["melodic", "emotional"],
    "night": ["nocturnal", "quiet"],
    "dream": ["dreamy", "ethereal"],
}


def normalize_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def safe_json_list(value: Any) -> List[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [normalize_text(item) for item in value if normalize_text(item)]
    text = normalize_text(value)
    if not text:
        return []
    try:
        parsed = json.loads(text)
        if isinstance(parsed, list):
            return [normalize_text(item) for item in parsed if normalize_text(item)]
    except Exception:
        pass
    return [item.strip() for item in re.split(r"[,/|;，、]+", text) if item.strip()]


def slugify(value: str) -> str:
    value = value.lower().strip()
    value = re.sub(r"[^a-z0-9\u4e00-\u9fff]+", "_", value)
    value = re.sub(r"_+", "_", value).strip("_")
    return value or "unknown"


def parse_year(row: Dict[str, Any]) -> Optional[int]:
    if row.get("release_year"):
        try:
            return int(row["release_year"])
        except Exception:
            pass
    release_date = row.get("album_release_date")
    if release_date:
        try:
            return int(str(release_date)[:4])
        except Exception:
            return None
    return None


def clamp_rating(value: float) -> float:
    return max(1.0, min(5.0, round(value, 2)))


def infer_rating(track_rate: Optional[float], avg_feedback: Optional[float], liked: int, skipped: int, play_count: int) -> float:
    if avg_feedback is not None:
        base = avg_feedback
    elif track_rate is not None and track_rate > 0:
        base = track_rate
    else:
        base = 3.5

    if liked > 0:
        base += min(0.5, liked * 0.08)
    if skipped > 0:
        base -= min(0.6, skipped * 0.05)
    if play_count >= 50:
        base += 0.3
    elif play_count >= 20:
        base += 0.2
    elif play_count >= 5:
        base += 0.1

    return clamp_rating(base)


def infer_profiles(texts: Iterable[str]) -> Tuple[List[str], List[str], List[str], List[str]]:
    raw_text = " ".join(normalize_text(text).lower() for text in texts if normalize_text(text))
    primary: List[str] = []
    secondary: List[str] = []
    descriptors: List[str] = []
    tags: List[str] = []

    for profile in GENRE_PROFILES:
        if any(keyword.lower() in raw_text for keyword in profile["keywords"]):
            primary.append(profile["genre"])
            secondary.extend(profile["secondary"])
            descriptors.extend(profile["descriptors"])
            tags.extend(profile["tags"])

    for keyword, values in KEYWORD_DESCRIPTOR_MAP.items():
        if keyword.lower() in raw_text:
            descriptors.extend(values)

    return dedupe(primary), dedupe(secondary), dedupe(descriptors), dedupe(tags)


def dedupe(values: Iterable[str], limit: Optional[int] = None) -> List[str]:
    seen = []
    for value in values:
        text = normalize_text(value)
        if text and text not in seen:
            seen.append(text)
            if limit is not None and len(seen) >= limit:
                break
    return seen


def to_iso(value: Any) -> Optional[str]:
    if not value:
        return None
    if isinstance(value, datetime):
        return value.isoformat()
    return normalize_text(value).replace(" ", "T")


@dataclass
class ReleaseAggregate:
    release_id: str
    artist: str
    album: str
    year_candidates: List[int] = field(default_factory=list)
    genres: List[str] = field(default_factory=list)
    secondary_genres: List[str] = field(default_factory=list)
    descriptors: List[str] = field(default_factory=list)
    tags: List[str] = field(default_factory=list)
    rating_values: List[float] = field(default_factory=list)
    play_count: int = 0
    last_played: Optional[str] = None

    def add_track(self, row: Dict[str, Any], history: Dict[str, Any], feedback: Dict[str, Any]) -> None:
        year = parse_year(row)
        if year:
            self.year_candidates.append(year)

        profile_primary, profile_secondary, profile_descriptors, profile_tags = infer_profiles(
            [
                row.get("genre_name"),
                row.get("genre_description"),
                row.get("prompt_hint"),
                row.get("description"),
                row.get("album_name"),
                row.get("name"),
                row.get("lyric"),
            ]
        )

        raw_genres = safe_json_list(row.get("genre_name")) + safe_json_list(row.get("genre_description"))
        self.genres.extend(profile_primary or raw_genres[:1])
        self.secondary_genres.extend(profile_secondary)
        self.descriptors.extend(profile_descriptors)
        self.tags.extend(profile_tags)

        track_play_count = int(row.get("play_count") or 0)
        history_play_count = int(history.get("history_play_count") or 0)
        combined_play_count = max(track_play_count, history_play_count)
        self.play_count += combined_play_count

        last_played = to_iso(history.get("last_played"))
        if last_played and (self.last_played is None or last_played > self.last_played):
            self.last_played = last_played

        rating = infer_rating(
            float(row["rate"]) if row.get("rate") is not None else None,
            float(feedback["avg_feedback_rating"]) if feedback.get("avg_feedback_rating") is not None else None,
            int(feedback.get("liked_count") or 0),
            int(feedback.get("skipped_count") or 0),
            combined_play_count,
        )
        self.rating_values.append(rating)

    def to_release(self) -> Dict[str, Any]:
        year = min(self.year_candidates) if self.year_candidates else None
        release = {
            "id": self.release_id,
            "artist": self.artist,
            "album": self.album,
            "year": year,
            "genres": dedupe(self.genres, 4),
            "secondary_genres": dedupe(self.secondary_genres, 6),
            "descriptors": dedupe(self.descriptors, 12),
            "personal_rating": clamp_rating(sum(self.rating_values) / len(self.rating_values)) if self.rating_values else 3.5,
            "play_count": self.play_count,
            "last_played": self.last_played,
            "tags": dedupe(self.tags, 8),
        }
        return {key: value for key, value in release.items() if value not in (None, [], "")}


def build_release_key(album_id: Any, album_name: str, artist: str, fallback_track_id: Any) -> str:
    if normalize_text(album_id):
        return f"album_{slugify(normalize_text(album_id))}"
    if album_name:
        return f"{slugify(artist)}__{slugify(album_name)}"
    return f"single_{fallback_track_id}"


def choose_album_name(row: Dict[str, Any]) -> str:
    album_name = normalize_text(row.get("album_name"))
    if album_name:
        return album_name
    return normalize_text(row.get("name")) or "Unknown Release"


def choose_artist(row: Dict[str, Any]) -> str:
    artists = safe_json_list(row.get("artist_names"))
    if artists:
        return artists[0]
    return "Unknown Artist"


def fetch_music_rows(db: ConnectionWrapper) -> List[Dict[str, Any]]:
    return db.query(
        """
        SELECT
            m.id,
            m.name,
            m.album_id,
            m.album_name,
            m.artist_names,
            m.rate,
            m.play_count,
            m.description,
            m.lyric,
            m.release_year,
            m.album_release_date,
            m.language,
            m.source_type,
            g.name AS genre_name,
            g.description AS genre_description,
            g.prompt_hint
        FROM musics m
        LEFT JOIN genres g ON m.genre_id = g.id
        WHERE COALESCE(m.is_active, 1) = 1
        ORDER BY m.id ASC
        """
    )


def fetch_history_map(db: ConnectionWrapper) -> Dict[str, Dict[str, Any]]:
    rows = db.query(
        """
        SELECT
            CAST(music_id AS CHAR) AS music_id,
            MAX(played_at) AS last_played,
            COUNT(*) AS history_play_count
        FROM listen_history
        GROUP BY CAST(music_id AS CHAR)
        """
    )
    return {normalize_text(row["music_id"]): row for row in rows}


def fetch_feedback_map(db: ConnectionWrapper) -> Dict[str, Dict[str, Any]]:
    rows = db.query(
        """
        SELECT
            CAST(music_id AS CHAR) AS music_id,
            AVG(rating) AS avg_feedback_rating,
            SUM(CASE WHEN liked_flag = 1 THEN 1 ELSE 0 END) AS liked_count,
            SUM(CASE WHEN skipped_flag = 1 THEN 1 ELSE 0 END) AS skipped_count
        FROM user_music_feedback
        GROUP BY CAST(music_id AS CHAR)
        """
    )
    return {normalize_text(row["music_id"]): row for row in rows}


def build_library(music_rows: List[Dict[str, Any]], history_map: Dict[str, Dict[str, Any]], feedback_map: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    releases: Dict[str, ReleaseAggregate] = {}

    for row in music_rows:
        artist = choose_artist(row)
        album = choose_album_name(row)
        release_key = build_release_key(row.get("album_id"), album, artist, row.get("id"))
        release = releases.get(release_key)
        if release is None:
            release = ReleaseAggregate(release_id=release_key, artist=artist, album=album)
            releases[release_key] = release

        music_id = normalize_text(row.get("id"))
        history = history_map.get(music_id, {})
        feedback = feedback_map.get(music_id, {})
        release.add_track(row, history, feedback)

    release_list = [release.to_release() for release in releases.values()]
    release_list.sort(key=lambda item: (-int(item.get("play_count", 0)), item.get("artist", ""), item.get("album", "")))

    return {
        "releases": release_list,
        "last_updated": datetime.now().isoformat(),
        "metadata": {
            "total_releases": len(release_list),
            "build_timestamp": datetime.now().isoformat(),
            "source": "design.mysql.export_mcp_library",
            "notes": [
                "Generated from track-level MySQL tables and aggregated into release-level MCP records.",
                "personal_rating and descriptors are inferred when raw release-level fields are missing.",
            ],
        },
    }


def ensure_output_parent(output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)


def main() -> int:
    args = parse_args()
    jdbc_config = parse_jdbc_url(args.jdbc_url)
    driver_name, driver_module = load_mysql_driver()

    with ConnectionWrapper(driver_name, driver_module, jdbc_config, args.user, args.password) as db:
        music_rows = fetch_music_rows(db)
        history_map = fetch_history_map(db)
        feedback_map = fetch_feedback_map(db)

    library = build_library(music_rows, history_map, feedback_map)
    output_path = Path(args.output)
    ensure_output_parent(output_path)

    with output_path.open("w", encoding="utf-8") as handle:
        json.dump(library, handle, ensure_ascii=False, indent=2 if args.pretty else None)

    print(f"Driver: {driver_name}")
    print(f"Database: mysql://{jdbc_config['host']}:{jdbc_config['port']}/{jdbc_config['database']}")
    print(f"Tracks read: {len(music_rows)}")
    print(f"Releases written: {len(library['releases'])}")
    print(f"Output: {output_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
