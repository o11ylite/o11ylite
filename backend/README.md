# O11yLite Backend

## Prerequisites

Before running the backend, you must run the setup script from the project root:

```shell
./dev/setup
```

This downloads OpenTelemetry proto definitions and compiles them to Java classes. Requires:
- `protoc` - Protocol buffer compiler (`brew install protobuf`)
- Java 21+

## Run the dev service

Run the service (clojure.main)

```shell
clojure -M:run/dev
```

## Development

Practicalli workflow overview:

- start a REPL process in a Terminal
- open the project in a Clojure Editor and connected to the REPL
- write code and evaluate expressions in the editor using the source code files

[Practicalli Clojure CLI Config](https://practical.li/clojure/clojure-cli/practicalli-config/) should be used with this project to support all aliases used.

This project uses `make` tasks to run the Clojure tests, kaocha test runner and package the service into an uberjar.  The `Makefile` uses `clojure` commands and arguments which can be used directly if not using `make`.

`make` command in a terminal will list all the tasks available

```shell
make
```


### Run Clojure REPL

Start the REPL with the [Practicalli REPL Reloaded](https://practical.li/clojure/clojure-cli/repl-reloaded/) aliases to include the custom `user` namespace (`dev/user.clj`) which provides additional tools for development (Portal data inspector, hotload libraries, namespace reload)

```shell
make repl
```

The local nREPL server port will be printed, along with a help menu showing the REPL Reloaded tools available.

Evaluate the o11ylite.backend namespace and a mulog publisher will start, sending pretty printed events to the console. Evaluate `(mulog-publisher)` to stop the mulog publisher.

Call the `-main` function with or without an argument, or call the `greet` function directly passing an optional key and value pair.

`(namespace/refresh)` will reload any changed namespaces in the Clojure project.


### Clojure Editor

If a REPL has been run from a terminal, use the editor **connect*- feature.

Otherwise, use the `:dev/reloaded` alias from Practicalli Clojure CLI Config to starting a REPL process from within a Clojure editor.


### Unit tests

Run unit tests of the service using the kaocha test runner

```shell
make test
```

> If additional libraries are required to support tests, add them to the `:test/env` alias definition in `deps.edn`

`make test-watch` will run tests on file save, stopping the current test run on the first failing test.  Tests will continue to be watched until `Ctrl-c` is pressed.

## Protocol Buffers & gRPC

The backend includes a gRPC server for receiving OpenTelemetry data. Protocol buffer definitions are compiled to Java classes using `protoc` and the grpc-java plugin.

### Directory Structure

```
backend/
├── proto/                    # .proto source files
│   └── o11ylite/proto/
│       └── echo.proto        # Example service definition
├── java-src/                 # Generated Java source (committed)
│   └── o11ylite/proto/echo/
│       ├── EchoRequest.java
│       ├── EchoResponse.java
│       └── DummyServiceGrpc.java
├── classes/                  # Compiled .class files (not committed)
└── .bin/                     # Downloaded protoc plugins (not committed)
```

### Workflow

1. **Edit `.proto` files** in `proto/` directory

2. **Generate and compile** Java classes:
   ```shell
   make proto-compile
   ```
   This will:
   - Download `protoc-gen-grpc-java` plugin (first time only)
   - Generate Java source files to `java-src/`
   - Compile to `.class` files in `classes/`

3. **Use in Clojure** - import the generated classes:
   ```clojure
   (:import [o11ylite.proto.echo EchoRequest EchoResponse DummyServiceGrpc$DummyServiceImplBase])
   ```

### Make Targets

| Target | Description |
|--------|-------------|
| `make proto-gen` | Generate Java source from .proto files |
| `make proto-compile` | Generate + compile to .class files |
| `make proto-clean` | Remove generated files |
| `make proto-rebuild` | Clean and regenerate everything |
| `make otel-proto-download` | Download OpenTelemetry proto definitions |
| `make otel-proto-clean` | Remove downloaded OpenTelemetry protos |

### Requirements

- **protoc** - Protocol buffer compiler
  ```shell
  brew install protobuf
  ```
- **Java 21+** - For compilation (virtual threads support)

The `protoc-gen-grpc-java` plugin is downloaded automatically by the Makefile.

### Version Compatibility

The `protoc` version must match the `protobuf-java` dependency in `deps.edn`:

| protoc version | protobuf-java version |
|----------------|----------------------|
| 33.x | 4.33.x |
| 32.x | 4.32.x |

Check your protoc version: `protoc --version`

### Adding New Proto Files

1. Create `.proto` file in `proto/` with appropriate package:
   ```protobuf
   syntax = "proto3";
   package o11ylite.proto.myservice;
   
   option java_multiple_files = true;
   option java_package = "o11ylite.proto.myservice";
   ```

2. Run `make proto-compile`

3. Import generated classes in Clojure code

### OpenTelemetry Protos

Download the official OpenTelemetry protocol buffer definitions:

```shell
make otel-proto-download
```

This downloads OTLP proto files (v1.9.0) for:
- `common/v1` - Common types (KeyValue, AnyValue, etc.)
- `resource/v1` - Resource definition
- `trace/v1` - Trace/Span data model
- `metrics/v1` - Metrics data model  
- `logs/v1` - Logs data model
- `collector/*/v1` - OTLP service definitions

The downloaded files are gitignored and must be downloaded locally.

### Notes

- All generated files are gitignored: `java-src/`, `classes/`, `.bin/`, `proto/opentelemetry/`
- Run `./dev/setup` after cloning or when proto definitions change
- The setup script handles both downloading OTel protos and compiling all Java classes

## Format Code

Check the code format before pushing commits to a shared repository, using cljstyle to check the Clojure format, MegaLinter to check format of all other files and kaocha test runner to test the Clojure code.

Before running the `pre-commit-check`

- [install cljstyle](https://github.com/greglook/cljstyle/releases){target=_blank}
- MegaLinter runs in a Docker container, so ensure Docker is running

```shell
make pre-commit-check
```

Run cljstyle only

- `make format-check` runs cljstyle and and prints a report if there are errors
- `make format-fix` updates all files if there are errors (check the changes made via `git diff`)

Run MegaLinter only

- `make lint` runs all configured linters in `.github/config/megalinter.yaml`
- `make lint-fix` as above and applies fixes

Run Kaocha test runner only

- `make test` runs all unit tests in the project, stopping at first failing test
- `make test-watch` detect file changes and run all unit tests in the project, stopping at first failing test


## Deployment

Build an uberjar to deploy the service as a jar file

```shell
make build-uberjar
```

- `make build-config` displays the tools.build configuration
- `make build-clean` deletes the build assets (`target` directory)

```shell
make docker-build
```

- `make docker-down` shuts down all services started with `docker-build`
- `make docker-build-clean`

Or build and run the service via the multi-stage `Dockerfile` configuration as part of a CI workflow.


## License

Copyright © 2025 Ming

[Creative Commons Attribution Share-Alike 4.0 International](http://creativecommons.org/licenses/by-sa/4.0/")
