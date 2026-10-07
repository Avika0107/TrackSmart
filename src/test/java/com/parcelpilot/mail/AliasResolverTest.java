package com.parcelpilot.mail;

import com.parcelpilot.dto.RawEmail;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AliasResolverTest {

    @Test
    void resolvesFromDeliveredTo() {
        RawEmail email = new RawEmail("id", "s@flipkart.com", "demoinbox@gmail.com",
                "demoinbox+rahul55@gmail.com", null, null, "sub", "body");
        assertThat(AliasResolver.resolve(email)).contains("rahul55");
    }

    @Test
    void resolvesFromXOriginalTo() {
        RawEmail email = new RawEmail("id", "s@myntra.com", "demoinbox@gmail.com",
                null, "demoinbox+priya12@gmail.com", null, "sub", "body");
        assertThat(AliasResolver.resolve(email)).contains("priya12");
    }

    @Test
    void resolvesFromXForwardedTo() {
        RawEmail email = new RawEmail("id", "s@nykaa.com", null,
                null, null, "demoinbox+amit9@gmail.com", "sub", "body");
        assertThat(AliasResolver.resolve(email)).contains("amit9");
    }

    @Test
    void resolvesFromToHeader() {
        RawEmail email = new RawEmail("id", "s@ajio.com", "demoinbox+kavya77@gmail.com",
                null, null, null, "sub", "body");
        assertThat(AliasResolver.resolve(email)).contains("kavya77");
    }

    @Test
    void fallsBackToLocalPart() {
        RawEmail email = new RawEmail("id", "s@ajio.com", "rahul55@gmail.com",
                null, null, null, "sub", "body");
        assertThat(AliasResolver.resolve(email)).contains("rahul55");
    }

    @Test
    void emptyWhenNoAliasAnywhere() {
        RawEmail email = new RawEmail("id", "s@ajio.com", null, null, null, null, "sub", "body");
        assertThat(AliasResolver.resolve(email)).isEqualTo(Optional.empty());
    }
}
