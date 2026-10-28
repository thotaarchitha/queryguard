FROM python:3.12-slim
RUN pip install --no-cache-dir "dbt-core==1.8.8" "dbt-clickhouse==1.8.4"
WORKDIR /dbt
COPY . .
ENV DBT_PROFILES_DIR=/dbt
ENTRYPOINT ["dbt"]
CMD ["build"]
