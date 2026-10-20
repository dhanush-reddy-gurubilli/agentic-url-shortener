package com.agentic.shortener.service;

import com.agentic.shortener.ShortenerProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Compliance: client IPs are only ever persisted as salted SHA-256 hashes. */
@Component
public class IpHasher {

    private final String salt;

    public IpHasher(ShortenerProperties properties) {
        this.salt = properties.ipHashSalt();
    }

    public String hash(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((salt + ":" + ip).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
