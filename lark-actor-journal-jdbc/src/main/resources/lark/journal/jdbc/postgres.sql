-- lark-actor-journal-jdbc's tables on Postgres. Apply them with the service's own migrations; nothing creates them at start.
create table lark_journal (
    kind   varchar(255) not null,
    id     varchar(255) not null,
    seq_nr bigint       not null,
    bytes  bytea        not null,
    primary key (kind, id, seq_nr)
);

-- JdbcSnapshots' table (spec 0074): one row per id, the newest snapshot saved.
create table lark_snapshot (
    kind   varchar(255) not null,
    id     varchar(255) not null,
    seq_nr bigint       not null,
    bytes  bytea        not null,
    primary key (kind, id)
);
