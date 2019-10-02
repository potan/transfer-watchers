# --- !Ups

create table "transfers" (
  "hash" varchar(256) references blocks(hash),
  "deploy" varchar(256),
  "fromVault" varchar(256),
  "toVault" varchar(256),
  "fromName" varchar(256),
  "toName" varchar(256),
  "fromBalance" varchar(256),
  "toBalance" varchar(256),
  "amount" varchar(256)
);

# --- !Downs

drop table "transfers" if exists;