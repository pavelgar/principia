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
check 0 "./gradlew quickCheck && ./gradlew runGameTest -Pgametest=sky_line"
check 0 "scripts/client-gametest.sh --only=SkyHookClientGameTest"
check 0 "ps aux | grep java"
check 0 "pgrep -f GradleDaemon"
check 0 "kill %1"
check 0 "skill-check"
check 0 "rg xvfb-run scripts/"
check 0 "./gradlew build; echo --stop"
# blocked
check 2 "./gradlew --stop"
check 2 "./gradlew build --stop"
check 2 "cd /tmp && ../gradlew --stop"
check 2 "pkill -f GradleDaemon"
check 2 "killall java"
check 2 "pkill -9 -f 'net.fabricmc.devlaunchinjector'; pkill java"
check 2 "pgrep java | xargs kill"
check 2 "kill \$(pgrep java)"
check 2 "kill -9 \$(pgrep -f GradleDaemon)"
check 2 "jps -q | xargs kill"
check 2 "xvfb-run ./gradlew runClientGameTest"
check 2 "env DISPLAY=:1 xvfb-run -a ./gradlew build"
# no parser: the guard fails closed
json=$(jq -n '{tool_input: {command: "ls"}}')
if [ "$(guard "$noparser" "$json")" = 2 ]; then echo "ok    exit=2  (neither jq nor python3)"; else echo "FAIL  without jq and python3 the guard must block"; fail=1; fi
exit $fail
