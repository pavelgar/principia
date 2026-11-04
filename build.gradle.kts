import net.ltgt.gradle.errorprone.errorprone

plugins {
	alias(libs.plugins.loom)
	alias(libs.plugins.mod.publish)
	alias(libs.plugins.spotless)
	alias(libs.plugins.errorprone)
	alias(libs.plugins.pitest)
}

// Not named modId: inside fabricApi { configure... { } } blocks that name means the block's own modId property.
val mainModId = "principia"
val minecraftVersion = libs.versions.minecraft.get()

version = "${providers.gradleProperty("mod_version").get()}+$minecraftVersion"
group = providers.gradleProperty("maven_group").get()

base {
	archivesName = mainModId
}

// Checks switched on for every game-test run (dev and production). See the loom block.
// Mixin's own verification, and Fabric's check for item tags without an English name, which lists each such tag.
// The check logs a warning that the game-log check below turns into a failure.
val gameTestArgs = listOf(
	"-Dmixin.debug.verify=true", "-Dmixin.checks.interfaces=true",
	"-Dfabric-tag-conventions-v2.missingTagTranslationWarning=VERBOSE",
)
val gameTestReport = layout.buildDirectory.file("test-results/gameTest/TEST-gametest.xml")

repositories {
	// Loom adds the Minecraft, Fabric and Maven Central repositories automatically.
	// Only add repositories here for other mods, and scope them with content filters.
	maven("https://maven.terraformersmc.com/releases") {
		name = "TerraformersMC"
		content { includeGroup("com.terraformersmc") }
	}
	maven("https://maven.caffeinemc.net/releases") {
		name = "CaffeineMC"
		content { includeGroup("net.caffeinemc") }
	}
	maven("https://api.modrinth.com/maven") {
		name = "Modrinth"
		content { includeGroup("maven.modrinth") }
	}
}

loom {
	splitEnvironmentSourceSets()

	// Also named in fabric.mod.json: widens and extends Minecraft's classes, at compile time and at run time.
	accessWidenerPath = file("src/main/resources/principia.classtweaker")

	mods {
		register(mainModId) {
			sourceSet(sourceSets.main.get())
			sourceSet(sourceSets.getByName("client"))
		}
	}

	// genSources (Vineflower) defaults to a 4 GB heap and one thread per CPU, which gets OOM-killed in small
	// sandboxes. Set ORG_GRADLE_PROJECT_decompiler_memory=2048 and ORG_GRADLE_PROJECT_decompiler_threads=2 there.
	decompilerOptions.named("vineflower") {
		providers.gradleProperty("decompiler_memory").orNull?.let { memory = it.toLong() }
		providers.gradleProperty("decompiler_threads").orNull?.let { maxThreads = it.toInt() }
	}

	runs.configureEach {
		// Enables full hotswap (add/remove methods, fields, classes) when the run uses the JetBrains Runtime.
		// Other JVMs don't recognise the flag, so it's ignored there.
		jvmArguments.addAll("-XX:+IgnoreUnrecognizedVMOptions", "-XX:+AllowEnhancedClassRedefinition")
	}

	// Game-test runs verify every mixin as it is applied (bytecode verifier, interface checks), so a wrong
	// injection target or an unimplemented interface method fails the run instead of only leaving a log line,
	// and list item tags that have no English name.
	runs.matching { it.name == "gameTest" || it.name == "clientGameTest" }.configureEach {
		jvmArguments.addAll(gameTestArgs)
	}

	// Server game tests (runGameTest):
	//   -Pgametest=<text>        runs only the tests whose id contains <text>. Ids are the class and method in snake
	//                            case: -Pgametest=sky_hook_game_test, -Pgametest=a_rider_at_speed_hops. A value with
	//                            `*` or `?` is a pattern for the whole id instead (sky_hook_game_test_the_server_*);
	//                            either way the namespace principia-test: is added unless the value has one.
	//   -Pgametest_repeat=N      runs each test N times, to find flaky ones: a new test should pass with N=100. Tests
	//                            that build outside their structure (chunk unloading) collide with their own copies.
	//   -Pgametest_verify        vanilla's verify: 100 times in each of the 4 rotations. Only tests written to turn
	//                            with their structure pass it; today's use world headings, so use repeat instead.
	//   -Pgametest_origin=x,z    where the tests are laid out (default 0,0; `random` for vanilla's random spot), so a
	//                            failure reruns in the same chunks. See GameTestServerMixin.
	// The results also go to build/test-results/gameTest/TEST-gametest.xml (JUnit XML).
	runs.matching { it.name == "gameTest" }.configureEach {
		providers.gradleProperty("gametest").orNull?.let { filter ->
			val wildcarded = if (filter.any { it in "*?" }) filter else "*$filter*"
			val pattern = if (":" in wildcarded) wildcarded else "principia-test:$wildcarded"
			systemProperties.put("fabric-api.gametest.filter", pattern)
		}
		if (providers.gradleProperty("gametest_verify").isPresent) {
			systemProperties.put("fabric-api.gametest.verify", "true")
		}
		providers.gradleProperty("gametest_repeat").orNull?.let { systemProperties.put("principia.gametest.repeat", it) }
		systemProperties.put("principia.gametest.origin", providers.gradleProperty("gametest_origin").getOrElse("0,0"))
		systemProperties.put("fabric-api.gametest.report-file", gameTestReport.get().asFile.absolutePath)
	}

	// runClient also loads the principia-test mod (src/gametest), for its playtest scenes: `/playtest <scene>` in
	// game, or `scripts/play.sh <scene>`, which passes -Pplaytest=<scene> to go straight into the playtest world.
	runs.named("client") {
		sourceSet = "gametest"
		providers.gradleProperty("playtest").orNull?.let { systemProperties.put("principia.playtest", it) }
	}
}

