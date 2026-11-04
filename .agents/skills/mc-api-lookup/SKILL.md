---
name: mc-api-lookup
description: Find the real Minecraft 26.x / Fabric API classes, methods and patterns in the local decompiled sources before writing mod code. Use before writing any code that touches a net.minecraft or net.fabricmc class, or when unsure what something is called in Mojang names.
---

# Look up the real API

Training data about Minecraft modding is mostly for older versions and Yarn names; the traps are listed in `AGENTS.md`. The local sources are the ground truth for this project.

## 0. Make sure the reference tree exists and is current

```bash
cat .references/VERSIONS 2>/dev/null; grep -E '^(minecraft|fabric-api) ' gradle/libs.versions.toml
```
If it's missing or the versions differ, run `scripts/setup.sh`.

## 1. Find the class

Always give the search a path inside `.references/`: the tree is gitignored, so ripgrep (and the Grep tool, which uses it) skips it in a repo-wide search and returns nothing.

Search by file name first. Mojang names are descriptive, so guess plausible words:
```bash
find .references/sources -name '*Furnace*.java' | head
```
Search for a concept across vanilla:
```bash
grep -rl 'SoundEvents.BELL' .references/sources/minecraft | head
```
Search Fabric API for hooks and events (`...api...` packages are public; `...impl...` and `...mixin...` are internal):
```bash
grep -rn 'Event<' .references/sources/libs/net/fabricmc/fabric/api --include=*.java | grep -i 'block\|break' | head
```

## Starting points

Copy the Principia file when one exists; it is already in the house style. Roots: Fabric docs under `.references/fabric-docs/develop/`, reference mod under `.references/fabric-docs/reference/latest/src/`, vanilla under `.references/sources/minecraft/net/minecraft/`; a bare class name is Principia's, under `src/`.

| Task | Fabric doc | Reference mod file | Vanilla / Principia example |
|---|---|---|---|
| Block with its block item | `blocks/first-block.md` | `main/java/com/example/docs/block/ModBlocks.java` | `Content`; `world/level/block/Blocks.java` |
| Item | `items/first-item.md` | `main/java/com/example/docs/item/ModItems.java` | `Content`; `world/item/Items.java` |
| Creative tab | `items/custom-creative-tabs.md` | `main/java/com/example/docs/item/ModItems.java` | `PrincipiaCreativeTab` |
| Data components on items | `items/custom-data-components.md` | `main/java/com/example/docs/component/ModComponents.java` | `core/component/DataComponents.java` |
| Block entity, with container, menu and screen | `blocks/block-entities.md`, `blocks/container-menus.md` | `main/java/com/example/docs/block/entity/custom/DirtChestBlockEntity.java` | `world/level/block/entity/BarrelBlockEntity.java` |
| Recipes (datagen) | `data-generation/recipes.md` | `client/java/com/example/docs/datagen/ExampleModRecipeProvider.java` | `ModRecipeProvider` |
| English text (datagen) | `data-generation/translations.md` | `client/java/com/example/docs/datagen/ExampleModEnglishLangProvider.java` | `English`, `ModLanguageProvider` |
| Loot tables and tags (datagen) | `data-generation/loot-tables.md`, `data-generation/tags.md` | `client/java/com/example/docs/datagen/ExampleModBlockLootTableProvider.java`, `client/java/com/example/docs/datagen/ExampleModItemTagProvider.java` | `ModBlockLootTableProvider`, `ModBlockTagProvider` |
| Models and blockstates (datagen) | `data-generation/block-models.md` | `client/java/com/example/docs/datagen/ExampleModModelProvider.java` | `ModModelProvider` |
| Game test: server | `automatic-testing.md` | `gametest/java/com/example/docs/ExampleModGameTest.java` | `RegistrationGameTest` |
| Game test: client, with screenshots | `automatic-testing.md`, [client-gametests.md](client-gametests.md) | `gametest/java/com/example/docs/ExampleModClientGameTest.java` | `PrincipiaClientGameTest`, `DedicatedServerClientGameTest` |
| Mixin (MixinExtras) | `mixins/bytecode.md` | `main/java/com/example/docs/mixin/event/SheepMixin.java` | `principia.mixins.json`, `MappedRegistryMixin` (test mod); annotations in `.references/sources/libs/com/llamalad7/mixinextras/` |
| Command (Brigadier) | `commands/basics.md` | `main/java/com/example/docs/command/ExampleModCommands.java` | `server/commands/SetBlockCommand.java` |
| Networking (payloads) | `networking.md` | `main/java/com/example/docs/networking/basic/ClientboundSummonLightningPayload.java` | `network/protocol/common/custom/CustomPacketPayload.java` |

## 2. Read how vanilla does it

Find the closest vanilla feature and read its whole implementation: the class, its registration in `Blocks`/`Items`/`EntityType`/..., its data provider in `net/minecraft/data/`, and client rendering if relevant. Copy the *pattern*, not the code.

For a mixin target, read the exact method body you're injecting into and pick a stable injection point. Check the signature exactly; parameter names are real Mojang names.

## 3. Check the docs for intent (optional)

```bash
grep -ril 'keyword' .references/fabric-docs/develop | head
```
Docs code snippets live in `.references/fabric-docs/reference/latest/src` and may be one drop behind. If a snippet doesn't compile, trust the sources.

## 4. Verify by compiling

`./gradlew compileJava compileClientJava` is the fastest way to confirm a name exists. A "cannot find symbol" error means you guessed. Go back to step 1 rather than trying variations.
