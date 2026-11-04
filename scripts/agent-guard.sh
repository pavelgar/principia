#!/usr/bin/env bash
# PreToolUse hook for shell commands, shared by Claude Code (.claude/settings.json) and Codex (.codex/hooks.json).
# Both tools pass the command as JSON on stdin (.tool_input.command). Exit code 2 blocks the command, and stderr
# is shown to the agent. It blocks live publishing (as a Gradle argument or ORG_GRADLE_PROJECT_ variable, not as a mention), and stops commands that hang or hit other worktrees:
# `gradlew runClient/runServer`, `gradlew --stop`, direct `xvfb-run`, `scripts/play.sh`, and genSources combined with
# other tasks.
# Patterns target actual use, so searching for, documenting, or *reading* these settings stays possible.
# Run scripts/test-agent-guard.sh after changing it.
input=$(cat)
cmd=$(jq -r '.tool_input.command // empty' <<< "$input" 2>/dev/null) || exit 0
[ -n "$cmd" ] || exit 0

block() { echo "Blocked by scripts/agent-guard.sh: $1" >&2; exit 2; }

# The start of a command in command position: after a separator or newline, past wrappers and VAR=value
# assignments, so `grep xvfb-run scripts` or `which xvfb-run` don't count as running it.
nl=$'\n'
cmd_start='(^|[;&|(`'"$nl"'])[[:space:]]*((env|sudo|exec|nohup|nice|time|command|timeout[[:space:]]+[0-9.]+[smhd]?)[[:space:]]+|[A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*[[:space:]]+)*'
path='([^[:space:];&|(`]*/)?'   # optional directory before a command name

[[ "$cmd" =~ ${cmd_start}${path}xvfb-run([[:space:]]|$) ]] \
	&& block "don't call xvfb-run directly (its 8-bit display breaks OpenGL); use scripts/client-gametest.sh, which sets a 24-bit display and keeps SDL off Wayland."

[[ "$cmd" =~ ${cmd_start}${path}play\.sh([[:space:]]|$) ]] \
	&& block "scripts/play.sh opens the game for the human to play and never exits; use scripts/client-gametest.sh to see a scene yourself."

[[ "$cmd" =~ ${cmd_start}ORG_GRADLE_PROJECT_publish_live=true ]] \
	&& block "live publishing only happens through the tag-triggered release workflow."

# Gradle: check the tasks and options of every `gradle`/`gradlew` invocation in the command line.
rest=${cmd//$'\\\n'/ }   # join backslash-continued lines
gradle_re="${cmd_start}${path}gradlew?[[:space:]]+([^|;&${nl}]*)"
while [[ "$rest" =~ $gradle_re ]]; do
	rest=${rest#*"${BASH_REMATCH[0]}"}
	read -ra words <<< "${BASH_REMATCH[${#BASH_REMATCH[@]}-1]}"
	tasks=() skip=0
	for w in "${words[@]}"; do
		w=${w//[\"\']/}
		if ((skip)); then skip=0; continue; fi
		case $w in
			-Ppublish_live=true|-Ppublish_live) block "live publishing only happens through the tag-triggered release workflow." ;;
			--stop) block "\`gradlew --stop\` stops the Gradle daemons of every worktree, including other agents' builds; just leave the daemon running." ;;
			# options whose value is the next word, and redirections to a file: that word is not a task
			-x|--exclude-task|-p|--project-dir|-g|--gradle-user-home|-c|--settings-file|-b|--build-file|-I|--init-script|--console|--warning-mode|--include-build|--priority|--project-cache-dir|[0-9]*[\<\>]|[\<\>]|[\<\>][\<\>\&]) skip=1 ;;
			-*|*[\<\>]*) ;;
			*) [[ "$w" =~ ^:?[A-Za-z][A-Za-z0-9:_.-]*$ ]] && tasks+=("${w##*:}") ;;   # a task, minus its project path
		esac
	done
	gen=0 others=0
	for t in "${tasks[@]}"; do
		case $t in
			runClient|runServer) block "\`$t\` is interactive and never exits; use ./gradlew runGameTest or scripts/client-gametest.sh instead." ;;
			gen*Sources*) ((++gen)) ;;
			exportLibrarySources) ;;   # scripts/setup.sh runs it together with genSources
			*) ((++others)) ;;
		esac
	done
	((gen && gen + others > 1)) \
		&& block "Gradle rejects genSources combined with other tasks; run it on its own (scripts/setup.sh already does)."
done

exit 0
