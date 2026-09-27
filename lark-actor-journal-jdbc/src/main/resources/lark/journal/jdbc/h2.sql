-- lark-actor-journal-jdbc's tables on H2. Apply them with the service's own migrations; nothing creates them at start.
create table lark_journal (
    kind   varchar(255) not null,
    id     varchar(255) not null,
    seq_nr bigint       not null,
    bytes  blob         not null,
    -- Where the event stands among every event appended (spec 0075): what a feed's offsets are.
    ordering bigint generated always as identity,
    primary key (kind, id, seq_nr)
);
create unique index lark_journal_ordering on lark_journal (ordering);
create index lark_journal_kind_ordering on lark_journal (kind, ordering);

-- JdbcSnapshots' table (spec 0074): one row per id, the newest snapshot saved.
create table lark_snapshot (
    kind   varchar(255) not null,
    id     varchar(255) not null,
    seq_nr bigint       not null,
    bytes  blob         not null,
    primary key (kind, id)
);
