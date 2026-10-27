--liquibase formatted sql

-- The example's own table, after the modules' changelogs (see Schema.changelogs).

--changeset example:bookings
create table bookings (
    id        bigint      generated always as identity primary key,
    -- Filled from the transaction's tenant, so an insert cannot name another.
    tenant_id text        not null default current_setting('app.tenant_id', true),
    name      text        not null,
    starts_at timestamptz not null
);
create index bookings_tenant on bookings (tenant_id);

alter table bookings enable row level security;
alter table bookings force row level security;

create policy tenant_isolation on bookings
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
