#!/usr/bin/env bash
# PreToolUse hook for shell commands, shared by Claude Code (.claude/settings.json) and Codex (.codex/hooks.json).
# Both tools pass the command as JSON on stdin (.tool_input.command). Exit code 2 blocks the command, and stderr
# is shown to the agent.
#
# A best-effort denylist: it stops an agent's slip early, with a message saying what to do instead, but a command
# spelled to get around it gets around it. What actually holds: releases need GitHub's release environment (a
# required reviewer) and the v* tag ruleset (header of .github/workflows/release.yml); runClient/runServer and
# scripts/play.sh refuse to start under an agent themselves (build.gradle.kts). It blocks:
# - live publishing (a Gradle argument or ORG_GRADLE_PROJECT_ variable, not a mention), pushing tags, and making
#   GitHub releases (gh release, gh api writing releases or tag refs, gh workflow run release).
# - commands that hang or reach other worktrees: `gradlew runClient/runServer` and their obvious abbreviations,
#   `gradlew --stop`, killing Gradle or Java processes, direct `xvfb-run`, `scripts/play.sh`, and genSources
#   combined with other tasks.
# It reads the command roughly as a shell does: quoted text, comments and here-documents are data (a commit message
# may name these commands), while `bash -c`/`eval` strings and command substitutions are checked as commands, and
# wrappers (env, sudo, nice, timeout, xargs, ...) are looked through to the command they run.
# Run scripts/test-agent-guard.sh after changing it.
input=$(< /dev/stdin)
if command -v jq > /dev/null; then
	cmd=$(jq -r '.tool_input.command // empty' <<< "$input" 2> /dev/null)
elif command -v python3 > /dev/null; then
	cmd=$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("tool_input", {}).get("command") or "")' <<< "$input" 2> /dev/null)
else
	# Fail closed: without a parser nothing below can be checked.
	echo "Blocked by scripts/agent-guard.sh: it needs jq (or python3) to read the command; ask the user to install jq." >&2
	exit 2
fi
[ -n "$cmd" ] || exit 0

LC_ALL=C # byte-wise string indexing: fast on long commands (here-documents)

block() { echo "Blocked by scripts/agent-guard.sh: $1" >&2; exit 2; }
icase_match() { # icase_match <text> <regex>, case-insensitively (bash 3 has no ${x,,})
	local r
	shopt -s nocasematch; [[ $1 =~ $2 ]]; r=$?; shopt -u nocasematch
	return $r
}
live_publish="live publishing only happens through the tag-triggered release workflow."
tag_push="pushing a tag publishes a release (release.yml); the human tags and pushes releases (README, Releasing)."
gh_release="GitHub releases come from the tag-triggered release workflow; the human makes them."

work=("$cmd") # shell code to check: the command, then the strings it runs (bash -c, eval, command substitutions)
saw_kill=0 saw_java=0

