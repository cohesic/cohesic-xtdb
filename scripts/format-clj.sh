#!/usr/bin/env bash
#
# Fix/check the formatting Clojure code.
#
# The cached/staged variants - which are synonyms in git - apply the changes only on git add-ed files.
#
# If you do not pass any action, the scripts just treats the parameters as files.
#
# This is handy for files belonging to the last commit:
#   git diff --relative --name-only  HEAD~1...HEAD | xargs ./scripts/format

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
script_name="${BASH_SOURCE[0]}"
script_path=$(canonicalize_path "${BASH_SOURCE[0]}")
script_dir=$(dirname $script_path)
appserver_dir="$(dirname $(dirname $script_path))"


if [[ ! "$action" =~ fix|fix-cached|fix-staged|check|check-cached|check-staged ]]; then
    action=fix-files
fi

{
  cd ${appserver_dir}
} &> /dev/null

clj_grep_regex='\.(clj|cljs|cljx|cljc|edn|bb)$'

# These have to be basic regexes in order to be compatible with MacOS
clj_find_regex='.*\.\(clj[csx]?\|edn\|bb\)$'
exclude_find_regex='.*\/\(target\|classes\)\/.*'

function git_cached_files() {
    echo $(git diff --relative --cached --no-renames --name-status -- $appserver_dir \
               | awk '$1 != "D" { print $2 }' \
               | grep -E $clj_grep_regex
        )
}

function git_uncached_files() {
    echo $(git diff --relative --no-renames --name-status -- $appserver_dir \
               | awk '$1 != "D" { print $2 }' \
               | grep -E $clj_grep_regex
        )
}

function find_files() {
    find $appserver_dir -type f \
         -not -regex "$exclude_find_regex" \
         -and \
         -regex "$clj_find_regex"
}

files=
zprint_opts=("{:search-config? true}")

case "$action" in
  check|fix)
      files=$(find_files)
      set +x
      ;;
  fix-cached|check-cached|fix-staged|check-staged)
      files=$(git_cached_files)
      ;;
  fix-uncached|check-uncached|fix-unstaged|check-unstaged)
    files=$(git_uncached_files)
    ;;
  fix-files)
      files="$*"
esac

case "$action" in
  fix*)
      zprint_opts+=('-sfw')
      ;;
  check*)
      zprint_opts+=('-sfc')
      ;;
esac

# Getting rid of non-Clojure files
files=$(echo $files | grep -E $clj_grep_regex)

# Why does it have to be this complicated
# https://stackoverflow.com/a/638835/1888507
files_count=$(echo "$files" | wc -w)
if [ $files_count -ge 1 ]; then
    zprint "${zprint_opts[@]}" $files
    result=$?
else
    echo "No Clojure(Script) files to check."
    exit 0
fi

{
  cd -
} &> /dev/null

if [ "$result" != 0 ]; then
  if [ "$action" == "check" ]; then
    echo "You can fix them with \`$script_name fix\` or \`$script_name fix-cached\`."
  else
    echo "There was an error formatting the given files."
  fi
  exit 1
fi
