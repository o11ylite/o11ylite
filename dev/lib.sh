# -------------------------------------- #
# Helpers shared by the scripts in dev/
#
# Source this; do not run it.
# -------------------------------------- #

# Point $1, a hostname, at loopback in the system hosts file. Callers pass
# O11YLITE_DEV_HOSTNAME so the hostname follows what Caddy and Vite are
# configured with in dev/Caddyfile.
#
# Needs root. sudo is used when not already root and non-interactive sudo is
# available; otherwise this explains what to run and gives up, so a restricted
# environment gets as far as it can.
#
# Returns 0 when the line was added and 1 when it was already there, so callers
# can stay quiet on a re-run.
add_dev_hostname() {
  local hostname="$1"

  if grep -qE "^[^#]*[[:space:]]${hostname}([[:space:]]|$)" /etc/hosts 2>/dev/null; then
    return 1
  fi

  if [[ "$(id -u)" -ne 0 ]] && ! sudo -n true 2>/dev/null; then
    echo "Cannot edit /etc/hosts without a password. Add this line by hand:"
    echo "  127.0.0.1 ${hostname}"
    return 0
  fi

  if [[ "$(id -u)" -eq 0 ]]; then
    echo "127.0.0.1 ${hostname}" | tee -a /etc/hosts >/dev/null
  else
    echo "127.0.0.1 ${hostname}" | sudo -n tee -a /etc/hosts >/dev/null
  fi
}
