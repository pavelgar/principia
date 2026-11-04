pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	// Lets Gradle download a matching JDK (see gradle/gradle-daemon-jvm.properties) on machines that lack one.
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Should match your modid
rootProject.name = "principia"
