# AGENTS.md

Guidance for coding agents working on **Principia**, a standalone Fabric mod. The human's page is `README.md`.

## Your training data is wrong about this Minecraft version

Minecraft uses year-based versions now (26.1 followed 1.21.11). 26.1+ ships **unobfuscated with official Mojang names**, and Fabric API was renamed to match. Most modding code online, and in your memory, targets 1.21.x with Yarn names. Before writing any Minecraft or Fabric API call, look it up in the local sources with the `mc-api-lookup` skill. A "cannot find symbol" error means you guessed: go back to the sources rather than trying variations.

Traps, each with its current form:
- Mappings are gone: no `mappings(...)`, `modImplementation`, `remapJar` or Yarn names (`class_1234`, `MinecraftClient`, `ItemGroup`, `RegistryKeys`, `Identifier.of`, `Text`, `PlayerEntity`). Depend on `fabric-api`; the mod ID `fabric` is gone.
- Identifiers are `net.minecraft.resources.Identifier`, created with `Identifier.fromNamespaceAndPath(ns, path)` (`Principia.id("path")` here). `ResourceLocation` no longer exists.
- Fabric API classes carry Mojang names: `ItemGroupEvents` is `CreativeModeTabEvents` in `net.fabricmc.fabric.api.creativetab.v1`.
- Item and block properties take their registry key: `new Item.Properties().setId(ResourceKey)`. Registries are `BuiltInRegistries` / `Registries`.
- Item data is **data components** (`DataComponents`, `DataComponentType`), not NBT on stacks.
- Access wideners are **class tweakers** (`*.classtweaker`, header `classTweaker v2 official`; the parser is in `.references/sources/libs/net/fabricmc/classtweaker/`). They also inject interfaces and extend enums.
- Rendering goes through the GPU API in `com.mojang.renderpearl` (OpenGL and Vulkan backends; `RenderSystem` and vertex formats stay in `com.mojang.blaze3d`), never raw GL calls.
- Recipes aren't sent to clients (since 1.21.2), so recipe viewers see a custom recipe type only when its `RecipeSerializer` is passed to `RecipeSynchronization.synchronizeRecipeSerializer` in common init.
- The local Fabric docs (`.references/fabric-docs`) can trail the Minecraft version: check the version their `reference/latest/build.gradle` pins. Fabric's release post for our version (for 26.3, `https://fabricmc.net/2026/09/15/263.html`) lists what changed since, e.g. 26.3 removed `Block.codec()`, which the 26.2 docs' block entity examples still override.
- Even the Fabric docs lag: `develop/automatic-testing.md` calls `singleplayer.getClientLevel().waitForChunksRender()`, which since 0.161 is `singleplayer.getConnection().waitForChunksRender()` (`getConnection().getClientLevel()` is the plain `ClientLevel`, without it), and still sets `-Dfabric.client.gametest.disableNetworkSynchronizer`, which 0.161 removed with the synchronizer: wait with `getConnection().waitForClientboundPackets()` and its siblings. **The sources are the ground truth; the docs explain intent.**

## Local references (gitignored)

`.references/` holds the decompiled Minecraft sources, Fabric API and the other libraries, and the Fabric docs. `scripts/setup.sh` builds it in about a minute; rerun it when `.references/VERSIONS` differs from `gradle/libs.versions.toml`. At each session start, `scripts/session-start.sh` copies it into a new worktree from the main checkout and warns when it's stale; a worktree made mid-session needs it copied (`cp -a --reflink=auto <main checkout>/.references .`).

Grep it instead of guessing. Vanilla usually already does what you need: find the block, item or entity that behaves similarly and read how it's built.

## Commands

Gradle tasks in one checkout run one at a time; different worktrees may build in parallel. The first build after a version change takes several minutes, and the first `runDatagen` or client game test downloads about 490 MB of game assets into `~/.gradle`.

- `./gradlew quickCheck` is the inner loop, in seconds: formats, compiles every source set, runs the JUnit tests. No game starts.
- `./gradlew build`: compile, JUnit tests, server game tests, formatting, jar.
- `./gradlew spotlessApply` formats the Java sources; `build` fails on violations.
- `./gradlew runGameTest` runs the server game tests alone, in a fresh world at a fixed origin, so a failure reruns as it failed. `-Pgametest=<part of the id>` runs some, `-Pgametest_repeat=100` hunts flakiness, `-Pgametest_origin=random` lays them out as vanilla does; the comment on these in `build.gradle.kts` has the details. Results also land in `build/test-results/gameTest/TEST-gametest.xml`.
- `scripts/client-gametest.sh` (`--only=<Class>` for one test class) runs the client game tests (headless when `xvfb-run` is installed, in a window otherwise) and copies the screenshots and the log to the `build/evidence/...` folder it prints. It is not part of `build`.
- `scripts/play.sh [scene]` starts the game straight into this checkout's playtest world and builds the scene beside the player. It never exits, so the guard blocks it for agents; `PlaytestClientGameTest` builds and screenshots every scene.
- `./gradlew pitest -Ppit_target=<package>.<Class>*` mutates those classes, runs their package's JUnit tests against each mutant, and lists the mutants no test noticed, each with what it changed, in `build/reports/pitest/mutations.xml`. Tests that need Fabric Loader fail under it. It is not part of `build`.
- `./gradlew fuzzTest` runs the Jazzer fuzz tests (`@FuzzTest` in `<Class>FuzzTest`) on the inputs saved under `src/test/resources/<package>/<Class>FuzzTestInputs/<method>/`; `build` runs it. With `-Pfuzz` and `--tests '*<Class>FuzzTest.<method>'`, one method at a time, it searches for an input that fails, for up to the test's `maxDuration`, and saves one it finds there. Fuzz tests run without Fabric Loader, as under PIT.
- `./gradlew runDatagen` regenerates `src/main/generated/` after a datagen provider changed. It fails when a registered block or item lacks a loot table, blockstate or model.

