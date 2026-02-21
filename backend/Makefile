# -------------------------------------- #
# O11yLite Makefile
#
# Self-contained build system - no external Clojure CLI config required
# All aliases defined in deps.edn
#
# `-` before a command ignores any errors returned

# Requirements
# - Java (see .tool-versions for pinned version)
# - Clojure CLI (see .tool-versions for pinned version)
# -------------------------------------- #

# -- Makefile task config -------------- #
# .PHONY: ensures target used rather than matching file name
# https://makefiletutorial.com/#phony
.PHONY: all clean deps dist repl test test-ci test-watch
# -------------------------------------- #

# -- Makefile Variables ---------------- #
# run help if no target specified
.DEFAULT_GOAL := help
# Column the target description is printed from
HELP-DESCRIPTION-SPACING := 24

# Application version (override with `make dist VERSION=1.0.0`)
VERSION := dev

# Tool variables
CLOJURE_TEST_RUNNER := clojure -M:test/env:test/run
CLOJURE_EXEC_TEST_RUNNER := clojure -X:test/env:test/run

# Makefile file and directory name wildcard
# EDN-FILES := $(wildcard *.edn)
# -------------------------------------- #

# -- Clojure Workflow ------------------ #
repl: reloaded

reloaded:  ## Run Clojure REPL with rich terminal UI (Rebel Readline)
	$(info -- Run Rebel REPL ------------------------)
	clojure -M:otel:repl/reloaded:lib/integrant-repl

deps: deps.edn  ## Prepare dependencies for test and dist targets
	$(info -- Download test and service libraries ---)
	clojure -P -M:test/run && clojure -P -T:build/task

dist: build-uberjar ## Build and package Clojure service
	$(info -- Build and Package Clojure service -----)

clean:  ## Clean Clojure tooling temporary files
	$(info -- Clean Clojure temporary files ---------)
	- rm -rf ./.cpcache ./.clj-kondo/cache ./.lsp ./.tmp

run:  ## Run Service using clojure.main
	$(info -- Download test and service libraries ---)
	clojure -M:run/service
# -------------------------------------- #

# -- Testing --------------------------- #
test-config:  ## Print Kaocha test runner configuration
	$(info -- Runner Configuration ------------------)
	$(CLOJURE_TEST_RUNNER) --print-config

test-profile:  ## Profile unit test speed, showing 3 slowest tests
	$(info -- Runner Profile Tests ------------------)
	$(CLOJURE_TEST_RUNNER) --plugin  kaocha.plugin/profiling

test:  ## Run unit tests - stoping on first error
	$(info -- Runner for unit tests -----------------)
	$(CLOJURE_EXEC_TEST_RUNNER)

test-all:  ## Run all unit tests regardless of failing tests
	$(info -- Runner for all unit tests -------------)
	$(CLOJURE_EXEC_TEST_RUNNER) :fail-fast? false

test-watch:  ## Run tests when changes saved, stopping test run on first error
	$(info -- Watcher for unit tests, fail fast -----)
	$(CLOJURE_EXEC_TEST_RUNNER) :watch? true

test-watch-all:  ## Run all tests when changes saved, regardless of failing tests
	$(info -- Watcher for unit tests ----------------)
	$(CLOJURE_EXEC_TEST_RUNNER) :fail-fast? false :watch? true
# -------------------------------------- #

# -- Build tasks ----------------------- #
build-config: ## Pretty print build configuration
	$(info -- View current build config -------------)
	clojure -T:build/task config

build-uberjar: ## Build a uberjar archive of Clojure project & Clojure runtime
	$(info -- Build service Uberjar (version: $(VERSION)) --)
	clojure -J-Do11ylite.version=$(VERSION) -T:build/task uberjar

build-clean: ## Clean build assets or given directory
	$(info -- Clean Build  --------------------------)
	clojure -T:build/task clean
# -------------------------------------- #

# -- Protobuf Generation --------------- #
# VENDORED: protoc and grpc-java plugin (downloaded automatically to .bin/)
# Version must match protobuf-java dependency in deps.edn

PROTO_SRC_DIR := proto
JAVA_OUT_DIR := java-src
JAVA_CLASSES_DIR := classes
PROTO_FILES := $(shell find $(PROTO_SRC_DIR) -name '*.proto')

# OpenTelemetry proto version
OTEL_PROTO_VERSION := 1.9.0
OTEL_PROTO_MODULES := common logs metrics resource trace
OTEL_PROTO_BASE_URL := https://raw.githubusercontent.com/open-telemetry/opentelemetry-proto/v$(OTEL_PROTO_VERSION)

