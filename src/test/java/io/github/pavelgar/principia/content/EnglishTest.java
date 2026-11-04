package io.github.pavelgar.principia.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EnglishTest {
	@Test
	void declaredTextIsListedUnderItsKey() {
		String key = English.add("test.principia.english.listed", "Listed");

		assertEquals("test.principia.english.listed", key);
		assertEquals("Listed", English.all().get(key));
	}

	/** A second text for the same key would silently replace the first in en_us.json. */
	@Test
	void aKeyDeclaredTwiceIsRejected() {
		English.add("test.principia.english.twice", "First");

		assertThrows(IllegalArgumentException.class, () -> English.add("test.principia.english.twice", "Second"));
		assertEquals("First", English.all().get("test.principia.english.twice"));
	}
}
