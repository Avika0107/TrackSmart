package com.ekraah.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Header shapes follow what Gmail writes on received (and auto-forwarded) mail. */
class SenderAuthTest {

    private static final String GMAIL = "mx.google.com";

    @Test
    void gmailDkimPassForRetailerIsVerified() {
        String ar = "mx.google.com; dkim=pass header.i=@flipkart.com header.s=s1 header.b=AbCd; "
                + "spf=fail (google.com: domain of x@flipkart.com does not designate 1.2.3.4) smtp.mailfrom=x@flipkart.com; "
                + "dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=flipkart.com";
        assertThat(SenderAuth.verified(ar, "flipkart.com", GMAIL)).isTrue();   // SPF fails on forwarding; DKIM carries it
    }

    @Test
    void subdomainSenderAlignsWithParentDkimDomain() {
        String ar = "mx.google.com; dkim=pass header.d=nykaa.com header.s=k1";
        assertThat(SenderAuth.verified(ar, "mail.nykaa.com", GMAIL)).isTrue();
    }

    @Test
    void forgedFromSignedByAnotherDomainIsRejected() {
        // Attacker's server signs as itself while claiming From: flipkart.com.
        String ar = "mx.google.com; dkim=pass header.i=@evil.example header.s=k; spf=pass smtp.mailfrom=a@evil.example; "
                + "dmarc=fail (p=REJECT) header.from=flipkart.com";
        assertThat(SenderAuth.verified(ar, "flipkart.com", GMAIL)).isFalse();
    }

    @Test
    void lookalikeDomainDoesNotAlign() {
        String ar = "mx.google.com; dkim=pass header.d=notflipkart.com";
        assertThat(SenderAuth.verified(ar, "flipkart.com", GMAIL)).isFalse();
    }

    @Test
    void failedOrMissingDkimIsRejected() {
        assertThat(SenderAuth.verified("mx.google.com; dkim=fail header.d=flipkart.com", "flipkart.com", GMAIL)).isFalse();
        assertThat(SenderAuth.verified("mx.google.com; spf=pass smtp.mailfrom=flipkart.com", "flipkart.com", GMAIL)).isFalse();
        assertThat(SenderAuth.verified(null, "flipkart.com", GMAIL)).isFalse();
    }

    @Test
    void verdictFromAnotherServerIsNotTrusted() {
        // A sender can write its own Authentication-Results; only the trusted receiver's counts.
        String forged = "attacker.example; dkim=pass header.d=flipkart.com";
        assertThat(SenderAuth.verified(forged, "flipkart.com", GMAIL)).isFalse();
    }

    @Test
    void noTrustedReceiverConfiguredMeansNothingIsTrusted() {
        assertThat(SenderAuth.verified("mx.google.com; dkim=pass header.d=flipkart.com", "flipkart.com", null)).isFalse();
        assertThat(SenderAuth.verified("mx.google.com; dkim=pass header.d=flipkart.com", "flipkart.com", " ")).isFalse();
    }
}
