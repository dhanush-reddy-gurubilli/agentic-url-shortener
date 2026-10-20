package com.agentic.shortener.service;

import com.agentic.shortener.ShortenerProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Random Base62 codes: not enumerable (unlike an encoded sequence). Uniqueness is enforced by the
 * database constraint; callers retry on the rare collision.
 */
@Component
public class ShortCodeGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final SecureRandom random = new SecureRandom();
    private final int length;

    public ShortCodeGenerator(ShortenerProperties properties) {
        this.length = properties.codeLength();
    }

    public String next() {
        char[] code = new char[length];
        for (int i = 0; i < length; i++) {
            code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }
}