// Launch the game for manual play (runClient/runServer, also used by the IDE's run buttons) on the
// JetBrains Runtime, which Gradle downloads automatically, so the flag above takes effect.
// Game tests and CI keep the regular JDK. Set dev_jbr=false in gradle.properties to opt out.
if (providers.gradleProperty("dev_jbr").map(String::toBoolean).getOrElse(true)) {
	val jetbrainsRuntime = javaToolchains.launcherFor {
		languageVersion = JavaLanguageVersion.of(25)
		vendor = JvmVendorSpec.JETBRAINS
	}
	tasks.withType<net.fabricmc.loom.task.RunGameTask>().matching { it.name == "runClient" || it.name == "runServer" }.configureEach {
		javaLauncher = jetbrainsRuntime
	}
}

fabricApi {
	configureDataGeneration {
		client = true
		// Runs only this mod's datagen entrypoint, not those of the dev-only mods on the runtime classpath.
		modId = mainModId
		// Fails runDatagen when a registered block or item has no loot table, blockstate or model provider entry.
		strictValidation = true
	}

	configureTests {
		// Game tests live in src/gametest with their own fabric.mod.json and are never shipped.
		createSourceSet = true
		modId = "$mainModId-test"
		enableGameTests = true
		enableClientGameTests = true
		eula = true // Accepts the Minecraft EULA for the game test runs.
	}
}

// The principia-test mod (src/gametest) as a jar, for the production run tasks. Never published.
val gametestJar = tasks.register<Jar>("gametestJar") {
	from(sourceSets.named("gametest").map { it.output })
	archiveClassifier = "gametest"
	destinationDirectory = layout.buildDirectory.dir("gametest-libs")
}

dependencies {
	minecraft(libs.minecraft)
	implementation(libs.fabric.loader)
	implementation(libs.fabric.api)

	// Dev-only mods: on the runClient classpath, but not in the jar or its dependency metadata.
	localRuntime(libs.modmenu)
	localRuntime(libs.spark) // profiler: `/spark profiler start`, then `/spark profiler stop` for a report link
	if (providers.gradleProperty("dev_sodium").map(String::toBoolean).getOrElse(false)) {
		localRuntime(libs.sodium)
	}

	testImplementation(libs.fabric.loader.junit)
	testImplementation(libs.jazzer.junit)

	// Compile-time bug patterns (Error Prone) and null checking (NullAway, JSpecify mode) for our own code.
	errorprone(libs.errorprone.core)
	errorprone(libs.nullaway)

	// Mods loaded alongside ours by the production run tasks (runProductionClientGameTest).
	// The test mod must be included explicitly, otherwise the run finds no tests and "passes".
	productionRuntimeMods(libs.fabric.api)
	productionRuntimeMods(files(gametestJar))
}

