#!/usr/bin/env bash
# Self-test for scripts/agent-guard.sh: run it after changing the guard. Each case runs twice: with jq, and with
# only python3 on the PATH, the guard's fallback for machines without jq.
cd "$(dirname "$0")/.." || exit 1
fail=0
nojq=$(mktemp -d) noparser=$(mktemp -d)
trap 'rm -rf "$nojq" "$noparser"' EXIT
python=$(command -v python3) && ln -s "$python" "$nojq/python3"
guard() { # guard <PATH> <json>: the guard's exit code
	PATH=$1 "$BASH" scripts/agent-guard.sh <<< "$2" 2> /dev/null
	echo $?
}
check() { # check <expected exit> <command>
	local json got fallback
	json=$(jq -n --arg c "$2" '{tool_input: {command: $c}}')
	got=$(guard "$PATH" "$json")
	if [ -n "$python" ]; then fallback=$(guard "$nojq" "$json"); else fallback=$got; fi
	if [ "$got" = "$1" ] && [ "$fallback" = "$1" ]; then
		echo "ok    exit=$got  $2"
	else
		echo "FAIL  exit=$got, without jq $fallback (want $1)  $2"
		fail=1
	fi
}
# allowed
check 0 "./gradlew build"
check 0 "scripts/setup.sh"
check 0 "echo done && ./gradlew test"
check 0 "rg publish_live build.gradle.kts"
check 0 "grep -n 'publish_live=true' scripts/test-agent-guard.sh"
check 0 "echo publish_live=true >> notes.md"
check 0 "python3 scripts/edit_docs.py"
check 0 "./gradlew publishMods"
check 0 "./gradlew runClientGameTest -Pgametest=SkyHook"
check 0 "./gradlew rCGT"
check 0 "git push"
check 0 "git push -u origin 26.3/skyline-fix"
check 0 "git push origin HEAD:refs/heads/26.3/vector-fix"
check 0 "git push --dry-run origin v1"
check 0 "git push -n --tags"
check 0 "git tag --list"
check 0 "gh release list"
check 0 "gh release view v0.1.0+26.3"
check 0 "gh api repos/pavelgar/principia/releases"
check 0 "gh api -X GET repos/pavelgar/principia/releases/latest"
check 0 "gh api repos/pavelgar/principia/pulls -f title=x"
check 0 "gh workflow run build.yml"
check 0 "pgrep -f GradleDaemon"
check 0 "kill 12345"
check 0 "grep -rn 'pkill java' docs"
check 0 "bash scripts/test-agent-guard.sh"
check 0 "bash -c 'echo hi'"
check 0 "sh scripts/client-gametest.sh --only=SkyHookClientGameTest"
check 0 "./gradlew runClientGameTest"
check 0 "./gradlew runProductionClientGameTest"
check 0 "./gradlew runGameTest"
check 0 "./gradlew runDatagen"
check 0 "scripts/client-gametest.sh --production"
check 0 "cat scripts/play.sh"
check 0 "grep -n play.sh AGENTS.md"
check 0 "grep -rn 'runClient' docs"
check 0 "grep -rn xvfb-run scripts"
check 0 "which xvfb-run"
check 0 "command -v xvfb-run >/dev/null || sudo dnf install xorg-x11-server-Xvfb"
check 0 "./gradlew genSources"
check 0 "./gradlew --console=plain -q genSources exportLibrarySources"
check 0 "ORG_GRADLE_PROJECT_decompiler_memory=2048 ./gradlew genSources 2>&1 | tail -n 20"
check 0 "./gradlew genSources && ./gradlew build"
check 0 "./gradlew build -x test --console plain"
# allowed: quoted text, comments and here-documents are data, not commands
check 0 "git commit -m \"fix; ./gradlew --stop and git push --tags\""
check 0 "git commit -m 'docs: never run ./gradlew runClient; use scripts/play.sh'"
check 0 "echo ok # ./gradlew runClient"
check 0 $'cat > notes.md <<\'EOF\'\n./gradlew runClient\ngit push --tags\nEOF\n./gradlew build'
check 0 $'git commit -m "$(cat <<\'EOF\'\nfix: don\'t ./gradlew --stop (or runClient\nEOF\n)"'
# blocked
check 2 "./gradlew publishMods -Ppublish_live=true"
check 2 "./gradlew runClient"
check 2 "./gradlew runServer --info"
check 2 "./gradlew runClientRenderDoc"
check 2 "gradle runClient"
check 2 "./gradlew build runClient"
check 2 "./gradlew :runClient"
check 2 "cd .claude/worktrees/x && ./gradlew runClient"
check 2 "timeout 600 ./gradlew runClient"
check 2 "./gradlew --stop"
check 2 "./gradlew build && ./gradlew --stop"
check 2 "./gradlew build; ./gradlew --stop"
check 2 "xvfb-run -a ./gradlew runClientGameTest"
check 2 "env FOO=1 xvfb-run ./gradlew runClientGameTest"
check 2 "cd x && /usr/bin/xvfb-run ./gradlew runClientGameTest"
check 2 "./gradlew genSources build"
check 2 "./gradlew build genSources"
check 2 "./gradlew --console=plain genClientOnlySources runGameTest"
check 2 "scripts/play.sh"
check 2 "scripts/play.sh descent"
check 2 "cd x && ./scripts/play.sh lines"
# blocked: live publishing in every spelling Gradle accepts
check 2 "ORG_GRADLE_PROJECT_publish_live=true ./gradlew publishMods"
check 2 "ORG_GRADLE_PROJECT_publish_live=TRUE ./gradlew publishMods"
check 2 "export ORG_GRADLE_PROJECT_publish_live=true; ./gradlew publishMods"
check 2 "env ORG_GRADLE_PROJECT_publish_live=true ./gradlew publishMods"
check 2 "cd x && ./gradlew publishMods -Ppublish_live=true --info"
check 2 "./gradlew publishMods -Ppublish_live=TRUE"
check 2 "./gradlew publishMods -P publish_live=true"
check 2 "./gradlew publishMods --project-prop publish_live=true"
check 2 "./gradlew publishMods -Dorg.gradle.project.publish_live=true"
# blocked: releases
check 2 "git push --tags"
check 2 "git push origin --follow-tags"
check 2 "git push origin v0.1.0+26.3"
check 2 "git push origin refs/tags/v0.1.0+26.3"
check 2 "git push origin tags/v1"
check 2 "git -C ../other push origin HEAD:refs/tags/v1"
check 2 "git -c a=b push --tags"
check 2 "git --no-pager push --tags"
check 2 "git --git-dir .git push --tags"
check 2 "/usr/bin/git push --tags"
check 2 "command git push --tags"
check 2 $'git push origin \\\n  --tags'
check 2 "git push --mirror"
check 2 "gh release create v0.1.0+26.3"
check 2 "gh api -X POST repos/pavelgar/principia/releases"
check 2 "gh api --method=PATCH repos/pavelgar/principia/releases/1"
check 2 "gh api repos/pavelgar/principia/releases -f tag_name=v1"
check 2 "gh api repos/pavelgar/principia/git/refs -f ref=refs/tags/v1 -f sha=abc"
check 2 "gh workflow run release.yml"
# blocked: wrappers, shell syntax, quoting and abbreviations
check 2 "bash scripts/play.sh"
check 2 "source scripts/play.sh"
check 2 ". scripts/play.sh"
check 2 "(scripts/play.sh)"
check 2 "sh -c \"./gradlew runClient\""
check 2 "bash -c './gradlew runServer'"
check 2 "bash -lc './gradlew runServer'"
check 2 "\"./gradlew\" runClient"
check 2 "echo \$(./gradlew runClient)"
check 2 "echo \"\$(./gradlew runClient)\""
check 2 "echo \`./gradlew runClient\`"
check 2 "eval \"./gradlew runClient\""
check 2 "{ ./gradlew runClient; }"
check 2 "if true; then ./gradlew runClient; fi"
check 2 "! ./gradlew runClient"
check 2 "nice -n 10 ./gradlew runClient"
check 2 "sudo -u x ./gradlew runClient"
check 2 "env -i ./gradlew runClient"
check 2 "timeout -k 5 10 ./gradlew runClient"
check 2 "ionice -c 3 ./gradlew runClient"
check 2 "setsid ./gradlew runClient"
check 2 "script -c './gradlew runClient' /dev/null"
check 2 "echo x | xargs ./gradlew runClient"
check 2 $'./gradlew \\\n  runClient'
check 2 "./gradlew rC"
check 2 "./gradlew RC"
check 2 "./gradlew runCl"
check 2 "./gradlew runcl"
check 2 "./gradlew runServe"
check 2 "./gradlew runclient"
check 2 "pkill -f GradleDaemon"
check 2 "killall java"
check 2 "pgrep java | xargs kill"
check 2 "kill \$(pgrep java)"
check 2 "kill -9 \$(pgrep -f GradleDaemon)"
check 2 "jps -q | xargs kill"
# no parser: the guard fails closed
json=$(jq -n '{tool_input: {command: "ls"}}')
if [ "$(guard "$noparser" "$json")" = 2 ]; then echo "ok    exit=2  (neither jq nor python3)"; else echo "FAIL  without jq and python3 the guard must block"; fail=1; fi
exit $fail
