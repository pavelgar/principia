#!/usr/bin/env bash
# SessionStart hook shared by Claude Code (.claude/settings.json) and Codex (.codex/hooks.json). Runs in every
# session, including worktrees and cloud sessions, and in Claude Code also as the SubagentStart hook: SessionStart doesn't
# fire for subagents, so a subagent's worktree (isolation: worktree) would otherwise have no .references/. It must stay
# fast and never fail; stdout is added to the agent's context.
#
#  - gives a new worktree the .references/ reference tree (copied from the main checkout; built from scratch in the cloud)
#  - warns when .references/ was built for other Minecraft or Fabric API versions than gradle/libs.versions.toml
input=$(cat 2>/dev/null || true)
cwd=$(jq -r '.cwd // empty' <<<"$input" 2>/dev/null || true)
cd "${cwd:-$(dirname "$0")/..}" 2>/dev/null || exit 0
cd "$(git rev-parse --show-toplevel 2>/dev/null)" 2>/dev/null || exit 0

mc=$(sed -n 's/^minecraft *= *"\(.*\)"/\1/p' gradle/libs.versions.toml 2>/dev/null)

if [ ! -f .references/VERSIONS ]; then
	main=$(git worktree list --porcelain 2>/dev/null | awk 'NR==1 {print $2}')
	if [ -n "$main" ] && [ "$main" != "$PWD" ] && grep -qx "minecraft=$mc" "$main/.references/VERSIONS" 2>/dev/null; then
		# --reflink is GNU cp's (macOS has none): fall back to a plain copy.
		{ cp -a --reflink=auto "$main/.references" .references 2>/dev/null || cp -a "$main/.references" .references 2>/dev/null; } \
			&& echo "Copied the .references/ reference tree from the main checkout."
	elif [ "${CLAUDE_CODE_REMOTE:-}" = "true" ]; then
		scripts/setup.sh > /tmp/setup.log 2>&1 \
			&& echo "Built the .references/ reference tree." \
			|| echo "scripts/setup.sh failed (log: /tmp/setup.log); run it before looking up APIs."
	else
		echo ".references/ reference tree is missing: run scripts/setup.sh before looking up Minecraft/Fabric APIs."
	fi
fi

# A tree built for other versions (after a version bump, or copied from an older checkout) answers API lookups with
# the wrong sources, which is the trap AGENTS.md opens with.
if [ -f .references/VERSIONS ]; then
	fabric_api=$(sed -n 's/^fabric-api *= *"\(.*\)"/\1/p' gradle/libs.versions.toml 2>/dev/null)
	if ! grep -qx "minecraft=$mc" .references/VERSIONS || ! grep -qx "fabric-api=$fabric_api" .references/VERSIONS; then
		echo ".references/ was built for other versions than gradle/libs.versions.toml (Minecraft $mc, Fabric API $fabric_api; see .references/VERSIONS): its sources are stale. Run scripts/setup.sh before looking up Minecraft/Fabric APIs."
	fi
fi
exit 0
