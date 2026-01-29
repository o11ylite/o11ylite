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
clojure -M:test/env:test/run --focus o11ylite.integration.health-test/api-status-test
```

## REPL Testing

Start the REPL:

```shell
cd backend && clojure -M:run/dev
```

Run tests from REPL:

```clojure
(require '[clojure.test :refer [run-tests]])
(run-tests 'o11ylite.integration.health-test)
```
