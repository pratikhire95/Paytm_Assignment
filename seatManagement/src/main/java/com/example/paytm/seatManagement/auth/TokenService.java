package com.example.paytm.seatManagement.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Issues and verifies bearer tokens.
 *
 * <p>Token format: {@code base64url(payload) "." base64url(HMAC-SHA256(secret, base64url(payload)))} where
 * payload is {@code v1|<userId>|user|<expiryEpochSeconds>}.
 *
 * <p>Why not a JWT? There is no algorithm header to confuse (no "alg": "none" or HS/RS downgrade class of
 * bugs), no JSON parser on the hot path, and the format is fixed. The admin credential is a separate static
 * secret compared in constant time; admin tokens can never be minted through the API.
 *
 * <p>This stands in for a real identity provider: the service only needs "who is calling", derived from a
 * verified token. Everything downstream uses {@link Principal}, never a body field.
 */
public final class TokenService {

    /** User ids end up in logs, metrics labels and DB rows: keep them boring. */
    public static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._@:-]{0,63}$");

    private static final String HMAC = "HmacSHA256";
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final long ttlSeconds;
    private final Clock clock;
    private final byte[] adminDigest;

    public TokenService(byte[] secret, String adminToken, long ttlSeconds, Clock clock) {
        this.key = new SecretKeySpec(secret, HMAC);
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
        this.adminDigest = sha256(adminToken.getBytes(StandardCharsets.UTF_8));
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** Mints a user-role token. The caller must have validated {@code userId} against {@link #USER_ID}. */
    public String mint(String userId) {
        if (userId == null || !USER_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("invalid user id");
        }
        long exp = clock.instant().getEpochSecond() + ttlSeconds;
        String payload = B64.encodeToString(("v1|" + userId + "|user|" + exp).getBytes(StandardCharsets.UTF_8));
        return payload + "." + B64.encodeToString(hmac(payload));
    }

    /** Returns the verified principal, or null if the token is missing, malformed, forged or expired. */
    public Principal authenticate(String bearer) {
        if (bearer == null || bearer.isEmpty() || bearer.length() > MAX_TOKEN_LENGTH) {
            return null;
        }
        // Constant-time comparison of digests: neither content nor length of the admin token leaks via timing.
        if (MessageDigest.isEqual(sha256(bearer.getBytes(StandardCharsets.UTF_8)), adminDigest)) {
            return Principal.admin();
        }

        int dot = bearer.indexOf('.');
        if (dot <= 0 || dot == bearer.length() - 1 || bearer.indexOf('.', dot + 1) >= 0) {
            return null;
        }
        String payloadPart = bearer.substring(0, dot);
        String sigPart = bearer.substring(dot + 1);

        byte[] presentedSig;
        try {
            presentedSig = B64D.decode(sigPart);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // Verify the signature BEFORE looking at the payload.
        if (!MessageDigest.isEqual(hmac(payloadPart), presentedSig)) {
            return null;
        }

        String payload;
        try {
            payload = new String(B64D.decode(payloadPart), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String[] f = payload.split("\\|", -1);
        if (f.length != 4 || !"v1".equals(f[0]) || !Principal.ROLE_USER.equals(f[2])) {
            return null;
        }
        if (!USER_ID.matcher(f[1]).matches()) {
            return null;
        }
        long exp;
        try {
            exp = Long.parseLong(f[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (exp <= clock.instant().getEpochSecond()) {
            return null;
        }
        return new Principal(f[1], Principal.ROLE_USER);
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
