#!/usr/bin/env bash
# Plays this checkout's build, for the human: the game straight into the playtest world (creative, superflat, made on
# first use in this checkout's run/saves) and, given a scene, builds it beside you as you join. In game, `/playtest`
# lists the scenes and `/playtest <scene>` builds one again; they are in
# src/gametest/java/io/github/pavelgar/principia/test/Scene.java. Agents can't run it: it never exits.
#
#   scripts/play.sh [scene]
set -euo pipefail
cd "$(dirname "$0")/.."
# The variables AI agents set in the commands they run (the same list as agentVariable in build.gradle.kts).
for var in CLAUDECODE AI_AGENT CODEX_THREAD_ID CODEX_CI CODEX_SANDBOX CODEX_SANDBOX_NETWORK_DISABLED; do
	if [ -n "${!var:-}" ]; then
		echo "scripts/play.sh opens the game for the human and never exits, so it doesn't run under an AI agent ($var is set). To see a scene, add it to PlaytestClientGameTest and run scripts/client-gametest.sh." >&2
		exit 1
	fi
done
exec ./gradlew runClient -Pplaytest="${1:-}"
