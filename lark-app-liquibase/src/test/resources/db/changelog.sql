--liquibase formatted sql

--changeset lark:1
create table orders (
    id    integer primary key,
    total integer
);
