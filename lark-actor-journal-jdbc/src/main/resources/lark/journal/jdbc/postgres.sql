-- lark-actor-journal-jdbc's table on Postgres. Apply it with the service's own migrations; nothing creates it at start.
create table lark_journal (
    kind   varchar(255) not null,
    id     varchar(255) not null,
    seq_nr bigint       not null,
    bytes  bytea        not null,
    primary key (kind, id, seq_nr)
);
