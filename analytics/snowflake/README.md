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

`load` is safe to rerun: COPY skips files it has already loaded.

## Results

**Checked so far (no Snowflake account involved):**

- `export` on the full ClickHouse table: 3,751,907 events in two daily files (52 MB and 129 MB,
  Snappy), about 4 seconds. Every field the COPY reads is present, timestamps and arrays parse.
- dbt on ClickHouse after the port: 19/19 passing. I built the old models first and diffed the
  marts against the new ones. Identical except float sums differing in the 13th digit
  (summation order), p95 (sampled, different every run), and `dim_query_shapes.statement_type`,
  which the port fixed: some query shapes carry two labels, and `any()` picked one at random.
  It now takes the newest label.
- dbt compiles for Snowflake, and all 19 compiled statements parse as Snowflake SQL (sqlglot).

**Not yet:** a real `load` and `dbt build --target snowflake`. Load time, dbt run time and
credits used go here once they've run.

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
