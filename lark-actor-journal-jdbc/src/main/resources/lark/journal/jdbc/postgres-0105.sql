-- Spec 0105 on a journal created before it: what postgres.sql now creates, added to its tables. Apply it with the
-- service's own migrations, then run JdbcJournal.fillSlices until it answers 0 before moving any slice.
alter table lark_journal add column slice integer;
create index lark_journal_slice on lark_journal (slice);

create table lark_journal_fenced (
    slice integer not null,
    primary key (slice)
);

create table lark_journal_slices (
    version    bigint       not null,
    from_slice integer      not null,
    to_slice   integer      not null,
    owner      varchar(255) not null,
    primary key (version, from_slice)
);

create table lark_journal_moves (
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
