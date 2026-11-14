# QueryGuard events in Snowflake

The live pipeline is Kafka → ClickHouse, and that's not changing. This folder copies the query
events into Snowflake and runs the same dbt project there, with the same tests.

I did it to learn Snowflake on data I already understand, and to see how much of a dbt project
is really warehouse-specific. Answer: one macro file. The models are plain SQL except for eight
things the two warehouses spell differently, all in
[`../dbt/macros/cross_warehouse.sql`](../dbt/macros/cross_warehouse.sql). The interesting one is
deduplication: ClickHouse's ReplacingMergeTree collapses Kafka redeliveries for you, and
Snowflake has nothing like it, so the staging model does it with `QUALIFY row_number()`.

## Setup (once)

**1. A key pair.** dbt and the loader log in as a service user with a key, not a password.
Keep the private key out of this repo:

```bash
mkdir -p ~/.snowflake
openssl genrsa 2048 | openssl pkcs8 -topk8 -inform PEM -out ~/.snowflake/qg_rsa_key.p8 -nocrypt
openssl rsa -in ~/.snowflake/qg_rsa_key.p8 -pubout -out ~/.snowflake/qg_rsa_key.pub
```

**2. Account objects.** Open [`setup.sql`](setup.sql) in a Snowsight worksheet, replace the
`rsa_public_key` placeholder with the body of `qg_rsa_key.pub` (the lines between the BEGIN and
END markers, joined into one line), and run it as ACCOUNTADMIN. It creates an XSMALL warehouse
that suspends after 60 seconds idle, a 10-credit resource monitor, the `QG_DBT` role, the
`QUERYGUARD` database and the service user.

**3. Python packages**, in a virtualenv:

```bash
pip install -r requirements.txt
```

**4. Environment.** The account identifier is in Snowsight under your name → Connect a tool to
Snowflake, in the form `orgname-accountname`:

```bash
export SNOWFLAKE_ACCOUNT=myorg-myaccount
export SNOWFLAKE_PRIVATE_KEY_PATH=~/.snowflake/qg_rsa_key.p8
```

## Run it

With the compose stack up and some traffic in ClickHouse:

```bash
python load_events.py export     # one Parquet file per day into export/
python load_events.py load       # PUT to a stage, COPY INTO qg.query_events
cd ../dbt && dbt build --target snowflake --profiles-dir .
```

`load` is safe to rerun: COPY skips files it has already loaded. With PowerShell instead of
bash, set the variables with `$env:SNOWFLAKE_ACCOUNT = "..."`.

## Results

Run on a Snowflake trial account (AWS us-east-2, X-Small warehouse), November 2026:

| Step | Result |
|---|---|
| Export from ClickHouse | 3,751,907 events, two daily Parquet files (52 + 129 MB, Snappy), ~4 s |
| `load` (PUT + COPY INTO) | all rows loaded, 0 rejected, 57 s end to end from my laptop; the COPY itself 18 s |
| `dbt build --target snowflake` | **19/19 passing**, 24 s, first run |
| Snowflake marts vs ClickHouse marts | **identical** on every column of all three marts, except p95 |
| p95 latency | median 0.3% apart, worst 7% (both warehouses approximate it differently) |

Most of the 57 s load is uploading 181 MB from a laptop; the warehouse only works during COPY.
At X-Small (1 credit an hour, billed per second, 60 s minimum after each resume), the whole
exercise is a few minutes of warehouse time, roughly 0.1 credit. I'll put the metered figure
here once Snowflake's usage view catches up; it lags by a few hours.

How I checked the port before any of that:

- I built the **old** ClickHouse-only models first, then the new ones, and diffed the marts.
  Same results except float sums differing in the 13th digit (summation order), the sampled
  p95, and one real fix the diff turned up: some query shapes carry two `statement_type`
  labels (VACUUM logged as ALTER before the MAINTENANCE kind existed, OTHER on timed-out
  decisions), and `any()` picked one at random. It now takes the newest label.
- Before the account existed, the Snowflake SQL was compiled and parsed with sqlglot, and the
  Parquet files were checked against every field the COPY reads. The first real run passed,
  which is the point of checking first.

## Differences worth knowing

| | ClickHouse | Snowflake |
|---|---|---|
| Duplicates from Kafka | ReplacingMergeTree + `FINAL` | `QUALIFY row_number()` in staging |
| p95 latency | `quantile(0.95)`, sampled | `approx_percentile`, t-digest. Close, not identical |
| `database` column | fine | reserved word, quoted as `"DATABASE"` |
| Loading | streams from Kafka, sub-second | batch `COPY INTO` from Parquet |
| Idle cost | the server you run | nothing while the warehouse is suspended |

Snowflake here is a batch copy for analysis, not a replacement for the live path. Feeding it
continuously would mean Snowpipe Streaming or the Kafka connector, which I haven't built.
