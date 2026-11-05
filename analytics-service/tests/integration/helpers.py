import psycopg


def fetch(url, query, *params):
    with psycopg.connect(url) as conn:
        return conn.execute(query, params).fetchall()
