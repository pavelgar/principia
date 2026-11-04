#!/usr/bin/env bash
# The definition of done: runs what CI (.github/workflows/build.yml) runs on a PR, on this checkout as it is, and ends
# with one line, `RESULT: PASS`, `RESULT: FAIL <steps>` or `RESULT: INCOMPLETE <steps>`, and the same as JSON in
# build/verify/verify.json. Each step's full output is in build/verify/<step>.log; on failure the first errors are
# printed. Exit code: 0 PASS, 1 FAIL, 3 INCOMPLETE.
#
#   scripts/verify.sh                   client game tests only if the change reaches them, as in CI
#   scripts/verify.sh --client          client game tests whatever changed
#   scripts/verify.sh --no-client       without them: INCOMPLETE at best
#   scripts/verify.sh --base=<ref>      what the change is measured from (default: where HEAD left origin/<mc>/dev)
#
# Steps, in CI's order: the repo's self-checks (skills, agent guard), `./gradlew build` (compile with Error Prone and
# NullAway, JUnit, fuzz replay, server game tests and their log check, formatting, jar), datagen output up to date,
# and the client game tests against the built jar (`scripts/client-gametest.sh --production`).
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
. scripts/lib/failures.sh

client=auto base='' fork=''
for arg in "$@"; do
	case $arg in
		--client) client=always ;;
		--no-client) client=never ;;
		--base=?*) base=${arg#--base=} ;;
		*) echo "unknown option: $arg (see the header of $0)" >&2 && exit 2 ;;
	esac
done

out=build/verify
rm -rf "$out" && mkdir -p "$out"
mc=$(sed -n 's/^minecraft *= *"\(.*\)"/\1/p' gradle/libs.versions.toml)

# What the change is: every file that differs from the base, committed or not, and new files.
if [ -z "$base" ]; then
	for ref in "origin/$mc/dev" "$mc/dev"; do
		if git rev-parse -q --verify "$ref^{commit}" > /dev/null; then
			base=$ref
			break
		fi
	done
fi
if [ -n "$base" ] && fork=$(git merge-base HEAD "$base" 2> /dev/null); then
	changed=$( { git diff --name-only "$fork"; git ls-files --others --exclude-standard; } | sort -u)
	base_desc="$base ($(git rev-parse --short "$fork"))"
else
	changed='' base_desc="none found"
fi
echo "verify: base $base_desc, $(grep -c . <<< "$changed") changed files"

# The client game tests run when the change reaches what they exercise: the paths in scripts/lib/client-test-paths,
# which build.yml's decision reads too.
client_paths=scripts/lib/client-test-paths
if [ "$client" = auto ]; then
	if [ -z "$fork" ] || grep -qEf "$client_paths" <<< "$changed"; then client=always; else client=no-change; fi
fi

names=() statuses=() times=() notes=()
record() { # record <step> <PASS|FAIL|SKIP> <seconds> <note>
	names+=("$1") statuses+=("$2") times+=("$3") notes+=("$4")
	printf '%-5s %-12s %5ss  %s\n' "$2" "$1" "$3" "$4"
}
elapsed() { echo $(($(date +%s) - $1)); }
failed=()
details=

# 1. The repo's self-checks.
start=$(date +%s)
if { scripts/check-skills.sh && scripts/test-agent-guard.sh; } > "$out/self-checks.log" 2>&1; then
	record self-checks PASS "$(elapsed "$start")" ""
else
	record self-checks FAIL "$(elapsed "$start")" "$out/self-checks.log"
	failed+=(self-checks)
	details+=$'\n'"self-checks:"$'\n'"$(grep -E 'FAIL|missing|not a symlink|error' "$out/self-checks.log" | head -n 20)"$'\n'
fi

# 2. The build, with --continue so that one run shows every independent failure.
start=$(date +%s)
if ./gradlew build --continue --console=plain > "$out/build.log" 2>&1; then
	record build PASS "$(elapsed "$start")" ""
	built=1
else
	record build FAIL "$(elapsed "$start")" "$out/build.log"
	failed+=(build)
	details+=$'\n'"build:"$'\n'"$(gradle_failures "$out/build.log")"$'\n'
	built=0
fi
# Datagen needs only the code to compile; the client tests need the whole build.
compiled=1
grep -qE '^> Task :compile[A-Za-z]*Java FAILED' "$out/build.log" && compiled=0

