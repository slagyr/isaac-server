# Isaac Server

The Isaac HTTP host and built-in platform module (`:isaac.server`). Declares
the seven server berths (`:isaac.server/route`, `/comm`, `/tools`, `/llm-api`,
`/slash-commands`, `/hook`, `/provider`, `/provider-template`), contributes
built-in routes, tools, providers, and CLI commands (`server`, `sessions`,
`hail`, …).

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) for
module machinery and shared utilities. At runtime the host (the `isaac`
platform checkout) supplies agent, comm, hail, cron, and hook namespaces that
`isaac.server.app` orchestrates during boot.

## Layout

- `src/isaac/server/*` — HTTP server, routes, boot orchestration, CLI
- `resources/isaac-manifest.edn` — builtin `:isaac.server` module manifest

## Development

Sibling checkouts expected:

```
plan/
  isaac/              # platform (agent stack, tests helpers)
  isaac-foundation/
  isaac-server/       # this repo
```

```sh
bb spec       # speclj (server specs + platform classpath from ../isaac)
bb features   # gherclj server acceptance tests
bb ci         # both
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-server {:local/root "../isaac-server"}
;; or {:git/url "https://github.com/slagyr/isaac-server.git" :git/sha "..."}
```