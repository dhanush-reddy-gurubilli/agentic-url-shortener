package com.agentic.shortener.service;

import com.agentic.shortener.ShortenerExceptions.InvalidRequest;
import com.agentic.shortener.ShortenerProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class UrlValidator {

    static final int MAX_URL_LENGTH = 2048;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");

    private final String ownHost;
    private final Set<String> reservedAliases;

    public UrlValidator(ShortenerProperties properties) {
        this.ownHost = URI.create(properties.baseUrl()).getHost().toLowerCase(Locale.ROOT);
        this.reservedAliases = properties.reservedAliases().stream()
                .map(a -> a.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Returns the normalised URL or throws {@link InvalidRequest}. */
    public String validateTargetUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidRequest("url is required");
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new InvalidRequest("url must be at most " + MAX_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InvalidRequest("url is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new InvalidRequest("only http and https URLs can be shortened");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidRequest("url must include a host");
        }
        if (uri.getHost().toLowerCase(Locale.ROOT).equals(ownHost)) {
            throw new InvalidRequest("url must not point at this shortener (redirect loop)");
        }
        return trimmed;
    }

    public void validateAlias(String alias) {
        if (!ALIAS.matcher(alias).matches()) {
            throw new InvalidRequest("customAlias must be 3-32 characters of letters, digits, '-' or '_'");
        }
        if (reservedAliases.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new InvalidRequest("customAlias '" + alias + "' is reserved");
        }
    }
}
