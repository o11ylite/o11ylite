# Backend Testing

## Running Tests

Run all tests:

```shell
make test
```

Run tests in watch mode (re-runs on file changes):

```shell
make test-watch
```

## Single Test

Run a specific test:

```shell
clojure -M:test/env:test/run --focus o11ylite.integration.api.health-test/api-status-test
```

## REPL Testing

Start the REPL (the Integrant system is dormant until `(go)`):

```shell
clojure -M:repl/reloaded
```

Then, from an editor or with `dev/repl-eval`:

```clojure
(require 'o11ylite.integration.api.health-test) ; run-tests does not load it
(require '[clojure.test :refer [run-tests]])
(run-tests 'o11ylite.integration.api.health-test)
```

See [DEVELOPMENT.md](../DEVELOPMENT.md) for the full REPL workflow.
