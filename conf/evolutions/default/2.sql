# --- !Ups

create table "parents" (
  "hash" varchar(256) references blocks(hash),
  "parent" varchar(256)
);

# --- !Downs

drop table "parents" if exists;