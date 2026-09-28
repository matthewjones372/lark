--liquibase formatted sql

-- lark-actor-journal-jdbc's tables on H2, as a Liquibase changelog: include it from the service's own
-- (`<include file="lark/journal/jdbc/h2.sql"/>`). The same changesets as postgres.sql; see it for what each is.

--changeset lark:journal
create table if not exists lark_journal (
    kind   varchar(255) not null,
    id     varchar(255) not null,
    seq_nr bigint       not null,
    bytes  blob         not null,
    ordering bigint generated always as identity,
    primary key (kind, id, seq_nr)
);
create unique index if not exists lark_journal_ordering on lark_journal (ordering);
create index if not exists lark_journal_kind_ordering on lark_journal (kind, ordering);

create table if not exists lark_journal_pruned (
    from_ordering bigint not null,
    to_ordering   bigint not null
);
create index if not exists lark_journal_pruned_to on lark_journal_pruned (to_ordering);

create table if not exists lark_snapshot (
    kind   varchar(255) not null,
    id     varchar(255) not null,
    seq_nr bigint       not null,
    bytes  blob         not null,
    primary key (kind, id)
);

create table if not exists lark_offset (
    name          varchar(255) not null,
    last_ordering bigint       not null,
    primary key (name)
);

--changeset lark:0105-slices
alter table lark_journal add column if not exists slice integer;
create index if not exists lark_journal_slice on lark_journal (slice);

create table if not exists lark_journal_fenced (
    slice integer not null,
    primary key (slice)
);

create table if not exists lark_journal_slices (
    version    bigint       not null,
    from_slice integer      not null,
    to_slice   integer      not null,
    owner      varchar(255) not null,
    primary key (version, from_slice)
);

create table if not exists lark_journal_moves (
    version     bigint       not null,
    from_slice  integer      not null,
    to_slice    integer      not null,
    source      varchar(255) not null,
    target      varchar(255) not null,
    copied_to   bigint       not null,
    switched_at bigint       not null,
    cleaned     boolean      not null,
    primary key (version)
);

--changeset lark:0106-kind-slice
create index if not exists lark_journal_kind_slice on lark_journal (kind, slice, ordering);
