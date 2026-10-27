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

--changeset example:courts
-- What a venue lets people book: its courts, or its stylists. Spec 0232.
create table courts (
    tenant_id text not null default current_setting('app.tenant_id', true),
    name      text not null,
    primary key (tenant_id, name)
);

alter table courts enable row level security;
alter table courts force row level security;

create policy tenant_isolation on courts
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

-- The exclusion constraint below compares text with `=` in a GiST index, which this contrib extension provides.
create extension if not exists btree_gist;

-- Each slot a booking holds. Postgres refuses two that overlap on one court, so a clash cannot slip between a check
-- and an insert: the second transaction fails, and makes nothing.
create table booking_slots (
    booking_id bigint    not null references bookings (id),
    tenant_id  text      not null default current_setting('app.tenant_id', true),
    court      text      not null,
    during     tstzrange not null check (not isempty(during)),
    foreign key (tenant_id, court) references courts (tenant_id, name),
    exclude using gist (tenant_id with =, court with =, during with &&)
);
create index booking_slots_booking on booking_slots (booking_id);

alter table booking_slots enable row level security;
alter table booking_slots force row level security;

create policy tenant_isolation on booking_slots
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

--changeset example:limits
-- Who made each booking, so a venue's limit counts what one member holds. Spec 0232.
alter table bookings add column member text;

-- How many slots one member may hold in a day and in an ISO week, in the venue's own zone. No row, no limit.
create table booking_limits (
    tenant_id text    primary key default current_setting('app.tenant_id', true),
    per_day   integer not null check (per_day > 0),
    per_week  integer not null check (per_week > 0),
    zone      text    not null
);

alter table booking_limits enable row level security;
alter table booking_limits force row level security;

create policy tenant_isolation on booking_limits
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));

-- One row per member, locked by each of their bookings before it counts: two bookings from one member queue, and the
-- second counts the first. Other members never wait on it.
create table member_quota (
    tenant_id text not null default current_setting('app.tenant_id', true),
    member    text not null,
    primary key (tenant_id, member)
);

alter table member_quota enable row level security;
alter table member_quota force row level security;

create policy tenant_isolation on member_quota
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
