# 🍏 Isaac Server 🖥️

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-server/main/isaac-server.png" alt="isaac-server" style="margin-right: 20px; margin-bottom: 10px;">

Isaac Server is the HTTP host and built-in platform module (`:isaac.server`). It declares the server berths, contributes built-in routes, tools, providers, and CLI commands like `server`, `sessions`, and `hail`.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) for module machinery and shared utilities. The server repo also carries the host-side orchestration namespaces that wire everything during boot.

<br>

[![Server](https://github.com/slagyr/isaac-server/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-server/actions/workflows/ci-tests.yml) 
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

## Layout

- `src/isaac/server/*` — HTTP server, routes, boot orchestration, CLI
- `src/isaac/*` — host runtime the server orchestrates (config lifecycle, comm,
  hail, hooks, session store, …)
- `features/server/` — HTTP host integration features (hooks, auth, reload, …)
- `resources/isaac-manifest.edn` — builtin `:isaac.server` module manifest
- `spec-support/` — test fixtures (`isaac.marigold`, step helpers) exported as
  `io.github.slagyr/isaac-server-test-support`

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-server/       # this repo
```

```sh
bb spec       # speclj
bb features   # gherclj server acceptance tests
bb ci         # both
```

From the JVM, compose `:test` with a runner alias (shared test deps live in
`:test` only):

```sh
clj -M:test:spec       # speclj specs
clj -M:test:features   # gherclj acceptance tests
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-server {:local/root "../isaac-server"}
;; or {:git/url "https://github.com/slagyr/isaac-server.git" :git/sha "..."}
```