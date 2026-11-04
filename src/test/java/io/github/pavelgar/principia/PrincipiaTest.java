package io.github.pavelgar.principia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Plain JUnit tests, run by {@code ./gradlew test} (and {@code build}) through Fabric Loader JUnit.
 * Call {@code SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();} in a {@code @BeforeAll}
 * before touching anything registry-backed (items, blocks, ItemStack, ...).
 */
class PrincipiaTest {
	@Test
	void idUsesModNamespace() {
		assertEquals("principia:example", Principia.id("example").toString());
	}
}
