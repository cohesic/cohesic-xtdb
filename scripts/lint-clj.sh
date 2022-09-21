#!/usr/bin/env bash
#
# Check the appserver code for issues - featuring https://github.com/clj-kondo/clj-kondo
#
# If you do not pass any action, the scripts just treats the parameters as files.
#
# This is handy for files belonging to the last commit:
#   git diff --relative --name-only  HEAD~1...HEAD | xargs ./scripts/lint-clj

set -u

# The following replaces "readlink -f" behavior for macOS
# see https://stackoverflow.com/a/22971167/1888507

_canonicalize_dir_path() {
    (cd "$1" 2>/dev/null && pwd -P)
}

_canonicalize_file_path() {
    local dir file
    dir=$(dirname -- "$1")
    file=$(basename -- "$1")
    (cd "$dir" 2>/dev/null && printf '%s/%s\n' "$(pwd -P)" "$file")
}

canonicalize_path() {
    if [ -d "$1" ]; then
        _canonicalize_dir_path "$1"
    else
        _canonicalize_file_path "$1"
    fi
}

action="${1:-}"

script_path=$(canonicalize_path "${BASH_SOURCE[0]}")
script_dir=$(dirname $script_path)
root_dir="$(dirname $(dirname $script_path))"
subproject_name=appserver

clj_grep_regex='\.(clj|cljs|cljx|cljc)$'

common_opts="--parallel --fail-level error"

function git_cached_files() {
    echo $(git diff --relative --cached --no-renames --name-status -- $root_dir \
               | awk '$1 != "D" { print $2 }' \
               | grep -E "$clj_grep_regex"
        )
}

if [[ ! "$action" =~ old|reset|check|check-cached|check-staged|check-itest ]]; then
    action=check-files
fi

paths=
case "$action" in
  check)
      paths="src test"
      ;;
  check-itest)
      paths="src itest"
      ;;
  reset)
      # reset needs the full classpath for populating the cache
      paths="$(clojure -A:dev:domain:test:itest -Spath)"
      ;;
  check-cached|check-staged)
      paths="$(git_cached_files)"
      ;;
  check-files)
      paths="$*"
esac

case "$action" in
  reset)
    echo -e "Resetting linter cache"
    ;;
  check|check-files|check-cached|check-staged)
    echo -e "Checking: $paths"
    ;;
esac

case "$action" in
  old)
    clj-kondo $common_opts --lint $files --config '{:output {:include-files ["'${subproject_name}'\\/"]}}'
    ;;
  reset)
    rm -rf "$root_dir/.clj-kondo/.cache"
    clj-kondo $common_opts --copy-configs --dependencies --lint $paths
    ;;
  check*)
    # Why does it have to be this complicated
    # https://stackoverflow.com/a/638835/1888507
    files_count=$(echo "$paths" | wc -w)
    if [ $files_count -ge 1 ]; then
        clj-kondo $common_opts --lint $paths --fail-level error --config '{:output {:canonical-paths true}}'
    else
        echo "No files in input. Nothing to do here."
        exit 0
    fi
    ;;
  *)
    echo "The $action parameter is not supported at this time"
    ;;
esac
