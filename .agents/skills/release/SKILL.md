---
name: release
description: Prepare a Principia release: version bump, changelog, checks and tag.
disable-model-invocation: true
---

# Cut a release

Pushing the tag publishes to Modrinth, CurseForge and GitHub.

1. Confirm the target version with the human (SemVer; `0.x` releases are published as beta).
2. On a release branch (`<mc_version>/release-x.y.z`, from the up-to-date `<mc_version>/dev`):
   - In `CHANGELOG.md`, add a `## [x.y.z]` section above the previous version's. Draft it from the Summary of every PR merged since the last release tag (`gh pr list --state merged --base <mc_version>/dev --search "merged:>=<date of that tag>" --json number,title,body`; all of them for the first release). The notes are for players, not a commit log: give the draft to the human to rewrite.
   - Set `mod_version=x.y.z` in `gradle.properties`.
3. Run `./gradlew build`, then `./gradlew publishMods` (a dry run: check the printed version and changelog).
4. Run `scripts/client-gametest.sh --production` against the built jar and give the human the screenshot path it prints; they look at the screenshots.
5. Commit (`Release x.y.z`), push, and open a PR into `<mc_version>/dev`. The human merges it.
6. After the merge, show the human the exact commands and let them run them. Tags carry the Minecraft version, because each `<mc_version>/dev` branch releases separately:
   ```bash
   git switch <mc_version>/dev && git pull && git tag v<mod_version>+<mc_version> && git push origin v<mod_version>+<mc_version>
   ```
   The `release` workflow verifies that the tag matches `v<mod_version>+<minecraft version>` and is on `<mc_version>/dev`, runs the build and the production client game tests, and publishes.
