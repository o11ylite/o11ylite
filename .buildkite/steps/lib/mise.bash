# Shared toolchain bootstrap. Source this, then call ensure_tools before using
# any tool that mise.toml pins.
#
# The steps that need a toolchain run jdxcode/mise:*-debian, which already
# carries mise, git, and curl. The image tag names the mise version; mise.toml
# names every tool version.

# Put tools from mise.toml on PATH. Name the tools the step uses, spelled the
# way mise.toml pins them (`ensure_tools helm`, `ensure_tools github:cli/cli`,
# backend prefix and all); with no arguments every pinned tool is installed.
# Naming them keeps chart linting from downloading a JDK. A name mise.toml does
# not pin would install whatever the registry's latest is instead of our pin.
ensure_tools() {
  if ! command -v mise >/dev/null 2>&1; then
    echo "mise is not on PATH; run this step in a jdxcode/mise image" >&2
    return 1
  fi

  local root what
  root="$(git rev-parse --show-toplevel)"
  what="${*:-all tools}"

  # Installing the toolchain takes seconds, and the k8s queue these steps run
  # on has no cache store configured, so there is nothing to restore: a
  # `buildkite-agent cache` call there only warns.
  echo "--- :toolbox: Installing ${what} from mise.toml"
  mise trust "${root}/mise.toml"
  mise install --cd "$root" "$@"

  # Shims, not the interactive shell hook: a step script never runs the prompt
  # hook that `mise activate` relies on, and the Makefile spawns child
  # processes that need real binaries on PATH.
  eval "$(mise activate bash --shims)"
}
