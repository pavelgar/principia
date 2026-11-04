#!/usr/bin/env bash
# Checks that Claude Code and Codex see the same skills: each .agents/skills/<name> has a .claude/skills/<name>
# symlink to ../../.agents/skills/<name>, and .claude/skills/ holds nothing else. Runs in CI.
set -euo pipefail
cd "$(dirname "$0")/.."
shopt -s nullglob

problems=0
fail() { echo "check-skills: $1" >&2; problems=$((problems + 1)); }

for dir in .agents/skills/*/; do
	name=$(basename "$dir")
	[ -L ".claude/skills/$name" ] || fail ".agents/skills/$name has no .claude/skills/$name symlink (ln -s ../../.agents/skills/$name .claude/skills/$name)"
done

for link in .claude/skills/*; do
	name=${link##*/}
	[ -L "$link" ] && [ "$(readlink "$link")" = "../../.agents/skills/$name" ] && [ -d "$link" ] \
		|| fail "$link should be a symlink to ../../.agents/skills/$name"
done

[ $problems -eq 0 ] || exit 1
echo "check-skills: ok"