Checks that fail on their own:
- javac warnings are errors, and Error Prone runs in the compile with NullAway in JSpecify mode. A `[NullAway]` error is a real null path: add the check or the `@Nullable` (`org.jspecify.annotations`) it points at.
- Every game-test run ends with a log check (`check*Log` tasks in `build.gradle.kts`) that fails on missing models, textures or blockstates, on a mixin that missed its target, and on an item tag without an English name.

When something else fails, look at Gradle's output and `build/reports/`, the game-test logs (`build/run/gameTest/logs/latest.log`, `build/run/clientGameTest/logs/latest.log`) and, for `runClient` sessions, `run/logs/latest.log` and `run/crash-reports/`. In a log, search for `Caused by:`, `Registry`, and the first `ERROR` after `Principia initialized`.

## Several checkouts at once

- Client game tests run in parallel across worktrees (about 2 GB of RAM each). A game test that starts a *dedicated server* takes a free `server-port` (`freePort()` in `DedicatedServerClientGameTest`), or it collides with other worktrees' on 25565.
- With less than 6 GB of RAM, run `scripts/setup.sh -Pdecompiler_memory=2048 -Pdecompiler_threads=2` or genSources gets OOM-killed.
- Give Gradle settings as `-P` properties, never a leading `VAR=value`: the command rules of Claude Code and Codex match a command by how it starts, and Codex can't match one that starts with an assignment.

## Agent tooling

- This file is the only instruction file. Claude Code reads `AGENTS.md` natively, and a `CLAUDE.md` or `CLAUDE.local.md` would stop that.
- Each skill in `.agents/skills/` (Codex) has a symlink in `.claude/skills/` (Claude Code); `scripts/check-skills.sh` checks it in CI.
- `scripts/agent-guard.sh` blocks commands that hang or reach other worktrees, live publishing, and pushing tags or making releases (the human releases); `scripts/test-agent-guard.sh` tests it. Command permissions live in `.claude/settings.json` and `.codex/rules/principia.rules`.
- When the jdtls plugin (Java language server) is on, hover on a Minecraft or Fabric call gives its exact signature, and go-to-definition works within `src/`. It can't open library classes, so read those in `.references/sources/`.

## Conventions

- `src/main/generated` is datagen output: to change it, change the provider and rerun `runDatagen`.
- English text is declared in Java next to what it names: a block's or item's name is an argument of its `Content` registration, other text an `English.add` constant. `en_us.json` is generated from it; other languages are hand-written files in `src/main/resources`.
- JUnit tests call `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();` before touching registries, and need nothing that runs a game or opens a window.
- A failing test is evidence: fix the code, or report the test as wrong. A test, saved fuzz input or screenshot template changes only with the behaviour it pins, and the PR says so.
- Every serverbound payload handler starts with `ClientActions.accept(player)` (alive, loaded, no pending teleport), then the feature's own checks: reach, line of sight, protection.
- How to write and run a client game test: `.agents/skills/mc-api-lookup/client-gametests.md`.

Rules the existing code can't show, because they are about code not yet written:
- A feature keeps its Fabric-specific calls (API Lookup, Transfer, data attachments, networking registration, events) in one registration class.
- Multiplayer first: a player's own state is stored per player and synced only to them, and whatever changes the world is shared server-side state. No system assumes a single player.
- Interoperate through shared conventions where they fit: `c:` tags for materials, and Fabric's Transfer API for moving items and fluids, rather than code for particular mods.
- Draw effects with vanilla render types; add a custom render pipeline only where a feature can't work without one, because shader packs (Iris) handle vanilla's types and break on unknown pipelines.
- Prefer, in order: Fabric API events or hooks, then vanilla extension points (datapacks, tags, data components), then Mixins. For Mixins, prefer MixinExtras (`@WrapOperation`, `@ModifyExpressionValue`, `@ModifyReturnValue`, `@Local`, `@WrapMethod`) over `@Redirect` / `@Overwrite`.
