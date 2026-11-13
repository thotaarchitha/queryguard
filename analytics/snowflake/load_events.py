"""Copy QueryGuard's query events from ClickHouse into Snowflake.

    python load_events.py export   # ClickHouse -> export/query_events_<day>.parquet
    python load_events.py load     # export/*.parquet -> stage -> QUERYGUARD.QG.QUERY_EVENTS

export needs only the standard library and ClickHouse running (docker compose up).
load needs snowflake-connector-python (installed with dbt-snowflake) and the SNOWFLAKE_* env
vars from README.md.

This is a one-off copy, not a second pipeline: the live path stays Kafka -> ClickHouse.
"""

import glob
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
EXPORT_DIR = os.path.join(HERE, "export")

# Exported raw, without FINAL, so Kafka redeliveries come along. Deduplication is
# stg_query_events' job on both warehouses, and this way that path actually gets exercised.
#
# Timestamps go out as text and arrays as JSON text. Parquet timestamps and LISTs load
# differently depending on Snowflake's scanner settings (USE_LOGICAL_TYPE, vectorized scanner);
# strings plus an explicit cast in the COPY don't depend on either.
EXPORT_SQL = """
SELECT
    event_id,
    toString(ts)                               AS ts,
    proxy_instance, user, role, database, client_ip, application, statement_type,
    fingerprint, sql_normalized,
    toJSONString(tables)                       AS tables_json,
    decision, rule_id, estimated_cost, estimated_rows, decision_latency_us,
    query_latency_ms, rows_returned,
    toJSONString(masked_columns)               AS masked_columns_json,
    error_code,
    toString(toDateTime64(ingested_at, 3))     AS ingested_at
FROM qg.query_events
WHERE toDate(ts) = '{day}'
FORMAT Parquet
SETTINGS output_format_parquet_string_as_string = 1,
         output_format_parquet_compression_method = 'snappy'
"""

DDL = [
    "create schema if not exists qg",
    "create stage if not exists qg.events_stage",
    """
    create table if not exists qg.query_events (
        event_id            string,
        ts                  timestamp_ntz,      -- UTC, as in ClickHouse
        proxy_instance      string,
        user                string,
        role                string,
        "DATABASE"          string,             -- reserved word in Snowflake
        client_ip           string,
        application         string,
        statement_type      string,
        fingerprint         string,
        sql_normalized      string,
        tables              array,
        decision            string,
        rule_id             string,
        estimated_cost      float,
        estimated_rows      float,
        decision_latency_us number,
        query_latency_ms    float,
        rows_returned       number,
        masked_columns      array,
        error_code          string,
        ingested_at         timestamp_ntz
    )
    """,
]

# COPY remembers which staged files it already loaded (for 64 days), so running load twice
# doesn't double the table. A re-export gets new bytes under the same name and would be
# loaded again, which the staging model's dedupe absorbs.
COPY_SQL = """
copy into qg.query_events
from (
    select
        $1:event_id::string, $1:ts::timestamp_ntz, $1:proxy_instance::string, $1:user::string,
        $1:role::string, $1:database::string, $1:client_ip::string, $1:application::string,
        $1:statement_type::string, $1:fingerprint::string, $1:sql_normalized::string,
        parse_json($1:tables_json::string)::array, $1:decision::string, $1:rule_id::string,
        $1:estimated_cost::float, $1:estimated_rows::float, $1:decision_latency_us::number,
        $1:query_latency_ms::float, $1:rows_returned::number,
        parse_json($1:masked_columns_json::string)::array, $1:error_code::string,
        $1:ingested_at::timestamp_ntz
    from @qg.events_stage
)
pattern = '.*query_events_.*[.]parquet'
file_format = (type = parquet)
"""


def clickhouse(sql, out=None):
    """Run a query over ClickHouse's HTTP interface. Streams to `out` if given."""
    url = os.environ.get("CLICKHOUSE_URL", "http://localhost:8123/")
    req = urllib.request.Request(url, data=sql.encode(), headers={
        "X-ClickHouse-User": os.environ.get("CLICKHOUSE_USER", "qg"),
        "X-ClickHouse-Key": os.environ.get("CLICKHOUSE_PASSWORD", "qg"),
    })
    with urllib.request.urlopen(req, timeout=600) as resp:
        if out is None:
            return resp.read().decode()
        while chunk := resp.read(1 << 20):
            out.write(chunk)


def export():
    os.makedirs(EXPORT_DIR, exist_ok=True)
    days = clickhouse(
        "SELECT toDate(ts) AS d, count() FROM qg.query_events GROUP BY d ORDER BY d FORMAT TabSeparated"
    ).split()
    if not days:
        sys.exit("qg.query_events is empty. Run the stack with traffic first (make up && make traffic).")

    total = 0
    for day, rows in zip(days[::2], days[1::2]):
        path = os.path.join(EXPORT_DIR, f"query_events_{day}.parquet")
        with open(path, "wb") as f:
            clickhouse(EXPORT_SQL.format(day=day), out=f)
        total += int(rows)
        print(f"{day}  {int(rows):>10,} rows  {os.path.getsize(path) / 1e6:7.1f} MB")
    print(f"total {total:,} rows in {EXPORT_DIR}")


def connect():
    import snowflake.connector  # only load needs it

    missing = [v for v in ("SNOWFLAKE_ACCOUNT", "SNOWFLAKE_PRIVATE_KEY_PATH") if not os.environ.get(v)]
    if missing:
        sys.exit(f"set {', '.join(missing)} first (see README.md)")
    return snowflake.connector.connect(
        account=os.environ["SNOWFLAKE_ACCOUNT"],
        user=os.environ.get("SNOWFLAKE_USER", "QG_DBT_USER"),
        private_key_file=os.environ["SNOWFLAKE_PRIVATE_KEY_PATH"],
        role="QG_DBT",
        warehouse="QG_WH",
        database="QUERYGUARD",
    )


def load():
    files = sorted(glob.glob(os.path.join(EXPORT_DIR, "query_events_*.parquet")))
    if not files:
        sys.exit("nothing to load, run export first")

    with connect() as conn, conn.cursor() as cur:
        for stmt in DDL:
            cur.execute(stmt)
        for path in files:
            # forward slashes: PUT wants a file:// URL, even on Windows
            uri = "file://" + path.replace("\\", "/")
            cur.execute(f"put '{uri}' @qg.events_stage auto_compress = false overwrite = false")
            print(f"staged  {os.path.basename(path)}")

        cur.execute(COPY_SQL)
        for row in cur.fetchall():
            # (file, status, rows_parsed, rows_loaded, ...) or a single "0 files processed" row
            print("copy   ", " ".join(str(c) for c in row[:4]))

        cur.execute("select count(*), count(distinct event_id) from qg.query_events")
        rows, distinct = cur.fetchone()
        print(f"qg.query_events: {rows:,} rows, {distinct:,} distinct events "
              f"({rows - distinct:,} duplicates for stg_query_events to drop)")


if __name__ == "__main__":
    commands = {"export": export, "load": load}
    if len(sys.argv) != 2 or sys.argv[1] not in commands:
        sys.exit(__doc__)
    commands[sys.argv[1]]()
