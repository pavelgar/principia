# shellcheck shell=bash
# Sourced by scripts/verify.sh and scripts/stop-check.sh: what an agent needs from a failed Gradle run, without the
# rest of its log. Prints the tasks that failed, then each kind of failure found, then (when none was recognised)
# Gradle's own summary.

# gradle_failures <log>
gradle_failures() {
	local log=$1 found=0
	local failed
	failed=$(grep -oE '^> Task :[^ ]+ FAILED' "$log" | sed 's/^> Task \(.*\) FAILED/\1/' | sort -u | tr '\n' ' ')
	[ -n "$failed" ] && echo "Failed tasks: $failed"

	# Compiler errors (javac, Error Prone, NullAway) as javac prints them, each with its source line and caret; Gradle
	# repeats them indented in its summary, which is skipped.
	if grep -qE '^/.*\.java:[0-9]+: (error|warning):' "$log"; then
		echo
		grep -E -A2 '^/.*\.java:[0-9]+: (error|warning):' "$log" | head -n 45
		found=1
	fi
	# Failed JUnit tests (`Class > method() FAILED`, not `> Task :x FAILED`), with their message and first frames.
	if grep -qE '^[^>].* > .* FAILED$' "$log"; then
		echo
		grep -E -A8 '^[^>].* > .* FAILED$' "$log" | head -n 45
		found=1
	fi
	# Failed server game tests, from their JUnit report: id and message, one per line.
	# Without a report, the server stopped before the tests ran (a data file it couldn't load, a crash): its log says why.
	local report=build/test-results/gameTest/TEST-gametest.xml game_log=build/run/gameTest/logs/latest.log
	if [[ " $failed " == *" :runGameTest "* ]]; then
		echo
		if [ -f "$report" ]; then
			echo "Failed game tests (rerun one with ./gradlew runGameTest -Pgametest=<part of its id>):"
			# One testcase per line (awk, not sed: BSD sed can't write a newline with \n).
			awk '{ gsub(/<testcase /, "\n<testcase "); print }' "$report" | grep '<failure' \
				| sed -E 's/.* name="([^"]*)".*<failure message="([^"]*)".*/  \1: \2/' | head -n 20
		else
			echo "The game test server stopped before running the tests. From $game_log:"
			grep -E '/ERROR\]|^Caused by:|found no tests' "$game_log" 2> /dev/null | awk '!seen[$0]++' | head -n 12
		fi
		found=1
	fi
	# Anything else (formatting, a game-log check, a Gradle error): Gradle's own failure summary.
	if ((!found)); then
		echo
		sed -n '/^\* What went wrong:/,/^\* Try:/p' "$log" | grep -v '^\* Try:' | head -n 40
	fi
}
