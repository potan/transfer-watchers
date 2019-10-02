This is a companion app to: https://rchain.atlassian.net/wiki/spaces/CORE/pages/757235721/Data+reporting

THIS IS PoC. IT IS NOT PRODUCTION-GRADE.
====

TL;DR
A simpliscit block explorer using RChains `reporting` API.

Setup
----
Play framework with akka web sockets and psql.

This app assumes a running isnstance of rnode is available. (vide: `application.conf` `rchain.url`)
It also assumes a postgres instance is up.
```
docker run --name transfersql -p 5432:5432 -e POSTGRES_PASSWORD=test123 -e POSTGRES_USER=test -e POSTGRES_DB=transfers -d postgres
```

To run the app `sbt run` and connect to `localhost:9000` to allow the db evolutions to execute.

Details
----
On startup the app connects to a websocket availale in RNode (`ws/events`).
It listens for two types of events:
- block added
- block finalized

**block addeed** is dispatched to `BlockController` and processed if it was never seen before:
- the block itself is stored
- the parent information is stored

**block finalized** is dispatched to `BlockController` and processed if it was seen before:
- the block is marked finalized
- all the parents of the given block are marked finalized

Available endpoints:
- **blocks** is a listing of all seen blocks (`/blocks`)
- **all transfers** is a listing of all seen transfers (`/transfers/all`)
- **vault transfers** is a listing of transfers *from* or *to* a given vault (`/transfers/:vault`)
- **trace** (`trace/:hash`) is used to manually trigger the `reporting API`
  - the RNode instance is queried for an execution trace for the given block (`reporting/trace`)
  - the trace is scraped for transfer information which is stored

Scraping
----
The process is simplistic and focuses on getting the interesting data from the trace:
- each deploy that does not contain a transfer (`BlockLogic.transferContract`) is pruned
- the first `consume` event that contains a `transfer` invocation (`@{x7}!("transfer"`) is inspected for the vault public names (this is based on how the contract in written and how the rholang interpreter works)
- the 'operation specific' `produces` are found (by the fact that a `)}!((x-1 +/- amount)` has to appear as a `COMM` in the trace) and the unforgeable names are extracted

Missing pieces
----
- a proper big data approach to building the dag; a single table in a dockerized psql instance will not fly for long
- a proper scraping mechanism that can verify modes of failure and can distinguish between a successful transfer and a suspended one
- and lots more, as stated: this is only a PoC done in a few days to show the APIs

