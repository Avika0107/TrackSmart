package com.ekraah.service;

import com.ekraah.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates the private inbound alias (demoinbox+ALIAS@…). Anyone who knows an alias can push
 * order emails into that user's dashboard, so it must be unguessable: random, never derived from
 * the phone number (it used to be "pp" + hex(phone.hashCode()), computable from the number).
 */
@Component
public class InboundAliases {

    /** Lowercase letters and digits without look-alikes (0/o, 1/l/i), so it survives being read aloud. */
    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    static final int LENGTH = 12;   // 31^12 ≈ 7.9e17 possibilities

    private final SecureRandom random = new SecureRandom();
    private final UserRepository users;

    public InboundAliases(UserRepository users) {
        this.users = users;
    }

    /** A random alias no other user has. */
    public String newUniqueAlias() {
        while (true) {
            String candidate = random();
            if (users.findByInboundAlias(candidate).isEmpty()) return candidate;
        }
    }

    String random() {
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        return new String(out);
    }
}
