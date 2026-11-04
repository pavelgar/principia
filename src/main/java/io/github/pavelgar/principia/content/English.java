package io.github.pavelgar.principia.content;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The English text of every translation key the mod defines, declared in Java next to what it names. Datagen's
 * language provider writes it to {@code en_us.json}, the file translators work from; other languages stay hand-written
 * (or come from a translation service) under {@code src/main/resources/assets/principia/lang/}.
 *
 * <p>{@link Content} adds the names of blocks and items. Anything else (a tag, a tooltip, a screen title) is declared
 * as a constant, {@code static final String TITLE = English.add("container.principia.x", "X");}, in a class that is
 * loaded during mod initialization, or datagen won't see it.
 */
public final class English {
	private static final Map<String, String> TEXT = new LinkedHashMap<>();

	private English() {
	}

	/**
	 * Declares the English text of a translation key, and returns the key.
	 *
	 * @throws IllegalArgumentException if the key already has a text
	 */
	public static synchronized String add(String key, String text) {
		String previous = TEXT.putIfAbsent(key, text);
		if (previous != null) {
			throw new IllegalArgumentException(
					"translation key " + key + " declared twice: \"" + previous + "\" and \"" + text + "\"");
		}
		return key;
	}

	/** Every declared key and its English text, in declaration order. */
	public static synchronized Map<String, String> all() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(TEXT));
	}
}
