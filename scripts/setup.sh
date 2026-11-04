#!/usr/bin/env bash
# One-time (and after version bumps) setup for humans and any coding agent (Claude Code, Codex, ...):
# builds a local, greppable reference tree in .references/ (gitignored):
#
#   .references/sources/minecraft/  decompiled Minecraft (common + client), official Mojang names
#   .references/sources/libs/       Fabric API, Fabric Loader, Mixin, MixinExtras, DataFixerUpper, Brigadier, ...
#   .references/fabric-docs/        the Fabric documentation (develop/*.md) and its compiling reference mod (reference/latest)
#   .references/VERSIONS            what the tree was generated from
#
# Re-run after changing versions in gradle/libs.versions.toml. Safe to run repeatedly.
set -euo pipefail

cd "$(dirname "$0")/.."
root=.references
mc_version=$(sed -n 's/^minecraft *= *"\(.*\)"/\1/p' gradle/libs.versions.toml)
fabric_api_version=$(sed -n 's/^fabric-api *= *"\(.*\)"/\1/p' gradle/libs.versions.toml)

echo "==> Decompiling Minecraft $mc_version and exporting library sources"
./gradlew --console=plain -q genSources exportLibrarySources

unpack() { # unpack <dest> <jar>...  (Java sources only)
	local dest=$1; shift
	rm -rf "$dest" && mkdir -p "$dest"
	for jar in "$@"; do
		unzip -qo "$jar" '*.java' -d "$dest" 2>/dev/null || true
	done
}

echo "==> Unpacking Minecraft sources"
shopt -s nullglob
mc_jars=(.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-*/"$mc_version"/*-sources.jar)
if [ ${#mc_jars[@]} -eq 0 ]; then
	echo "error: no Minecraft sources jar found for $mc_version under .gradle/loom-cache" >&2
	exit 1
fi
unpack "$root/sources/minecraft" "${mc_jars[@]}"

echo "==> Unpacking library sources"
unpack "$root/sources/libs" build/agent-sources/*.jar

echo "==> Updating Fabric docs"
if [ -d "$root/fabric-docs/.git" ]; then
	git -C "$root/fabric-docs" pull -q --ff-only
else
	# Only the current English docs and the reference mod; skip images, translations and old versions.
	git clone -q --depth 1 --filter=blob:none --sparse https://github.com/FabricMC/fabric-docs.git "$root/fabric-docs"
	git -C "$root/fabric-docs" sparse-checkout set develop players reference/latest
fi

{
	echo "minecraft=$mc_version"
	echo "fabric-api=$fabric_api_version"
	echo "fabric-docs=$(git -C "$root/fabric-docs" log -1 --format='%h %cs')"
	echo "generated=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$root/VERSIONS"

echo "==> Done: $(find "$root/sources/minecraft" -name '*.java' | wc -l) Minecraft classes, $(find "$root/sources/libs" -name '*.java' | wc -l) library classes"
cat "$root/VERSIONS"
