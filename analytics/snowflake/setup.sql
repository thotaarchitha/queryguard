-- One-time Snowflake setup for QueryGuard's analytics. Run it in a Snowsight worksheet as
-- ACCOUNTADMIN, after pasting your public key below (README.md in this folder says how to make one).
--
-- Account-level objects only. The schema, stage and table belong to QG_DBT and are created by
-- load_events.py, so they're defined next to the code that writes into them.

use role accountadmin;

-- XSMALL is plenty for a few million rows. AUTO_SUSPEND 60: a running warehouse burns credits
-- whether or not anything is querying it, and a trial's credits go quickly that way.
create warehouse if not exists qg_wh
    warehouse_size = xsmall
    auto_suspend = 60
    auto_resume = true
    initially_suspended = true;

-- Hard stop in case something loops. 10 credits is far more than this project needs.
create resource monitor if not exists qg_cap
    with credit_quota = 10
    triggers on 80 percent do notify
             on 100 percent do suspend;
alter warehouse qg_wh set resource_monitor = qg_cap;

create role if not exists qg_dbt;
grant usage, operate on warehouse qg_wh to role qg_dbt;

create database if not exists queryguard;
grant ownership on database queryguard to role qg_dbt copy current grants;

-- So the objects show up in Snowsight when you're logged in as yourself.
grant role qg_dbt to role sysadmin;

-- TYPE = SERVICE: no password, no MFA prompt, key-pair only. That's what Snowflake wants for
-- anything that isn't a person at a keyboard.
create user if not exists qg_dbt_user
    type = service
    default_role = qg_dbt
    default_warehouse = qg_wh
    rsa_public_key = 'PASTE_THE_KEY_BODY_FROM_rsa_key.pub_HERE';
grant role qg_dbt to user qg_dbt_user;
