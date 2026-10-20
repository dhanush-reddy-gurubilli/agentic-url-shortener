package com.agentic.shortener.service;

import com.agentic.shortener.ShortenerFixtures;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator(ShortenerFixtures.properties());

    @Test
    void codesAreTheConfiguredLengthAndBase62() {
        for (int i = 0; i < 1000; i++) {
            assertThat(generator.next()).hasSize(7).matches("[0-9A-Za-z]{7}");
        }
    }

    @Test
    void codesAreNotSequentialOrRepeating() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            seen.add(generator.next());
        }
        assertThat(seen).as("62^7 codes: 20k draws should not collide").hasSize(20_000);
    }

    @Test
    void everyCharacterOfTheAlphabetEventuallyAppears() {
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            generator.next().chars().forEach(c -> seen.add((char) c));
        }
        assertThat(seen).hasSize(62);
    }
}
