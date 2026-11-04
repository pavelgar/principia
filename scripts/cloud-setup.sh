#!/usr/bin/env bash
# Setup script for cloud agent environments (Claude Code on the web, Codex cloud). Paste this file's contents into
# the environment's "Setup script" field. It runs as root on Ubuntu 24.04
# when the environment cache is built, so the result (JDK, packages, warmed Gradle caches) is reused by later sessions.
#
# Constraints (Claude Code cloud): it must exit 0 and finish within roughly five minutes. Only the package install
# is critical; everything after it is best-effort and time-boxed.
set -uo pipefail
export DEBIAN_FRONTEND=noninteractive
SUDO=$([ "$(id -u)" = 0 ] || echo sudo)

# The base image ships third-party apt sources (e.g. PPAs on ppa.launchpadcontent.net) that the network allowlist
# blocks, so `apt-get update` reports errors even though the Ubuntu archives refresh fine. Tolerate that and let
# the install decide.
$SUDO apt-get update -q || echo "apt-get update reported errors (unreachable third-party sources); continuing"

# JDK 25 (the image ships 21), plus a virtual display and Mesa's software Vulkan driver for client game tests.
$SUDO apt-get install -y -q --no-install-recommends \
	openjdk-25-jdk-headless unzip jq xvfb xauth mesa-vulkan-drivers || exit 1
# For scripts/client-gametest.sh --video; without it a run reports the video missing instead.
$SUDO apt-get install -y -q --no-install-recommends ffmpeg || echo "ffmpeg didn't install; client-gametest.sh --video won't work"

# The JVM ignores HTTP(S)_PROXY environment variables. The sandbox routes traffic through a proxy,
# so hand it to Gradle as system properties.
proxy="${HTTPS_PROXY:-${https_proxy:-}}"
if [ -n "$proxy" ]; then
	hostport="${proxy#*://}"; hostport="${hostport#*@}"; hostport="${hostport%%/*}"
	mkdir -p "$HOME/.gradle"
	{
		echo "systemProp.https.proxyHost=${hostport%:*}"
		echo "systemProp.https.proxyPort=${hostport##*:}"
		echo "systemProp.http.proxyHost=${hostport%:*}"
		echo "systemProp.http.proxyPort=${hostport##*:}"
	} >> "$HOME/.gradle/gradle.properties"
fi

# Cloud sessions share egress IPs that Maven Central rate-limits (HTTP 429). Route Gradle's Maven Central
# repositories through Google's official mirror (on *.googleapis.com, which the default allowlist covers).
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/maven-central-mirror.gradle" <<'GRADLE'
// Installed by scripts/cloud-setup.sh (cloud environments only). Cloud sessions share egress IPs that Maven Central
// rate-limits (HTTP 429), so resolve Maven Central artifacts from Google's official mirror instead.
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
def centralHosts = ['repo.maven.apache.org', 'repo1.maven.org']
def useMirror = { RepositoryHandler repos ->
	repos.withType(MavenArtifactRepository).configureEach { repo ->
		if (repo.url.host in centralHosts) repo.url = mirror
	}
}
beforeSettings { settings ->
	useMirror(settings.pluginManagement.repositories)
	useMirror(settings.buildscript.repositories)
	useMirror(settings.dependencyResolutionManagement.repositories)
}
allprojects { project ->
	useMirror(project.buildscript.repositories)
	useMirror(project.repositories)
}
GRADLE

# Best effort, time-boxed: warm the Gradle caches (Minecraft, Fabric, game assets) and build the .references/ tree
# if the repo is present. Anything unfinished happens on first use in the session instead.
repo=$(git rev-parse --show-toplevel 2>/dev/null || true)
if [ -n "$repo" ] && [ -x "$repo/gradlew" ]; then
	cd "$repo"
	timeout 150 ./gradlew --console=plain build downloadAssets || echo "cache warm-up incomplete; continuing"
	timeout 60 scripts/setup.sh || echo ".references/ not built yet; the session-start hook builds it"
	./gradlew --stop >/dev/null 2>&1 || true
fi
exit 0
