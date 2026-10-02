package com.example.paytm.seatManagement.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

    // Random per run: no credential-looking literals in the repository.
    private static final byte[] SECRET = randomBytes(48);
    private static final String ADMIN = "adm-" + java.util.UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static TokenService service(Instant now) {
        return new TokenService(SECRET, ADMIN, 3600, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void mintedTokenAuthenticatesAsThatUserOnly() {
        TokenService ts = service(T0);
        Principal p = ts.authenticate(ts.mint("alice@example.com"));
        assertNotNull(p);
        assertEquals("alice@example.com", p.userId());
        assertEquals(Principal.ROLE_USER, p.role());
        assertFalse(p.isAdmin());
    }

    @Test
    void tokenExpires() {
        String token = service(T0).mint("alice");
        assertNotNull(service(T0.plusSeconds(3599)).authenticate(token));
        assertNull(service(T0.plusSeconds(3600)).authenticate(token));
        assertNull(service(T0.plusSeconds(86_400)).authenticate(token));
    }

    @Test
    void anyTamperingInvalidatesTheToken() {
        TokenService ts = service(T0);
        String token = ts.mint("alice");
        int dot = token.indexOf('.');
        // flip one character of the payload
        char c = token.charAt(2);
        String payloadTampered = token.substring(0, 2) + (c == 'A' ? 'B' : 'A') + token.substring(3);
        assertNull(ts.authenticate(payloadTampered));
        // flip one character of the signature
        String sigTampered = token.substring(0, dot + 1) + (token.charAt(dot + 1) == 'A' ? 'B' : 'A')
                + token.substring(dot + 2);
        assertNull(ts.authenticate(sigTampered));
        // truncated
        assertNull(ts.authenticate(token.substring(0, token.length() - 3)));
    }

    @Test
    void tokenFromAnotherSecretIsRejected() {
        TokenService other = new TokenService(randomBytes(48), ADMIN, 3600, Clock.fixed(T0, ZoneOffset.UTC));
        assertNull(service(T0).authenticate(other.mint("alice")));
    }

    @Test
    void aProperlySignedPayloadCannotClaimTheAdminRole() throws Exception {
        // Even someone holding the signing key cannot mint admin power through the user-token path.
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("v1|mallory|admin|" + (T0.getEpochSecond() + 600)).getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        String sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        assertNull(service(T0).authenticate(payload + "." + sig));
    }

    @Test
    void adminCredentialIsExactMatchOnly() {
        TokenService ts = service(T0);
        Principal p = ts.authenticate(ADMIN);
        assertNotNull(p);
        assertTrue(p.isAdmin());
        assertNull(ts.authenticate(ADMIN + "x"));
        assertNull(ts.authenticate(ADMIN.substring(1)));
        assertNull(ts.authenticate(ADMIN.toUpperCase()));
    }

    @Test
    void garbageIsRejectedWithoutThrowing() {
        TokenService ts = service(T0);
        String[] junk = {null, "", " ", ".", "..", "a.b.c", "abc", "a.", ".b", "!!!.???", "eyJhbGciOiJub25lIn0.e30.",
            "x".repeat(600), "v1|alice|user|99999999999"};
        for (String j : junk) {
            assertNull(ts.authenticate(j), "should reject: " + j);
        }
    }

    @Test
    void userIdsAreRestrictedToBoringCharacters() {
        TokenService ts = service(T0);
        assertThrows(IllegalArgumentException.class, () -> ts.mint("a|b"));
        assertThrows(IllegalArgumentException.class, () -> ts.mint(""));
        assertThrows(IllegalArgumentException.class, () -> ts.mint("has space"));
        assertThrows(IllegalArgumentException.class, () -> ts.mint("x".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> ts.mint(null));
        assertNotNull(ts.mint("user-1_2.3:4@x"));
    }
}
