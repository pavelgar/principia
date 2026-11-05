# AGENTS.md

Principia is a standalone Fabric mod for Minecraft 26.3. This file holds what the repo can't tell you by itself; `README.md` is the human's page.

## Your training data is wrong about this Minecraft version

Minecraft numbers its versions by year now (26.1 followed 1.21.11). Since 26.1 it ships **unobfuscated with official Mojang names**, and Fabric API was renamed to match. Most modding code online and in your memory targets 1.21.x with Yarn names. Before writing a Minecraft or Fabric API call, look it up in the local sources with the `mc-api-lookup` skill. A "cannot find symbol" error means the name was a guess: go back to the sources.

The current forms of what you'll be tempted to write from memory:
- No mappings: depend on `fabric-api` with plain `implementation`, and use Mojang names throughout. (Gone: `mappings(...)`, `modImplementation`, `remapJar`, the mod ID `fabric`, and Yarn names such as `MinecraftClient`, `ItemGroup`, `RegistryKeys`, `Text`, `PlayerEntity`.)
- Identifiers are `net.minecraft.resources.Identifier` (it replaced `ResourceLocation`): `Identifier.fromNamespaceAndPath(ns, path)`, or `Principia.id("path")` here.
- Fabric API classes carry Mojang names too: `CreativeModeTabEvents` in `net.fabricmc.fabric.api.creativetab.v1`.
- Item and block properties take their registry key: `new Item.Properties().setId(ResourceKey)`. Registries are `BuiltInRegistries` and `Registries`.
- Item data lives in **data components** (`DataComponents`, `DataComponentType`).
- Access wideners are **class tweakers** (`*.classtweaker`, header `classTweaker v2 official`; parser in `.references/sources/libs/net/fabricmc/classtweaker/`). They can also inject interfaces and extend enums.
- Rendering goes through the GPU API in `com.mojang.renderpearl` (OpenGL and Vulkan backends); `RenderSystem` and vertex formats stay in `com.mojang.blaze3d`.
- Clients don't receive recipes, so a recipe viewer sees a custom recipe type only after its `RecipeSerializer` goes to `RecipeSynchronization.synchronizeRecipeSerializer` in common init.
- The Fabric docs in `.references/fabric-docs` can trail our version (see the version `reference/latest/build.gradle` pins), and Fabric's release post (`https://fabricmc.net/2026/09/15/263.html` for 26.3) lists what changed since. For example, 26.3 removed `Block.codec()`, which the docs' block entity examples still override. **The sources are the ground truth; the docs explain intent.**

## Local references

`.references/` (gitignored) holds the decompiled Minecraft sources, Fabric API and other libraries, and the Fabric docs. The session-start hook copies it into new worktrees and warns when `.references/VERSIONS` no longer matches `gradle/libs.versions.toml`; `scripts/setup.sh` rebuilds it in about a minute (its header has the flags for machines under 6 GB of RAM).

Search it with an explicit path inside `.references/`: ripgrep and the Grep tool skip gitignored trees, so a repo-wide search silently finds nothing there. Vanilla usually already does what you need: find the block, item or entity that behaves similarly and read how it's built.

## Commands

Gradle tasks in one checkout run one at a time; separate worktrees build in parallel. The first build after a version change takes several minutes, and the first `runDatagen` or client game test downloads about 490 MB of assets. Pass Gradle settings as `-P` properties after the task (`./gradlew build -Pfoo=bar`), with the command starting at `./gradlew`: the permission rules of Claude Code and Codex match a command by how it starts, and Codex can't match one that starts with a `VAR=value` assignment.

- `./gradlew quickCheck`: the inner loop, in seconds. Formats, compiles every source set, runs the JUnit tests; no game starts. The Stop hook runs it after any turn that changed code and sends you back with the first errors.
- `./gradlew build`: compile, JUnit and fuzz-input replay, server game tests, formatting, jar.
- `./gradlew runGameTest`: the server game tests alone, in a fresh world at a fixed origin, so a failure reruns as it failed. `-Pgametest=<part of the id>`, `-Pgametest_repeat=100` and `-Pgametest_origin=random` are explained in `build.gradle.kts`, in the comment starting `// Server game tests (runGameTest)`.
- `scripts/client-gametest.sh [--only=<Class>]`: the client game tests, headless; prints the `build/evidence/...` folder holding their screenshots and log. Not part of `build`.
- `./gradlew runDatagen`: regenerates `src/main/generated/` after a datagen provider changes.
- `./gradlew pitest -Ppit_target=<package>.<Class>*`: mutation testing; surviving mutants land in `build/reports/pitest/mutations.xml`.
- `./gradlew fuzzTest`: replays the saved Jazzer inputs; `-Pfuzz --tests '*<Class>FuzzTest.<method>'` searches for new failing ones. Fuzz tests and PIT run without Fabric Loader.
- To see a scene in game yourself, add it to `PlaytestClientGameTest`, which builds and screenshots every `scripts/play.sh` scene. `play.sh` itself is the human's: it never exits.

Checks that fail on their own:
- javac warnings are errors, and Error Prone with NullAway (JSpecify mode) runs in the compile. A `[NullAway]` error is a real null path: add the check, or the `org.jspecify.annotations.Nullable` it points at.
- Every game-test run ends with a log check (`check*Log` in `build.gradle.kts`) that fails on missing models, textures or blockstates, a mixin that missed its target, and an item tag without an English name.

Logs, when Gradle's output isn't enough: `build/reports/`, `build/run/gameTest/logs/latest.log`, `build/run/clientGameTest/logs/latest.log`. Search for `Caused by:`, `Registry`, and the first `ERROR` after `Principia initialized`.

## Done

