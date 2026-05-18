import json
import pymysql
from typing import List, Tuple, Any


# ===== 数据库配置 =====
DB_CONFIG = {
    "host": "127.0.0.1",
    "port": 3306,
    "user": "root",
    "password": "123456",
    "database": "design",
    "charset": "utf8mb4"
}


def parse_json_array(value: Any) -> List[Any]:
    """
    把数据库中的 artist_names / artist_ids 解析成 Python 列表
    支持:
    - None
    - 已经是 list
    - 字符串形式: ["陈小春"]
    - 字符串形式: [123456]
    """
    if value is None:
        return []

    if isinstance(value, list):
        return value

    if isinstance(value, (tuple, set)):
        return list(value)

    if isinstance(value, str):
        value = value.strip()
        if not value:
            return []
        try:
            result = json.loads(value)
            if isinstance(result, list):
                return result
            return [result]
        except json.JSONDecodeError:
            # 如果不是标准 JSON，就把原始值当成单个元素
            return [value]

    return [value]


def build_artist_pairs(names_raw: Any, ids_raw: Any) -> List[Tuple[int, str]]:
    """
    根据 artist_names 和 artist_ids 生成 [(artist_id, artist_name), ...]
    """
    names = parse_json_array(names_raw)
    ids = parse_json_array(ids_raw)

    pairs = []
    min_len = min(len(names), len(ids))

    for i in range(min_len):
        artist_name = str(names[i]).strip() if names[i] is not None else ""
        artist_id_raw = ids[i]

        if not artist_name:
            continue

        try:
            artist_id = int(artist_id_raw)
        except (TypeError, ValueError):
            continue

        pairs.append((artist_id, artist_name))

    return pairs


def main():
    conn = pymysql.connect(**DB_CONFIG)

    try:
        with conn.cursor() as cursor:
            # 1. 读取 musics 表中的 artist_names 和 artist_ids
            select_sql = """
                SELECT artist_names, artist_ids
                FROM musics
                WHERE artist_names IS NOT NULL
                  AND artist_ids IS NOT NULL
            """
            cursor.execute(select_sql)
            rows = cursor.fetchall()

            # 2. 收集所有歌手，按 artist_id 去重
            artist_map = {}

            for row in rows:
                names_raw, ids_raw = row
                pairs = build_artist_pairs(names_raw, ids_raw)

                for artist_id, artist_name in pairs:
                    if artist_id not in artist_map:
                        artist_map[artist_id] = artist_name

            print(f"共解析出 {len(artist_map)} 个歌手")

            if not artist_map:
                print("没有可写入的歌手数据")
                return

            # 3. 插入 artists 表
            # 如果 artists.id 已存在，则更新 name
            insert_sql = """
                INSERT INTO artists (id, name)
                VALUES (%s, %s)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name)
            """

            data_to_insert = [(artist_id, artist_name) for artist_id, artist_name in artist_map.items()]
            cursor.executemany(insert_sql, data_to_insert)

            conn.commit()
            print(f"成功写入/更新 {cursor.rowcount} 条 artists 数据")

    except Exception as e:
        conn.rollback()
        print("执行失败：", e)
    finally:
        conn.close()


if __name__ == "__main__":
    main()