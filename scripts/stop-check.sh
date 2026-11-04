#!/usr/bin/env bash
# Stop hook for Claude Code (.claude/settings.json) and Codex (.codex/hooks.json): when an agent ends a turn that
# changed code, runs `./gradlew quickCheck` (format, compile every source set with Error Prone and NullAway, JUnit
# tests; a few seconds) and, if it fails, sends the agent back to work with the first errors. Exit code 2 with the
# reason on stderr means "keep going" to both tools; exit 0 must print nothing on stdout (Codex reads it as JSON).
#
# It does nothing when no build input differs from HEAD, or when they are exactly as at the last passing run, so
# chat-only turns cost nothing. If the agent stops again without changing anything after a failure, it is let go:
# it has given up, and blocking again would only repeat the same message.
cd "$(git rev-parse --show-toplevel 2>/dev/null || dirname "$0")" || exit 0
input=$(cat)
active=$(jq -r '.stop_hook_active // false' <<< "$input" 2>/dev/null || echo false)

inputs=(src build.gradle.kts settings.gradle.kts gradle.properties gradle)
state=build/stop-check
mkdir -p "$state"

# A fingerprint of the build inputs: tracked changes against HEAD, plus untracked files' names and contents.
fingerprint() {
	{
		git rev-parse HEAD
		git diff HEAD -- "${inputs[@]}"
		git ls-files --others --exclude-standard -z -- "${inputs[@]}" | xargs -0r sha1sum
	} 2>/dev/null | sha1sum | cut -d' ' -f1
}

if git diff --quiet HEAD -- "${inputs[@]}" 2>/dev/null \
	&& [ -z "$(git ls-files --others --exclude-standard -- "${inputs[@]}" 2>/dev/null)" ]; then
	exit 0 # nothing changed since the last commit, which CI checks
fi
now=$(fingerprint)
[ "$now" = "$(cat "$state/passed" 2>/dev/null)" ] && exit 0
if [ "$active" = true ] && [ "$now" = "$(cat "$state/failed" 2>/dev/null)" ]; then
	exit 0
fi

log=$state/quickCheck.log
if ./gradlew quickCheck --console=plain > "$log" 2>&1; then
	fingerprint > "$state/passed" # after the run: spotlessApply may have reformatted files
	exit 0
fi
echo "$now" > "$state/failed"

. scripts/lib/failures.sh
{
	echo "./gradlew quickCheck failed on the changes in this turn; fix this before finishing. Full output: $log"
	gradle_failures "$log"
} >&2
exit 2