A change is done when `scripts/verify.sh` ends in `RESULT: PASS`. It runs what CI runs on the checkout as it is, and first fails on build inputs CI wouldn't see (untracked, or under `src/` and gitignored: `git add` or delete them; edits to tracked files may stay uncommitted): self-checks, `build`, datagen staleness, and the client game tests when the change reaches them (`--client` forces them). Run it after `quickCheck` and the change's own tests pass.

Each kind of change also needs evidence: a test that fails without it.

| Change | Evidence |
|---|---|
| Logic that needs no game (geometry, physics, codecs) | A JUnit test; a `@FuzzTest` when the inputs are too many to list |
| Behaviour in the world | A server game test that also passes with `-Pgametest=<its id> -Pgametest_repeat=100` |
| What a client sends, or what players share | A client game test against a dedicated server (`SkyHookDedicatedServerClientGameTest`) |
| What players see | A client game test asserting the render state, with its screenshots looked at and described in the PR |
| Blocks, items, recipes, assets | `runDatagen` output committed, and `RegistrationGameTest` passing |
| What is saved (block entities, entities, attachments) | A game test loading the previous format, and one placing it in a structure turned and mirrored |

A failing test is evidence too: fix the code, or report the test as wrong. A test, saved fuzz input or screenshot template changes only together with the behaviour it pins, and the PR says so.

The PR fills in `.github/pull_request_template.md`.

## Commits

PR titles, and the commit subjects that become them, are [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) with the standard types (`feat`, `fix`, `perf`, `refactor`, `test`, `docs`, `style`, `build`, `ci`, `chore`, `revert`). PRs are squash-merged, so the title becomes the commit on `*/dev`; the `pr-title` workflow checks it.
- `feat`, `fix` and `perf` are for what a player or server owner notices: the release notes are drafted from them. Changes to tooling, tests or CI are `build`, `test`, `ci` or `chore`, even when they fix something.
- The scope names the feature or area (`sky-line`, `sky-hook`, `gametest`, `agent-guard`), or is left out.
- The description is imperative and lowercase unless it starts with a name: `fix(sky-hook): keep the rider attached across chunk borders`.
- `!` marks a change that breaks existing worlds, configs, or servers on an older version; the PR description says what to do about it.
- A revert is titled `revert: <the reverted title>`.

## Conventions

- `src/main/generated` is datagen output: change the provider and rerun `runDatagen`.
- English text is declared in Java next to what it names: a block's or item's name is an argument of its `Content` registration, other text an `English.add` constant. `en_us.json` is generated from them; other languages are hand-written files in `src/main/resources`.
- JUnit tests call `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();` before touching registries, and run without a game or a window.
- Every serverbound payload is registered with `ClientActions.register`, which runs its handler only once `ClientActions.accept(player)` passes (alive, loaded, no pending teleport). The handler then makes the feature's own checks: reach, line of sight, and `Protection.mayPlace` / `mayBreak` for anything it places or breaks outside vanilla's own block placing and mining, which claim mods watch. `mayBreak` takes every cell of one action and fires Fabric's break event once.
- Saved data is whatever a world, a structure or `/data` hands back: it carries a save version (`AnchorBlockEntity.SAVE_VERSION`), its codecs bound every size and offset they decode, and it turns with structures. A block with a direction overrides `rotate` and `mirror`; a block entity whose data holds offsets or directions implements `TurnsWithStructures`, as `AnchorBlockEntity` does, and `StructureTemplateMixin` turns that data before it loads.
- Writing or running a client game test, or recording a video of one: read `.agents/skills/mc-api-lookup/client-gametests.md` first.

Design rules for code not yet written:
- A feature keeps its Fabric-specific calls (API Lookup, Transfer, data attachments, networking registration, events) in one registration class.
- Multiplayer first: a player's own state is stored per player and synced only to them; whatever changes the world is shared server-side state.
- Interoperate through shared conventions: `c:` tags for materials, Fabric's Transfer API for moving items and fluids.
- Draw effects with vanilla render types. A custom render pipeline is a last resort, for a feature that can't work without one: shader packs (Iris) handle vanilla's types and break on unknown pipelines.
- Prefer Fabric API events and hooks, then vanilla extension points (datapacks, tags, data components), then Mixins. Write Mixins with MixinExtras (`@WrapOperation`, `@ModifyExpressionValue`, `@ModifyReturnValue`, `@Local`, `@WrapMethod`).

## Agent tooling

For when you change the agent setup itself:
- This is the only instruction file, read natively by Codex and by Claude Code (2.1.277+). Any `CLAUDE.md`, `.claude/CLAUDE.md` or `CLAUDE.local.md` in the checkout or above it makes Claude Code skip it.
- Skills live in `.agents/skills/` (Codex's location), each with a symlink in `.claude/skills/`; `scripts/check-skills.sh` checks the pairing in CI.
- Both tools share the hooks in `scripts/`: `session-start.sh`, `stop-check.sh`, and `agent-guard.sh`, which blocks by text match what nothing else stops: `gradlew --stop`, killing Gradle or Java (both take down every worktree's builds), and calling `xvfb-run` directly; its tests are `scripts/test-agent-guard.sh`. The rest needs no guard: GitHub's `release` environment and `v*` tag ruleset protect releases (header of `.github/workflows/release.yml`), and `runClient`, `runServer` and `scripts/play.sh` refuse to start under an agent's environment variables (`agentVariable` in `build.gradle.kts`). Command permissions are kept in step in `.claude/settings.json` and `.codex/rules/principia.rules`.
- With the jdtls plugin on, hover gives a Minecraft or Fabric call's exact signature. It can't open library classes; read those in `.references/sources/`.
