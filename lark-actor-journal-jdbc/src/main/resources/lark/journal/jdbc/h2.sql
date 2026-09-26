-- lark-actor-journal-jdbc's table on H2. Apply it with the service's own migrations; nothing creates it at start.
create table lark_journal (
    kind   varchar(255) not null,
    id     varchar(255) not null,
    seq_nr bigint       not null,
    bytes  blob         not null,
    primary key (kind, id, seq_nr)
);