// Source hygiene, mirroring Fabric API's setup: it fixes imports and whitespace but doesn't reformat code layout.
// `./gradlew spotlessApply` fixes, and `spotlessCheck` (part of `check`/`build`) fails on violations.
spotless {
	lineEndings = com.diffplug.spotless.LineEnding.UNIX
	java {
		target("src/*/java/**/*.java")
		removeUnusedImports()
		importOrder("java", "javax", "", "net.minecraft", "net.fabricmc")
		// Layout (braces, spacing, indentation, lines over 120 columns) is formatted, not just checked.
		eclipse().configFile("gradle/eclipse-formatter.properties")
		leadingSpacesToTabs()
		trimTrailingWhitespace()
		endWithNewline()
	}
}

// Unit tests see the client source set too, for client logic that needs no running game (a renderer's geometry, an
// animation's timing). Only the shipped code needs the split: common code still can't see client code.
sourceSets.test {
	val client = sourceSets.getByName("client")
	compileClasspath += client.output + client.compileClasspath
	runtimeClasspath += client.output + client.runtimeClasspath
}

// Mutation testing of the code under JUnit tests, run by hand and not part of `build`:
// `./gradlew pitest -Ppit_target=io.github.pavelgar.principia.<package>.*` changes the compiled code one small mutation
// at a time, runs the tests against each, and lists in build/reports/pitest/mutations.xml the mutants no test noticed,
// each with a description of what it changed. A class glob (`<package>.Ride*`) mutates only those classes, against all
// of their package's tests: a class's behaviour is often tested from another class's test.
pitest {
	pitestVersion = libs.versions.pitest.core.get()
	junit5PluginVersion = libs.versions.pitest.junit5.get()
	mainSourceSets = setOf(sourceSets.main.get(), sourceSets.getByName("client"))
	val target = providers.gradleProperty("pit_target").getOrElse("io.github.pavelgar.principia.*")
	targetClasses = setOf(target)
	targetTests = setOf(target.substringBeforeLast('.') + ".*")
	threads = 4
	outputFormats = setOf("HTML", "XML")
	timestampedReports = false
}