# 3. Datagen output up to date: running the providers changes nothing under src/main/generated. If it does, the
# files are now updated in place, to review and commit.
generated() { find src/main/generated -type f -print0 | sort -z | xargs -0 sha1sum; }
start=$(date +%s)
if ((compiled)); then
	generated > "$out/generated.before"
	# Without its cache (gitignored, so CI never has one), datagen writes every file: with it, a file whose output
	# didn't change isn't rewritten, so an edit made to it by hand would survive.
	rm -rf src/main/generated/.cache
	if ./gradlew runDatagen --console=plain > "$out/datagen.log" 2>&1; then
		generated > "$out/generated.after"
		if cmp -s "$out/generated.before" "$out/generated.after"; then
			record datagen PASS "$(elapsed "$start")" ""
		else
			record datagen FAIL "$(elapsed "$start")" "src/main/generated was stale; runDatagen has now updated it"
			failed+=(datagen)
			# The files only before (removed or changed) or only after (added or changed): 40 hex digits, 2 spaces.
			stale=$(comm -3 <(sort "$out/generated.before") <(sort "$out/generated.after") | sed 's/^\t//' | cut -c43- | sort -u | head -n 20 | sed "s|^|  |")
			details+=$'\n'"datagen: a provider changed but src/main/generated wasn't regenerated. runDatagen just did; review and commit these:"$'\n'"$stale"$'\n'
		fi
	else
		record datagen FAIL "$(elapsed "$start")" "$out/datagen.log"
		failed+=(datagen)
		details+=$'\n'"datagen (a registered block or item without a loot table, blockstate or model fails it):"$'\n'"$(gradle_failures "$out/datagen.log")"$'\n'
	fi
else
	record datagen SKIP 0 "the code doesn't compile"
fi

# 4. The client game tests against the built jar, as CI runs them.
evidence=
start=$(date +%s)
if [ "$client" = never ]; then
	record client SKIP 0 "--no-client"
	skipped=1
elif [ "$client" = no-change ]; then
	record client PASS 0 "not needed: nothing they exercise changed (as in CI)"
elif ((!built)); then
	record client SKIP 0 "the build failed"
elif ! command -v xvfb-run > /dev/null && [ -z "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
	record client SKIP 0 "no display and no xvfb-run: install Xvfb (README, Development)"
	skipped=1
else
	status=0
	scripts/client-gametest.sh --production > "$out/client.log" 2>&1 || status=$?
	evidence=$(sed -n 's/^Evidence: \([^ ]*\).*/\1/p' "$out/client.log" | tail -n 1)
	if ((status == 0)); then
		record client PASS "$(elapsed "$start")" "evidence: $evidence"
	else
		record client FAIL "$(elapsed "$start")" "$out/client.log${evidence:+, evidence: $evidence}"
		failed+=(client)
		failures=$(grep -E 'AssertionError|Exception:|Caused by:' "$out/client.log" | head -n 12)
		details+=$'\n'"client game tests (one class: scripts/client-gametest.sh --only=<Class>):"$'\n'"${failures:-$(gradle_failures "$out/client.log")}"$'\n'
	fi
fi

if ((${#failed[@]})); then
	result=FAIL
	printf '%s' "$details"
elif ((${skipped:-0})); then
	result=INCOMPLETE
else
	result=PASS
fi
uncommitted=$(git status --porcelain --untracked-files=normal | grep -c .)
((uncommitted)) && echo "Note: $uncommitted uncommitted or untracked paths; CI checks only what is committed and pushed."

steps=$(for i in "${!names[@]}"; do
	jq -n --arg name "${names[$i]}" --arg status "${statuses[$i]}" --argjson seconds "${times[$i]}" --arg note "${notes[$i]}" \
		'{name: $name, status: $status, seconds: $seconds, note: $note}'
done | jq -s .)
jq -n --arg result "$result" --arg base "$base_desc" --argjson steps "$steps" --arg evidence "$evidence" \
	--argjson uncommitted "$uncommitted" --arg failed "${failed[*]}" \
	'{result: $result, failed: ($failed | split(" ") | map(select(. != ""))), base: $base, steps: $steps,
	  evidence: (if $evidence == "" then null else $evidence end), uncommitted: $uncommitted}' > "$out/verify.json"

case $result in
	PASS) echo "RESULT: PASS" && exit 0 ;;
	FAIL) echo "RESULT: FAIL ${failed[*]}" && exit 1 ;;
	*) echo "RESULT: INCOMPLETE client (skipped)" && exit 3 ;;
esac
