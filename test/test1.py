# -*- coding: utf-8 -*-

import pymysql
import requests
import time
from datetime import datetime

# ====== 数据库配置：改成你自己的 ======
DB_CONFIG = {
    "host": "127.0.0.1",
    "port": 3306,
    "user": "root",
    "password": "123456",          # 改成你的数据库密码
    "database": "design",   # 改成你的数据库名
    "charset": "utf8mb4",
    "cursorclass": pymysql.cursors.DictCursor,
    "autocommit": False
}

BASE_URL = "http://localhost:3000"
BATCH_SIZE = 2000
REQUEST_TIMEOUT = 10
SLEEP_SECONDS = 0.05


def get_connection():
    return pymysql.connect(**DB_CONFIG)


def fetch_batch_ids(conn):
    """
    只读取 release_year 为空的前 500 条
    这样成功写入后，下次不会重复跑
    """
    sql = """
        SELECT id
        FROM musics
        WHERE release_year IS NULL
        ORDER BY id
        LIMIT %s
    """
    with conn.cursor() as cursor:
        cursor.execute(sql, (BATCH_SIZE,))
        rows = cursor.fetchall()
    return [row["id"] for row in rows]


def fetch_song_detail(song_id):
    """
    调用本地接口获取歌曲详情
    """
    url = f"{BASE_URL.rstrip('/')}/song/detail"
    params = {"ids": song_id}
    resp = requests.get(url, params=params, timeout=REQUEST_TIMEOUT)
    resp.raise_for_status()

    data = resp.json()
    songs = data.get("songs", [])
    if not songs:
        return None
    return songs[0]


def extract_publish_date(song):
    """
    提取完整日期，格式要求：YYYY-MM-DD
    优先使用 publish_date
    如果没有 publish_date，则从 publishTime 转换
    """
    publish_date = song.get("publish_date")
    if publish_date and isinstance(publish_date, str):
        # 简单校验格式长度
        if len(publish_date) >= 10:
            return publish_date[:10]

    publish_time = song.get("publishTime")
    if publish_time:
        try:
            return datetime.fromtimestamp(publish_time / 1000).strftime("%Y-%m-%d")
        except Exception:
            return None

    return None


def update_release_date(conn, song_id, publish_date):
    """
    把完整日期写入 release_year 字段
    """
    sql = """
        UPDATE musics
        SET release_year = %s
        WHERE id = %s
    """
    with conn.cursor() as cursor:
        cursor.execute(sql, (publish_date, song_id))
        return cursor.rowcount


def main():
    conn = None
    failed_ids = []
    success = 0
    failed = 0
    skipped = 0

    try:
        conn = get_connection()
        print("数据库连接成功")

        song_ids = fetch_batch_ids(conn)
        total = len(song_ids)
        print(f"本次待处理数量：{total}")

        if total == 0:
            print("没有需要处理的数据")
            return

        for i, song_id in enumerate(song_ids, 1):
            print(f"[{i}/{total}] 正在处理 song_id={song_id}")

            try:
                song = fetch_song_detail(song_id)
                if not song:
                    print("  ❌ 没有获取到歌曲详情")
                    failed += 1
                    failed_ids.append(song_id)
                    continue

                publish_date = extract_publish_date(song)
                if publish_date is None:
                    print("  ⚠️ 没有获取到发行日期，跳过")
                    skipped += 1
                    continue

                rowcount = update_release_date(conn, song_id, publish_date)
                if rowcount > 0:
                    success += 1
                    print(f"  ✅ 已写入 release_year={publish_date}")
                else:
                    failed += 1
                    failed_ids.append(song_id)
                    print("  ❌ 更新失败：数据库未匹配到对应 id")

            except Exception as e:
                failed += 1
                failed_ids.append(song_id)
                print(f"  ❌ 处理失败：{e}")

            time.sleep(SLEEP_SECONDS)

        conn.commit()

        print("\n===== 本次结果 =====")
        print(f"成功写入：{success}")
        print(f"失败数量：{failed}")
        print(f"跳过数量：{skipped}")

        print("\n失败的 song_id：")
        if failed_ids:
            for fid in failed_ids:
                print(fid)
        else:
            print("无")

    except Exception as e:
        if conn:
            conn.rollback()
        print(f"程序执行失败：{e}")

    finally:
        if conn:
            conn.close()
            print("数据库连接已关闭")


if __name__ == "__main__":
    main()