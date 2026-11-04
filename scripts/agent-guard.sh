#!/usr/bin/env bash
# PreToolUse hook for shell commands, shared by Claude Code (.claude/settings.json) and Codex (.codex/hooks.json).
# Both tools pass the command as JSON on stdin (.tool_input.command). Exit code 2 blocks the command, and stderr
# is shown to the agent. It blocks:
# - live publishing (a Gradle argument or ORG_GRADLE_PROJECT_ variable, not a mention), pushing tags and making
#   GitHub releases. The build already refuses to publish outside the tag-triggered release workflow; this is the
#   second line.
# - commands that hang or reach other worktrees: `gradlew runClient/runServer` and their abbreviations,
#   `gradlew --stop`, killing Gradle or Java processes, direct `xvfb-run`, `scripts/play.sh`, and genSources
#   combined with other tasks.
# Patterns target actual use, so searching for, documenting, or *reading* these settings stays possible.
# Run scripts/test-agent-guard.sh after changing it.
input=$(cat)
if command -v jq >/dev/null; then
	cmd=$(jq -r '.tool_input.command // empty' <<< "$input" 2>/dev/null)
elif command -v python3 >/dev/null; then
	cmd=$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("tool_input", {}).get("command") or "")' <<< "$input" 2>/dev/null)
else
	# Fail closed: without a parser nothing below can be checked.
	echo "Blocked by scripts/agent-guard.sh: it needs jq (or python3) to read the command; ask the user to install jq." >&2
	exit 2
fi
[ -n "$cmd" ] || exit 0

block() { echo "Blocked by scripts/agent-guard.sh: $1" >&2; exit 2; }

# The start of a command in command position: after a separator or newline, past wrappers (`env`, `sudo`,
# `bash -c "`, `export`, ...) and VAR=value assignments, so `grep xvfb-run scripts` or `which xvfb-run` don't count
# as running it.
nl=$'\n'
wrappers='env|sudo|exec|nohup|nice|time|command|export|timeout[[:space:]]+[0-9.]+[smhd]?|(ba|z)?sh([[:space:]]+-[A-Za-z]+)*'
cmd_start="(^|[;&|(\`${nl}])[[:space:]]*((${wrappers})[[:space:]]+[\"']?|[A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*[[:space:]]+)*"
path='([^[:space:];&|(`]*/)?'   # optional directory before a command name
last() { echo "${BASH_REMATCH[${#BASH_REMATCH[@]}-1]}"; }   # the last group of the latest match

[[ "$cmd" =~ ${cmd_start}${path}xvfb-run([[:space:]]|$) ]] \
	&& block "don't call xvfb-run directly (its 8-bit display breaks OpenGL); use scripts/client-gametest.sh, which sets a 24-bit display and keeps SDL off Wayland."

[[ "$cmd" =~ ${cmd_start}${path}play\.sh([[:space:]\"\']|$) ]] \
	&& block "scripts/play.sh opens the game for the human to play and never exits; use scripts/client-gametest.sh to see a scene yourself."

[[ "$cmd" =~ ${cmd_start}ORG_GRADLE_PROJECT_publish_live= ]] \
	&& block "live publishing only happens through the tag-triggered release workflow."

[[ "$cmd" =~ ${cmd_start}(pkill|killall)[[:space:]]+[^\;\&\|]*([Gg]radle|java) ]] \
	&& block "killing Gradle or Java processes stops other worktrees' builds and game tests too; leave them running."

# A pushed v* tag publishes a release (release.yml), so tags and GitHub releases are the human's to make.
if [[ "$cmd" =~ ${cmd_start}git([[:space:]]+-C[[:space:]]+[^[:space:]]+)*[[:space:]]+push([^\;\&\|${nl}]*) ]]; then
	read -ra words <<< "$(last)"
	for w in "${words[@]}"; do
		w=${w//[\"\']/}
		case $w in
			--tags|--follow-tags|--mirror|refs/tags/*|v[0-9]*|+v[0-9]*|*:refs/tags/*|*:v[0-9]*)
				block "pushing a tag publishes a release (release.yml); the human tags and pushes releases (README, Releasing)." ;;
		esac
	done
fi
[[ "$cmd" =~ ${cmd_start}gh[[:space:]]+release[[:space:]]+(create|upload|edit|delete) ]] \
	&& block "GitHub releases come from the tag-triggered release workflow; the human makes them."

# Gradle: check the tasks and options of every `gradle`/`gradlew` invocation in the command line.
rest=${cmd//$'\\\n'/ }   # join backslash-continued lines
gradle_re="${cmd_start}${path}gradlew?[\"']?[[:space:]]+([^|;&${nl}]*)"
while [[ "$rest" =~ $gradle_re ]]; do
	rest=${rest#*"${BASH_REMATCH[0]}"}
	read -ra words <<< "$(last)"
	tasks=() skip=0
	for w in "${words[@]}"; do
		w=${w//[\"\'\)\`]/}
		[[ "${w,,}" == *publish_live* ]] && block "live publishing only happens through the tag-triggered release workflow."
		if ((skip)); then skip=0; continue; fi
		case $w in
			--stop) block "\`gradlew --stop\` stops the Gradle daemons of every worktree, including other agents' builds; just leave the daemon running." ;;
			# options whose value is the next word, and redirections to a file: that word is not a task
			-x|--exclude-task|-p|--project-dir|-g|--gradle-user-home|-c|--settings-file|-b|--build-file|-I|--init-script|--console|--warning-mode|--include-build|--priority|--project-cache-dir|-P|--project-prop|-D|--system-prop|[0-9]*[\<\>]|[\<\>]|[\<\>][\<\>\&]) skip=1 ;;
			-*|*[\<\>]*) ;;
			*) [[ "$w" =~ ^:?[A-Za-z][A-Za-z0-9:_.-]*$ ]] && tasks+=("${w##*:}") ;;   # a task, minus its project path
		esac
	done
	gen=0 others=0
	for t in "${tasks[@]}"; do
		# Gradle also takes a task name in any case, or abbreviated by camel-case prefixes (rC, runCl, rS, runServ).
		[[ "${t,,}" == runclient || "${t,,}" == runserver || "$t" =~ ^(r|ru|run)(C|Cl|Cli|Clie|Clien|S|Se|Ser|Serv|Serve)$ ]] \
			&& block "\`$t\` (runClient/runServer) is interactive and never exits; use ./gradlew runGameTest or scripts/client-gametest.sh instead."
		case $t in
			gen*Sources*) ((++gen)) ;;
			exportLibrarySources) ;;   # scripts/setup.sh runs it together with genSources
			*) ((++others)) ;;
		esac
	done
	((gen && gen + others > 1)) \
		&& block "Gradle rejects genSources combined with other tasks; run it on its own (scripts/setup.sh already does)."
done

exit 0
