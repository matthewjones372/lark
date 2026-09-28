--liquibase formatted sql

-- lark-actor-journal-jdbc's tables on Postgres, as a Liquibase changelog: include it from the service's own
-- (`<include file="lark/journal/jdbc/postgres.sql"/>`). Nothing creates these tables at start. Every changeset is
-- written `if not exists`, so a database made from this file before it was a changelog takes it up without failing.

--changeset lark:journal
-- Every event, one row each (spec 0072).
create table if not exists lark_journal (
    kind   varchar(255) not null,
    id     varchar(255) not null,
    seq_nr bigint       not null,
    bytes  bytea        not null,
    -- Where the event stands among every event appended (spec 0075): what a feed's offsets are.
    ordering bigint generated always as identity,
    primary key (kind, id, seq_nr)
);
create unique index if not exists lark_journal_ordering on lark_journal (ordering);
create index if not exists lark_journal_kind_ordering on lark_journal (kind, ordering);

-- The orderings each deletion (spec 0076) spanned, so the feed reads past rows deleted rather than waiting on them.
create table if not exists lark_journal_pruned (
    from_ordering bigint not null,
    to_ordering   bigint not null
);
create index if not exists lark_journal_pruned_to on lark_journal_pruned (to_ordering);

-- JdbcSnapshots' table (spec 0074): one row per id, the newest snapshot saved.
create table if not exists lark_snapshot (
    kind   varchar(255) not null,
    id     varchar(255) not null,
    seq_nr bigint       not null,
    bytes  bytea        not null,
    primary key (kind, id)
);

-- JdbcOffsets' table (spec 0075): the last offset each read model handled, by its name.
create table if not exists lark_offset (
    name          varchar(255) not null,
    last_ordering bigint       not null,
    primary key (name)
);

--changeset lark:0105-slices
-- The id's slice (spec 0105), set on append. Null only on rows older than the column: run JdbcJournal.fillSlices
-- until it answers 0 before moving any slice.
alter table lark_journal add column if not exists slice integer;
create index if not exists lark_journal_slice on lark_journal (slice);

-- Slices this database refuses appends for: given to another database, or on their way to one.
create table if not exists lark_journal_fenced (
    slice integer not null,
    primary key (slice)
);

-- Which database owns each range of slices, by version. Kept in the first database only.
create table if not exists lark_journal_slices (
    version    bigint       not null,
    from_slice integer      not null,
    to_slice   integer      not null,
    owner      varchar(255) not null,
    primary key (version, from_slice)
);

-- Each range moved, until its rows are cleaned from the source. Kept in the first database only.
create table if not exists lark_journal_moves (
    version     bigint       not null,
    from_slice  integer      not null,
    to_slice    integer      not null,
    source      varchar(255) not null,
    target      varchar(255) not null,
    -- The source's highest ordering in the range when it switched: what its projections must pass before cleaning.
    copied_to   bigint       not null,
    switched_at bigint       not null,
    cleaned     boolean      not null,
    primary key (version)
);

--changeset lark:0106-kind-slice runInTransaction:false
-- A partition of a read model reads its own slices in the feed's order (spec 0106). Built concurrently, so a large
-- journal goes on taking appends, which Postgres allows only outside a transaction.
create index concurrently if not exists lark_journal_kind_slice on lark_journal (kind, slice, ordering);
