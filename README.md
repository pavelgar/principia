# Principia

*A Minecraft mod for Fabric.*

> [!NOTE]
> Principia is in early development and hasn't been released yet.

## Requirements

- Minecraft 26.3 (Java Edition)
- [Fabric Loader](https://fabricmc.net/use/)
- [Fabric API](https://modrinth.com/mod/fabric-api)

Building from source needs Java 25; see [Development](#development).

## Installation

There are no downloads yet. To play a build: install Fabric Loader, then put the Principia jar and Fabric API in your `mods` folder.

## License

Principia is released under the [Principia License 1.0](LICENSE): all rights reserved, with these permissions. You may play it, run it on any server, include it unmodified in modpacks (with credit and a link to its official download page), and make videos and other media about it. Redistributing it on its own or in modified form, and copying its code or assets, need permission.

## Development

One-time setup:

- **JDK 25.** Gradle runs a Java 25 daemon whatever `JAVA_HOME` says and downloads one if none is installed.
- **IDE:** IntelliJ IDEA 2025.3+ with the [Minecraft Development](https://plugins.jetbrains.com/plugin/8327-minecraft-development) plugin. For VS Code, run `./gradlew vscode`.
- **Xvfb** for headless client game tests: `sudo dnf install xorg-x11-server-Xvfb` or `sudo apt install xvfb`.
- **`scripts/setup.sh`** builds the `.references/` source tree that the agents read. Rerun it after a version bump.

`./gradlew runClient` starts the game with the mod, and `./gradlew build` runs every check.

Releasing:

1. Once the project pages exist, fill in `modrinth_project_id` and `curseforge_project_id` in `gradle.properties`, and add the `MODRINTH_TOKEN` and `CURSEFORGE_TOKEN` repository secrets.
2. In a PR, bump `mod_version` in `gradle.properties` and add a matching `## [x.y.z]` section to [CHANGELOG.md](CHANGELOG.md). The `release` skill walks through it.
3. Tag the merge commit on the `/dev` branch with `v<mod_version>+<mc_version>` (e.g. `v0.1.0+26.3`) and push the tag. The `release` workflow publishes to every configured platform and creates a GitHub release.

`./gradlew publishMods` is a dry run unless you pass `-Ppublish_live=true`.
