package com.parcelpilot.mail;

import com.parcelpilot.dto.RawEmail;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Users forward to demoinbox+ALIAS@gmail.com. The alias is resolved from
 * Delivered-To, X-Forwarded-To, X-Original-To and finally To headers.
 */
public final class AliasResolver {

    private static final Pattern PLUS_ALIAS = Pattern.compile("\\+([A-Za-z0-9._-]+)@");

    private AliasResolver() {}

    public static Optional<String> resolve(RawEmail email) {
        Optional<String> fromHeaders = Stream.of(email.deliveredTo(), email.xOriginalTo(), email.xForwardedTo(), email.to())
                .filter(Objects::nonNull)
                .map(PLUS_ALIAS::matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1).toLowerCase())
                .findFirst();
        if (fromHeaders.isPresent()) return fromHeaders;
        // Last resort: the local part of the To header may itself be the alias.
        if (email.to() != null && email.to().contains("@")) {
            String local = email.to().split("@")[0].trim().toLowerCase();
            if (!local.isBlank()) return Optional.of(local);
        }
        return Optional.empty();
    }
}
