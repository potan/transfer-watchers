# --- !Ups

create table "blocks" (
  "hash" varchar(256) not null primary key,
  "seq_num" bigint not null,
  "finalized" boolean
);

# --- !Downs

drop table "blocks" if exists;