# --- Reading shell code ------------------------------------------------------------------------------------------
# lex <code>: splits shell code into simple commands, quotes removed, and calls check on each one's words.
# Redirections and their targets, comments and here-document bodies are dropped; command substitutions go to work.
stop_re='[[:space:];&|<>()]'
lex() {
	local s=$1 n=${#1} i=0 c word='' inword=0 redirect=0 q k delim
	local -a words=() heredocs=()
	while ((i < n)); do
		c=${s:i:1}
		case $c in
			' ' | $'\t') end_word ;;
			$'\n')
				end_command
				if ((${#heredocs[@]})); then
					skip_heredocs "$i" "${heredocs[@]}"
					i=$k heredocs=()
				fi
				;;
			';' | '|' | '(' | ')' | '`') end_command ;;
			'&')
				if [[ ${s:i+1:1} == '>' ]]; then # &> file
					end_word
					redirect=1
					((i++))
					[[ ${s:i+1:1} == '>' ]] && ((i++))
				else
					end_command
				fi
				;;
			'<' | '>')
				if [[ ${s:i+1:1} == '(' ]]; then # process substitution: a command
					end_command
				elif [[ ${s:i:3} == '<<<' ]]; then # here-string: data
					end_word
					redirect=1
					((i += 2))
				elif [[ ${s:i:2} == '<<' ]]; then # here-document: its lines are data
					end_word
					k=$((i + 2))
					[[ ${s:k:1} == - ]] && ((k++))
					read_delimiter "$k"
					heredocs+=("$delim")
					i=$((k - 1))
				else
					# A file descriptor number right before it (2>) is part of the redirection.
					if ((inword)) && [[ $word =~ ^[0-9]+$ ]]; then word='' inword=0; else end_word; fi
					while [[ ${s:i+1:1} == [\>\&\|] ]]; do ((i++)); done
					redirect=1
				fi
				;;
			\\)
				if [[ ${s:i+1:1} != $'\n' ]]; then # backslash-newline joins lines
					word+=${s:i+1:1}
					inword=1
				fi
				((i++))
				;;
			\')
				q=${s:i+1}
				[[ $q == *\'* ]] && q=${q%%\'*}
				word+=$q inword=1
				((i += ${#q} + 1))
				;;
			\")
				inword=1
				((i++))
				while ((i < n)); do
					c=${s:i:1}
					case $c in
						\") break ;;
						\\)
							case ${s:i+1:1} in
								$'\n') ((i++)) ;;
								[\"\\\$\`]) word+=${s:i+1:1} && ((i++)) ;;
								*) word+=$c ;;
							esac
							;;
						\$)
							if [[ ${s:i+1:1} == '(' ]]; then substitution; else word+=$c; fi
							;;
						\`)
							q=${s:i+1}
							q=${q%%\`*}
							work+=("$q") word+='(subst)'
							((i += ${#q} + 1))
							;;
						*) word+=$c ;;
					esac
					((i++))
				done
				;;
			\$)
				inword=1
				if [[ ${s:i+1:1} == '(' ]]; then substitution; else word+=$c; fi
				;;
			\#)
				if ((inword)); then
					word+=$c
				else # a comment, up to the end of the line
					q=${s:i}
					q=${q%%$'\n'*}
					((i += ${#q} - 1))
				fi
				;;
			*) word+=$c inword=1 ;;
		esac
		((i++))
	done
	end_command
}
# These run inside lex and use its variables.
end_word() {
	if ((inword)); then
		((redirect)) || words+=("$word")
		redirect=0
	fi
	word='' inword=0
}
end_command() {
	end_word
	redirect=0
	((${#words[@]})) && check "${words[@]}"
	words=()
}
substitution() { # at "$(": its contents go to work, and the word gets a placeholder
	close_paren $((i + 2))
	work+=("${s:i+2:j-i-2}")
	word+='(subst)'
	i=$j
}
close_paren() { # close_paren <index>: sets j to the index of the ")" closing the "(" just before it
	local depth=1 ch q
	local -a hd=()
	j=$1
	while ((j < n)); do
		ch=${s:j:1}
		case $ch in
			\\) ((j++)) ;;
			\')
				q=${s:j+1}
				[[ $q == *\'* ]] || { j=$n; return 0; }
				q=${q%%\'*}
				((j += ${#q} + 1))
				;;
			\")
				((j++))
				while ((j < n)) && [[ ${s:j:1} != \" ]]; do
					[[ ${s:j:1} == \\ ]] && ((j++))
					((j++))
				done
				;;
			\<)
				if [[ ${s:j:3} == '<<<' ]]; then
					((j += 2))
				elif [[ ${s:j:2} == '<<' ]]; then
					k=$((j + 2))
					[[ ${s:k:1} == - ]] && ((k++))
					read_delimiter "$k"
					hd+=("$delim")
					j=$((k - 1))
				fi
				;;
			$'\n')
				if ((${#hd[@]})); then
					skip_heredocs "$j" "${hd[@]}"
					j=$k hd=()
				fi
				;;
			\() ((depth++)) ;;
			\)) ((--depth)) || return 0 ;;
		esac
		((j++))
	done
}
read_delimiter() { # read_delimiter <index>: the here-document delimiter there, unquoted, in delim; k is past it
	k=$1
	while [[ ${s:k:1} == ' ' || ${s:k:1} == $'\t' ]]; do ((k++)); done
	delim=''
	while ((k < n)) && ! [[ ${s:k:1} =~ $stop_re ]]; do
		[[ ${s:k:1} == [\"\'\\] ]] || delim+=${s:k:1}
		((k++))
	done
}
skip_heredocs() { # skip_heredocs <index of a newline> <delimiter>...: k is the newline ending the last body
	local d line
	k=$1
	shift
	for d; do
		while ((k + 1 < n)); do
			line=${s:k+1}
			line=${line%%$'\n'*}
			((k += ${#line} + 1))
			while [[ $line == $'\t'* ]]; do line=${line#$'\t'}; done
			[[ $line == "$d" ]] && break
		done
	done
}

# --- Checking simple commands ------------------------------------------------------------------------------------
# skip_options <regex of the options that take a value> <word>...: the words after the options, in rest.
skip_options() {
	local takes=$1
	shift
	while (($#)) && [[ $1 == -* ]]; do
		[[ $1 == -- ]] && { shift; break; }
		[[ $1 =~ ^($takes)$ ]] && shift
		shift
	done
	rest=("$@")
}
assignment() { # assignment <NAME=value>: a variable set for a command or exported
	[[ $1 == ORG_GRADLE_PROJECT_publish_live=* ]] && block "$live_publish"
	return 0
}

# check <word>...: one simple command. Looks past keywords, assignments and wrappers to the command it runs.
check() {
	local name
	while (($#)); do
		case $1 in
			'{' | '}' | '!' | if | then | elif | else | fi | do | done | while | until | coproc) shift ;;
			*=*)
				[[ $1 =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] || break
				assignment "$1"
				shift
				;;
			*) break ;;
		esac
	done
	(($#)) || return 0
	name=${1##*/}
	shift
	# Gradle or Java processes looked up anywhere, for a kill elsewhere in the command line (`kill $(pgrep java)`).
	{ [[ $name == jps ]] || { [[ $name != kill ]] && icase_match "$*" 'gradle|java'; }; } && saw_java=1

	case $name in
		# wrappers: the command they run is checked instead
		env) skip_options '-u|--unset|-C|--chdir' "$@" && check "${rest[@]}" ;;
		sudo | doas) skip_options '-[ugCDhprtURT]|--user|--group|--chdir|--host|--prompt|--role|--type|--other-user' "$@" && check "${rest[@]}" ;;
		nice) skip_options '-n|--adjustment' "$@" && check "${rest[@]}" ;;
		ionice) skip_options '-[cnpPu]|--class|--classdata|--pid|--pgid|--uid' "$@" && check "${rest[@]}" ;;
		timeout) skip_options '-[ks]|--kill-after|--signal' "$@" && check "${rest[@]:1}" ;; # past the duration
		xargs) skip_options '-[aEdILnPs]|--arg-file|--delimiter|--max-args|--max-procs|--max-chars|--max-lines' "$@" && check "${rest[@]}" ;;
		time) skip_options '-[fo]|--format|--output' "$@" && check "${rest[@]}" ;;
		exec) skip_options '-a' "$@" && check "${rest[@]}" ;;
		stdbuf) skip_options '-[ioe]' "$@" && check "${rest[@]}" ;;
		watch) skip_options '-n|--interval' "$@" && check "${rest[@]}" ;;
		chrt | taskset) skip_options '' "$@" && check "${rest[@]:1}" ;; # past the priority or CPU mask
		nohup | setsid | builtin | caffeinate | unbuffer | chronic | systemd-run) skip_options '' "$@" && check "${rest[@]}" ;;
		command) [[ ${1-} == -[vV] ]] || { skip_options '' "$@" && check "${rest[@]}"; } ;; # command -v only looks
		source | .) check "$@" ;;
		eval) work+=("$*") ;;
		bash | sh | zsh | dash | ksh | mksh | ash | fish)
			local code=0
			while (($#)); do
				case $1 in
					--) shift && break ;;
					[-+][oO] | --rcfile | --init-file) shift ;;
					--*) ;;
					-*c*) code=1 ;; # -c, -lc, -ec
					[-+]*) ;;
					*) break ;;
				esac
				shift
			done
			(($#)) || return 0
			if ((code)); then work+=("$1"); else check "$@"; fi # a code string, or a script and its arguments
			;;
		script | su)
			while (($#)); do
				case $1 in
					-c | --command | -[!-]*c) work+=("${2-}") && return 0 ;;
					--command=*) work+=("${1#*=}") && return 0 ;;
				esac
				shift
			done
			;;
		# the commands checked
		xvfb-run) block "don't call xvfb-run directly (its 8-bit display breaks OpenGL); use scripts/client-gametest.sh, which sets a 24-bit display and keeps SDL off Wayland." ;;
		play.sh) block "scripts/play.sh opens the game for the human to play and never exits; use scripts/client-gametest.sh to see a scene yourself." ;;
		export | declare | typeset | readonly | local) for w; do assignment "$w"; done ;;
		pkill | killall) icase_match "$*" 'gradle|java' && block "killing Gradle or Java processes stops other worktrees' builds and game tests too; leave them running." ;;
		kill) saw_kill=1 ;;
		git) check_git "$@" ;;
		gh) check_gh "$@" ;;
		gradle | gradlew) check_gradle "$@" ;;
	esac
	return 0
}

# A pushed v* tag publishes a release (release.yml), so tags and GitHub releases are the human's to make.
check_git() {
	local w
	while (($#)); do # git's own options, before the subcommand
		case $1 in
			-C | -c | --git-dir | --work-tree | --namespace | --super-prefix | --config-env) shift 2 ;;
			-*) shift ;;
			*) break ;;
		esac
	done
	[[ ${1-} == push ]] || return 0
	shift
	for w; do [[ $w == -n || $w == --dry-run ]] && return 0; done
	for w; do
		case $w in
			--tags | --follow-tags | --mirror | refs/tags/* | +refs/tags/* | tags/* | +tags/* | v[0-9]* | +v[0-9]* | *:refs/tags/* | *:tags/* | *:v[0-9]*)
				block "$tag_push"
				;;
		esac
	done
}

check_gh() {
	case ${1-} in
		release) [[ ${2-} =~ ^(create|upload|edit|delete|delete-asset)$ ]] && block "$gh_release" ;;
		workflow) [[ ${2-} == run && "$*" == *release* ]] && block "$gh_release" ;;
		api) shift && check_gh_api "$@" ;;
	esac
	return 0
}
check_gh_api() { # a request that isn't a GET, to releases or git refs/tags
	local method='' fields=0 target=0
	while (($#)); do
		case $1 in
			-X | --method) method=${2-} && shift ;;
			-X*) method=${1#-X} ;;
			--method=*) method=${1#*=} ;;
			-f | -F | --field | --raw-field | --input) fields=1 && shift ;;
			-f* | -F* | --field=* | --raw-field=* | --input=*) fields=1 ;;
			*releases* | *git/refs* | *git/tags*) target=1 ;;
		esac
		shift
	done
	[[ -z $method ]] && ((fields)) && method=POST # gh api sends a POST when given fields
	((target)) && [[ -n $method ]] && ! icase_match "$method" '^get$' && block "$gh_release"
	return 0
}

check_gradle() {
	local w t skip=0 gen=0 others=0
	local -a tasks=()
	for w; do
		icase_match "$w" publish_live && block "$live_publish"
		if ((skip)); then skip=0 && continue; fi
		case $w in
			--stop) block "\`gradlew --stop\` stops the Gradle daemons of every worktree, including other agents' builds; just leave the daemon running." ;;
			# options whose value is the next word: that word is not a task
			-x | --exclude-task | -p | --project-dir | -g | --gradle-user-home | -c | --settings-file | -b | --build-file | -I | --init-script | --console | --warning-mode | --include-build | --priority | --project-cache-dir | -P | --project-prop | -D | --system-prop) skip=1 ;;
			-*) ;;
			*) [[ $w =~ ^:?[A-Za-z][A-Za-z0-9:_.-]*$ ]] && tasks+=("${w##*:}") ;; # a task, minus its project path
		esac
	done
	for t in "${tasks[@]}"; do
		# The obvious spellings, for an early message: the build itself refuses the rest when an agent runs it.
		icase_match "$t" '^((r|ru|run)(c|cl|cli|clie|clien|client|s|se|ser|serv|serve|server)|runclientrenderdoc)$' \
			&& block "\`$t\` (runClient/runServer) is interactive and never exits; use ./gradlew runGameTest or scripts/client-gametest.sh instead."
		case $t in
			gen*Sources*) ((++gen)) ;;
			exportLibrarySources) ;; # scripts/setup.sh runs it together with genSources
			*) ((++others)) ;;
		esac
	done
	((gen && gen + others > 1)) \
		&& block "Gradle rejects genSources combined with other tasks; run it on its own (scripts/setup.sh already does)."
	return 0
}

checked=0
while ((${#work[@]})) && ((checked++ < 100)); do
	code=${work[0]}
	work=("${work[@]:1}")
	lex "$code"
done
((saw_kill && saw_java)) \
	&& block "killing Gradle or Java processes stops other worktrees' builds and game tests too; leave them running."
exit 0
