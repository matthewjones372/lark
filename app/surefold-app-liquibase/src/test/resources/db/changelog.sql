--liquibase formatted sql

--changeset surefold:1
create table orders (
    id    integer primary key,
    total integer
);