# Platform detection for protoc and grpc-java
UNAME_S := $(shell uname -s)
UNAME_M := $(shell uname -m)
ifeq ($(UNAME_S),Darwin)
	ifeq ($(UNAME_M),arm64)
		PROTOC_PLATFORM := osx-aarch_64
		GRPC_JAVA_PLATFORM := osx-aarch_64
	else
		PROTOC_PLATFORM := osx-x86_64
		GRPC_JAVA_PLATFORM := osx-x86_64
	endif
else ifeq ($(UNAME_M),aarch64)
	PROTOC_PLATFORM := linux-aarch_64
	GRPC_JAVA_PLATFORM := linux-aarch_64
else
	PROTOC_PLATFORM := linux-x86_64
	GRPC_JAVA_PLATFORM := linux-x86_64
endif

# Protoc version - must match protobuf-java version in deps.edn
# protobuf-java 4.33.1 requires protoc 33.1
PROTOC_VERSION := 33.1
PROTOC_BIN := .bin/protoc

# grpc-java plugin
GRPC_JAVA_VERSION := 1.72.0
GRPC_JAVA_PLUGIN := .bin/protoc-gen-grpc-java

# Download protoc compiler
$(PROTOC_BIN):
	$(info -- Downloading protoc v$(PROTOC_VERSION) ---)
	@mkdir -p .bin
	@curl -sSL -o /tmp/protoc-$(PROTOC_VERSION).zip \
		"https://github.com/protocolbuffers/protobuf/releases/download/v$(PROTOC_VERSION)/protoc-$(PROTOC_VERSION)-$(PROTOC_PLATFORM).zip"
	@unzip -q -o /tmp/protoc-$(PROTOC_VERSION).zip -d /tmp/protoc-$(PROTOC_VERSION)
	@cp /tmp/protoc-$(PROTOC_VERSION)/bin/protoc $(PROTOC_BIN)
	@chmod +x $(PROTOC_BIN)
	@rm -rf /tmp/protoc-$(PROTOC_VERSION) /tmp/protoc-$(PROTOC_VERSION).zip

# Download grpc-java protoc plugin
# Note: Maven artifact uses .exe extension for all platforms (it's a native binary, not Windows-specific)
$(GRPC_JAVA_PLUGIN):
	$(info -- Downloading protoc-gen-grpc-java -------)
	@mkdir -p .bin
	curl -sSL -o $(GRPC_JAVA_PLUGIN) \
		"https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/$(GRPC_JAVA_VERSION)/protoc-gen-grpc-java-$(GRPC_JAVA_VERSION)-$(GRPC_JAVA_PLATFORM).exe"
	chmod +x $(GRPC_JAVA_PLUGIN)

