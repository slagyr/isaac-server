# Isaac Server

The Isaac HTTP host and built-in platform module (`:isaac.server`). Declares
the seven server berths (`:isaac.server/route`, `/comm`, `/tools`, `/llm-api`,
`/slash-commands`, `/hook`, `/provider`, `/provider-template`), contributes
built-in routes, tools, providers, and CLI commands (`server`, `sessions`,
`hail`, …).

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) for
module machinery and shared utilities. The server repo also carries the
host-side orchestration namespaces (`isaac.config.runtime`, comm/hail/cron
registries, session store, …) that `isaac.server.app` wires during boot.

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