#!/usr/bin/env bash
# PreToolUse hook for shell commands, shared by Claude Code (.claude/settings.json) and Codex (.codex/hooks.json).
# Both tools pass the command as JSON on stdin (.tool_input.command). Exit code 2 blocks the command, and stderr
# is shown to the agent.
#
# It stops the few slips nothing else catches, with a message saying what to do instead: stopping or killing Gradle
# and Java, which takes down every worktree's builds and game tests, and calling xvfb-run directly. It matches the
# command's text, so a command spelled to get around it gets around it, and a commit message naming these commands
# is blocked too (write the message to a file and use `git commit -F`). Everything else holds without it: releases
# need GitHub's release environment (header of .github/workflows/release.yml), live publishing needs that
# environment's tokens, and runClient, runServer and scripts/play.sh refuse to start under an agent
# (agentVariable in build.gradle.kts). Run scripts/test-agent-guard.sh after changing it.
input=$(< /dev/stdin)
if command -v jq > /dev/null; then
	cmd=$(jq -r '.tool_input.command // empty' <<< "$input" 2> /dev/null)
elif command -v python3 > /dev/null; then
	cmd=$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("tool_input", {}).get("command") or "")' <<< "$input" 2> /dev/null)
else
	echo "Blocked by scripts/agent-guard.sh: it needs jq (or python3) to read the command; ask the user to install jq." >&2
	exit 2
fi

block() { echo "Blocked by scripts/agent-guard.sh: $1" >&2; exit 2; }
kills="killing Gradle or Java processes stops other worktrees' builds and game tests too; leave them running."

# The patterns, kept in variables: bash reads ;, & and | in an unquoted [[ =~ ]] as its own syntax.
stop='gradlew[^;&|]*[[:space:]]--stop([[:space:]]|$)'
pkill='(pkill|killall)[^;&|]*(gradle|java)'
kill='(^|[^[:alnum:]_-])kill([[:space:]]|$)'
lookup='(pgrep|pidof)[^;&|]*(gradle|java)|jps'
xvfb='(^|[;&|(])[[:space:]]*(env[^;&|]*[[:space:]])?xvfb-run([[:space:]]|$)'
shopt -s nocasematch
[[ $cmd =~ $stop ]] \
	&& block "\`gradlew --stop\` stops the Gradle daemons of every worktree, including other agents' builds; just leave the daemon running."
[[ $cmd =~ $pkill ]] && block "$kills"
[[ $cmd =~ $kill && $cmd =~ $lookup ]] && block "$kills"
[[ $cmd =~ $xvfb ]] \
	&& block "don't call xvfb-run directly (its 8-bit display breaks OpenGL); use scripts/client-gametest.sh, which sets a 24-bit display and keeps SDL off Wayland."
exit 0
