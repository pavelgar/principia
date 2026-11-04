#!/usr/bin/env bash
# Starts a Claude Code cloud session on this repo from the terminal:
#   scripts/cloud-task.sh "<what to do>"
#
# Works around anthropics/claude-code#81776: `claude --cloud` can wrongly conclude the Claude GitHub App isn't
# installed and silently upload a local bundle instead of cloning (the session then has no remote and can't push).
# Disabling non-essential traffic turns off the server-side switch behind that fallback. Drop this once #81776 is fixed.
#
# The session starts from the current branch as it exists on GitHub, so push local commits first.
set -euo pipefail
cd "$(dirname "$0")/.."

[ $# -ge 1 ] || { echo "usage: scripts/cloud-task.sh \"<task>\"" >&2; exit 1; }
branch=$(git branch --show-current)
if ! git ls-remote --exit-code origin "refs/heads/$branch" >/dev/null 2>&1; then
	echo "Branch '$branch' isn't on GitHub yet. Push it first (git push -u origin HEAD:<mc_version>/<name>)." >&2
	exit 1
fi
[ -z "$(git log "origin/$branch..HEAD" --oneline 2>/dev/null)" ] \
	|| echo "Note: local commits on '$branch' aren't pushed; the cloud session won't see them." >&2

CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 exec claude --cloud "$1" --ref "$branch" "${@:2}"
