#!/usr/bin/env bash
# Plays this checkout's build, for the human: the game straight into the playtest world (creative, superflat, made on
# first use in this checkout's run/saves) and, given a scene, builds it beside you as you join. In game, `/playtest`
# lists the scenes and `/playtest <scene>` builds one again; they are in
# src/gametest/java/io/github/pavelgar/principia/test/Scene.java. Agents can't run it: it never exits.
#
#   scripts/play.sh [scene]
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./gradlew runClient -Pplaytest="${1:-}"