// Fabric Loader's JUnit hook starts the loader once per JVM, and PIT starts JUnit several times in each worker, so
// under PIT the tests run without it: plain class loading, no Knot, no mixins. Tests that need those fail there.
tasks.named<info.solidsoft.gradle.pitest.PitestTask>("pitest") {
	additionalClasspath.setFrom(sourceSets.test.map { set ->
		set.runtimeClasspath.filter { !it.name.startsWith("fabric-loader-junit") }
	})
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	// A failed test's message (expected and actual) in the console, not only in build/reports.
	testLogging {
		events("failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}

tasks.test {
	exclude("**/*FuzzTest*")
}

// Jazzer fuzz tests (`*FuzzTest`, part of `check`) replay the inputs saved under src/test/resources; with -Pfuzz they
// search for new ones, one test method at a time (`--tests '*<Class>.<method>'`): Jazzer fuzzes only one per run.
// -Pfuzz sets Jazzer's JAZZER_FUZZ=1 for the tests, and a JAZZER_FUZZ already in the environment still works; the
// property keeps the command a plain `./gradlew ...`, which agents' command rules can match (Codex's can't match a
// command that starts with an assignment).
// They run without Fabric Loader's JUnit hook: under Knot, Jazzer's native driver is loaded twice and crashes. So, as
// under PIT, they can't use Fabric Loader.
val fuzzTest = tasks.register<Test>("fuzzTest") {
	description = "Runs the Jazzer fuzz tests."
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath.filter { !it.name.startsWith("fabric-loader-junit") }
	include("**/*FuzzTest*")
	val fuzzProperty = providers.gradleProperty("fuzz").map { it.isEmpty() || it.toBoolean() }.orElse(false)
	if (fuzzProperty.get()) {
		environment("JAZZER_FUZZ", "1")
	}
	// A fuzzing run searches anew each time, so it is never up to date nor taken from the build cache.
	val fuzzing = providers.environmentVariable("JAZZER_FUZZ").map { it.isNotEmpty() && it != "0" }.orElse(false)
		.zip(fuzzProperty) { env, property -> env || property }
	inputs.property("fuzzing", fuzzing)
	outputs.upToDateWhen { !fuzzing.get() }
	outputs.cacheIf { !fuzzing.get() }
}

tasks.check {
	dependsOn(fuzzTest)
}

// The inner loop, in a few seconds: formats the sources, compiles every source set (main, client, test, game tests)
// with Error Prone and NullAway, and runs the JUnit tests. No game starts. scripts/stop-check.sh runs it when an agent
// finishes a turn that changed code; `build` is still the check before a change is done.
tasks.register("quickCheck") {
	description = "Formats the sources, compiles every source set and runs the JUnit tests."
	group = "verification"
	dependsOn("spotlessApply", sourceSets.map { it.classesTaskName }, tasks.test)
}
// spotlessApply rewrites sources, so it goes before anything that reads them.
tasks.withType<JavaCompile>().configureEach { mustRunAfter("spotlessApply") }

// Runs the client game tests against the built jar in a production-like environment.
// Needs a display: use scripts/client-gametest.sh --production, which provides a 24-bit Xvfb display when available.
// (Loom's own useXVFB wraps the game in plain `xvfb-run`, whose 8-bit default display breaks OpenGL on Debian/Ubuntu.)
tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runProductionClientGameTest") {
	jvmArgs.add("-Dfabric.client.gametest")
	jvmArgs.addAll(gameTestArgs)
	useXVFB = false
	runDir = layout.buildDirectory.dir("run/clientGameTestProduction")
	// Accepts the Minecraft EULA for the dedicated server that client game tests start. `eula = true` in
	// fabricApi.configureTests covers only the dev runs; without the file the server never starts.
	val eula = layout.buildDirectory.file("run/clientGameTestProduction/eula.txt")
	doFirst {
		eula.get().asFile.apply { parentFile.mkdirs(); writeText("eula=true\n") }
	}
}

// A game-test run can pass while the log shows that a model, texture or blockstate failed to load, that a
// mixin missed its target, or that an item tag has no English name. These tasks run after every game-test run
// (even a failed one) and fail on such lines, so nobody has to remember to read the log. Add a regex to gameLogAllowlist only for a line proven harmless.
val gameLogFailures = listOf(
	"Critical injection failure", "Mixin transformation of .* failed", "Mixin apply failed", "MixinApplyError",
	"InvalidInjectionException", "InvalidMixinException",
	"Failed to load blockstate definition", "Failed to load model", "Missing block model", "Missing model for variant",
	"Missing item model definitions", "Unable to bake item model", "Couldn't parse item model",
	"Missing textures in model", "Missing texture references in model", "Missing sprite", "Using missing texture",
	"Untranslated Item Tags detected",
)
val gameLogAllowlist = listOf<String>()

abstract class CheckGameLog : DefaultTask() {
	@get:Internal abstract val log: RegularFileProperty
	@get:Input abstract val failures: ListProperty<String>
	@get:Input abstract val allowlist: ListProperty<String>

	@TaskAction
	fun check() {
		val file = log.get().asFile
		if (!file.isFile) throw GradleException("No game log at $file: the run didn't get far enough to write one.")
		val failing = failures.get().map(::Regex)
		val allowed = allowlist.get().map(::Regex)
		val hits = file.readLines().filter { line -> failing.any { it.containsMatchIn(line) } && allowed.none { it.containsMatchIn(line) } }
		if (hits.isNotEmpty()) {
			throw GradleException("${hits.size} error line(s) in $file:\n" + hits.take(20).joinToString("\n") { "  $it" })
		}
	}
}

listOf("gameTest", "clientGameTest", "clientGameTestProduction").forEach { run ->
	val check = tasks.register<CheckGameLog>("check${run.replaceFirstChar(Char::uppercase)}Log") {
		log = layout.buildDirectory.file("run/$run/logs/latest.log")
		failures = gameLogFailures
		allowlist = gameLogAllowlist
	}
	val runTask = if (run == "clientGameTestProduction") "runProductionClientGameTest" else "run${run.replaceFirstChar(Char::uppercase)}"
	val log = layout.buildDirectory.file("run/$run/logs/latest.log")
	tasks.named(runTask) {
		finalizedBy(check)
		// Loom clears only clientGameTest's run directory: without this, a run that dies before logging anything
		// would be checked against the previous run's log.
		doFirst { log.get().asFile.delete() }
	}
}

tasks.named("runGameTest") {
	val report = gameTestReport
	// Each run starts from a new world: chunks that unload during a run are saved, and the next run, laid out at the
	// same origin, would otherwise find that run's blocks where it builds.
	val world = layout.buildDirectory.dir("run/gameTest/world")
	outputs.file(report) // so Gradle doesn't treat the file as stale output of another task
	doFirst {
		world.get().asFile.deleteRecursively()
		report.get().asFile.apply { delete(); parentFile.mkdirs() }
	}
}

tasks.processResources {
	val properties = mapOf(
		"version" to version,
		"minecraft_version" to minecraftVersion,
		"loader_version" to libs.versions.fabric.loader.get(),
		// The Fabric API the mod is built against, without its "+<minecraft version>": an older one lacks APIs it calls.
		"fabric_api_version" to libs.versions.fabric.api.get().substringBefore("+"),
	)
	inputs.properties(properties)

	filesMatching("fabric.mod.json") {
		expand(properties)
	}
}

// Mixin and MixinExtras injector annotations, for Error Prone's unused-code checks below.
val mixinInjectors = listOf(
	"org.spongepowered.asm.mixin.injection.Inject",
	"org.spongepowered.asm.mixin.injection.ModifyArg",
	"org.spongepowered.asm.mixin.injection.ModifyArgs",
	"org.spongepowered.asm.mixin.injection.ModifyConstant",
	"org.spongepowered.asm.mixin.injection.ModifyVariable",
	"org.spongepowered.asm.mixin.injection.Redirect",
	"com.llamalad7.mixinextras.injector.ModifyExpressionValue",
	"com.llamalad7.mixinextras.injector.ModifyReceiver",
	"com.llamalad7.mixinextras.injector.ModifyReturnValue",
	"com.llamalad7.mixinextras.injector.v2.WrapWithCondition",
	"com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod",
	"com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation",
).joinToString(",")

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
	options.encoding = "UTF-8"
	// Every javac warning is an error: a warning is easy to skim past, a failed compile isn't. "classfile" is
	// excluded: it reports annotations missing from dependencies' class files (Guava references Error Prone's
	// annotations, which Minecraft doesn't ship), which says nothing about our code.
	options.compilerArgs.addAll(listOf("-Xlint:all,-classfile", "-Werror"))
	options.errorprone {
		// NullAway reads the JSpecify @Nullable annotations Minecraft and Fabric API already carry; unannotated
		// code in our own packages is treated as non-null, so a missing null check fails the build.
		error("NullAway")
		option("NullAway:AnnotatedPackages", "io.github.pavelgar.principia")
		option("NullAway:JSpecifyMode", "true")
		// Mixin handlers are called by the woven code, not ours, and must declare the target's parameters, so their
		// injector annotations exempt them from the unused-method and unused-parameter checks.
		option("UnusedMethod:ExemptingMethodAnnotations", mixinInjectors)
		option("Unused:methodAnnotationsExemptingParameters", mixinInjectors)
	}
}

java {
	// No sources jar: it isn't published (see publishMods), so the source code isn't handed out in bulk.
	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

val releaseRun = providers.environmentVariable("GITHUB_ACTIONS").map { it == "true" }.orElse(false)
	.zip(providers.environmentVariable("GITHUB_REF_TYPE").map { it == "tag" }.orElse(false)) { ci, tag -> ci && tag }
val publishLive = providers.gradleProperty("publish_live").map { it.toBoolean() }.orElse(false)

// Publishes to every platform whose project ID is set in gradle.properties.
// Tokens come from the MODRINTH_TOKEN / CURSEFORGE_TOKEN / GITHUB_TOKEN environment variables.
publishMods {
	val modVersion = providers.gradleProperty("mod_version").get()

	file = tasks.jar.flatMap { it.archiveFile }
	displayName = "Principia $modVersion"
	version = project.version.toString()
	// The body of the "## [<mod_version>]" section of CHANGELOG.md.
	changelog = providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText.map { text ->
		val header = Regex("^## \\[${Regex.escape(modVersion)}].*$", RegexOption.MULTILINE).find(text)
			?: error("CHANGELOG.md has no '## [$modVersion]' section")
		val rest = text.substring(header.range.last + 1)
		rest.substring(0, Regex("^## ", RegexOption.MULTILINE).find(rest)?.range?.first ?: rest.length).trim()
	}
	type = when {
		"alpha" in modVersion -> ALPHA
		"beta" in modVersion || modVersion.startsWith("0.") -> BETA
		else -> STABLE
	}
	modLoaders.add("fabric")
	// Always a dry run, unless -Ppublish_live=true is passed *and* the build runs in GitHub Actions for a pushed tag:
	// the release workflow is the only place a live publish can happen, whatever a local command line or
	// gradle.properties says.
	dryRun = publishLive.zip(releaseRun) { wanted, release -> !(wanted && release) }

	providers.gradleProperty("modrinth_project_id").orNull?.takeIf(String::isNotBlank)?.let { id ->
		modrinth {
			projectId = id
			accessToken = providers.environmentVariable("MODRINTH_TOKEN")
			minecraftVersions.add(minecraftVersion)
			requires("fabric-api")
		}
	}

	providers.gradleProperty("curseforge_project_id").orNull?.takeIf(String::isNotBlank)?.let { id ->
		curseforge {
			projectId = id
			accessToken = providers.environmentVariable("CURSEFORGE_TOKEN")
			minecraftVersions.add(minecraftVersion)
			javaVersions.add(JavaVersion.VERSION_25)
			client = true
			server = true
			requires("fabric-api")
		}
	}

	providers.gradleProperty("github_repository").orNull?.takeIf(String::isNotBlank)?.let { repo ->
		github {
			repository = repo
			accessToken = providers.environmentVariable("GITHUB_TOKEN")
			commitish = providers.environmentVariable("GITHUB_SHA").orElse(minecraftVersion) // branches are named after the MC version
			tagName = "v${project.version}" // e.g. v0.1.0+26.3; each <mc_version>/dev branch releases separately
		}
	}
}

// Copies the sources jars of Fabric Loader, Fabric API, Mixin, MixinExtras and Mojang's libraries into
// build/agent-sources/ so scripts/setup.sh can unpack them for grep-based API lookup.
// Minecraft's own sources come from genSources instead.
tasks.register<Sync>("exportLibrarySources") {
	// Fabric Loader shades class-tweaker (the *.classtweaker file format), so it's on no classpath; fetch its
	// sources separately. The version is the one loader bundles; check it when bumping the loader.
	from(configurations.detachedConfiguration(dependencies.create("net.fabricmc:class-tweaker:${libs.versions.classtweaker.get()}:sources")))
	val sourcesJars = configurations.named("clientRuntimeClasspath").map { classpath ->
		classpath.incoming.artifactView {
			withVariantReselection()
			lenient(true)
			componentFilter { id ->
				id is ModuleComponentIdentifier && listOf("net.fabricmc", "com.mojang", "io.github.llamalad7").any { id.group.startsWith(it) }
			}
			attributes {
				attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
				attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
				attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
				attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
			}
		}.files
	}
	from(sourcesJars)
	into(layout.buildDirectory.dir("agent-sources"))
}

tasks.named("publishMods") {
	val wanted = publishLive
	val release = releaseRun
	doFirst {
		if (wanted.get() && !release.get()) {
			logger.warn("publish_live is set, but this isn't the tag-triggered release workflow: this is a dry run.")
		}
	}
}