proto-clean: ## Clean generated protobuf Java files
	$(info -- Clean generated protobuf files ---------)
	rm -rf $(JAVA_OUT_DIR)/* $(JAVA_CLASSES_DIR)/*

proto-gen: $(PROTOC_BIN) $(GRPC_JAVA_PLUGIN) ## Generate Java classes from .proto files
	$(info -- Generate Java from protobuf ------------)
	@mkdir -p $(JAVA_OUT_DIR)
	$(PROTOC_BIN) \
		--proto_path=$(PROTO_SRC_DIR) \
		--java_out=$(JAVA_OUT_DIR) \
		--grpc-java_out=$(JAVA_OUT_DIR) \
		--plugin=protoc-gen-grpc-java=$(GRPC_JAVA_PLUGIN) \
		$(PROTO_FILES)
	$(info -- Generated files in $(JAVA_OUT_DIR) -----)

proto-compile: proto-gen ## Compile generated Java files to classes
	$(info -- Compiling Java protobuf classes --------)
	@mkdir -p $(JAVA_CLASSES_DIR)
	javac -d $(JAVA_CLASSES_DIR) \
		-cp "$$(clojure -Spath)" \
		$$(find $(JAVA_OUT_DIR) -name '*.java')
	$(info -- Compiled classes in $(JAVA_CLASSES_DIR) -)

proto-rebuild: proto-clean proto-compile ## Clean and regenerate protobuf files

# Download OpenTelemetry proto definitions
otel-proto-download: ## Download OpenTelemetry proto files (v$(OTEL_PROTO_VERSION))
	$(info -- Downloading OpenTelemetry protos v$(OTEL_PROTO_VERSION) --)
	@mkdir -p $(PROTO_SRC_DIR)/opentelemetry/proto
	@# Download data model protos (common, resource, logs, metrics, trace)
	@for module in $(OTEL_PROTO_MODULES); do \
		mkdir -p $(PROTO_SRC_DIR)/opentelemetry/proto/$$module/v1; \
		echo "  Downloading $$module/v1/$$module.proto"; \
		curl -sSL -o $(PROTO_SRC_DIR)/opentelemetry/proto/$$module/v1/$$module.proto \
			"$(OTEL_PROTO_BASE_URL)/opentelemetry/proto/$$module/v1/$$module.proto"; \
	done
	@# Download collector service protos (OTLP endpoints)
	@for module in logs metrics trace; do \
		mkdir -p $(PROTO_SRC_DIR)/opentelemetry/proto/collector/$$module/v1; \
		echo "  Downloading collector/$$module/v1/$${module}_service.proto"; \
		curl -sSL -o $(PROTO_SRC_DIR)/opentelemetry/proto/collector/$$module/v1/$${module}_service.proto \
			"$(OTEL_PROTO_BASE_URL)/opentelemetry/proto/collector/$$module/v1/$${module}_service.proto"; \
	done
	$(info -- OpenTelemetry protos downloaded ----------)

otel-proto-clean: ## Remove downloaded OpenTelemetry proto files
	$(info -- Removing OpenTelemetry protos -----------)
	rm -rf $(PROTO_SRC_DIR)/opentelemetry
# -------------------------------------- #

# -- Code Quality ---------------------- #
CLJSTYLE_VERSION := 0.17.642
CLJSTYLE_INSTALL_DIR := .tmp
CLJSTYLE_BIN := $(CLJSTYLE_INSTALL_DIR)/bin/cljstyle

cljstyle-install: ## Install cljstyle to .tmp/
	$(info -- Installing cljstyle to $(CLJSTYLE_INSTALL_DIR) ---)
	@if [ ! -f "$(CLJSTYLE_BIN)" ]; then \
		mkdir -p $(CLJSTYLE_INSTALL_DIR)/bin && \
		curl -sL https://raw.githubusercontent.com/greglook/cljstyle/main/util/install-cljstyle -o /tmp/install-cljstyle && \
		chmod +x /tmp/install-cljstyle && \
		/tmp/install-cljstyle --dir $(CLJSTYLE_INSTALL_DIR)/bin --version $(CLJSTYLE_VERSION); \
	else \
		echo "cljstyle already installed at $(CLJSTYLE_BIN)"; \
	fi

pre-commit-check: format-check test  ## Run format and test targets

format-check: cljstyle-install ## Run cljstyle to check the formatting of Clojure code
	$(info -- cljstyle Runner show errors -----------)
	$(CLJSTYLE_BIN) check

format-fix: cljstyle-install ## Run cljstyle and fix the formatting of Clojure code
	$(info -- cljstyle Runner fix errors ------------)
	$(CLJSTYLE_BIN) fix
# -------------------------------------- #

# -- Dependency Management -------------
ANTQ_VERSION := 2.11.1276

deps-antq-install: ## Install antq dependency checker tool
	$(info -- Installing antq dependency checker ---)
	@clojure -Ttools list 2>/dev/null | grep -q "antq" || \
		clojure -Ttools install com.github.liquidz/antq '{:git/tag "$(ANTQ_VERSION)"}' :as antq

deps-outdated: deps-antq-install ## Check for outdated dependencies
	$(info -- Checking for outdated dependencies ---)
	@clojure -Tantq outdated

deps-upgrade: deps-antq-install ## Upgrade all outdated dependencies
	$(info -- Upgrading outdated dependencies ---)
	@clojure -Tantq upgrade
# -------------------------------------- #

# -- Continuous Integration ------------ #
# .DELETE_ON_ERROR: halts if command returns non-zero exit status
# https://makefiletutorial.com/#delete_on_error

# Run tests, build & package the Clojure code and clean up afterward
# `make all` used in Docker builder stage
.DELETE_ON_ERROR:
all: test-ci dist clean  ## Call test-ci dist and clean targets, used for CI
# -------------------------------------- #

# -- Help ------------------------------ #
# Source: https://nedbatchelder.com/blog/201804/makefile_help_target.html

help:  ## Describe available tasks in Makefile
	@grep '^[a-zA-Z]' $(MAKEFILE_LIST) | \
	sort | \
	awk -F ':.*?## ' 'NF==2 {printf "\033[36m  %-$(HELP-DESCRIPTION-SPACING)s\033[0m %s\n", $$1, $$2}'
# -------------------------------------- #
