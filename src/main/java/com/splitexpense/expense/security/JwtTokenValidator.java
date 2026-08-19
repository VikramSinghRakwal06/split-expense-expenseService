package com.splitexpense.expense.security;

import com.splitexpense.expense.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Verifies access tokens minted by auth-service and turns them into an
 * {@link AuthenticatedUser}.
 *
 * <p>Named a <em>validator</em> rather than a {@code JwtService} on purpose, mirroring
 * group-service: this service is structurally incapable of signing a token. There is no
 * {@code generate} method here and there must never be one.
 *
 * <h2>What is checked</h2>
 *
 * <p>All of it happens in {@link #validate(String)}, in one parse:
 *
 * <ul>
 *   <li><strong>Signature</strong> — HMAC-SHA over the shared secret.</li>
 *   <li><strong>Expiry</strong> — enforced by JJWT against the {@code exp} claim.</li>
 *   <li><strong>Issuer</strong> — required to equal the configured value.</li>
 *   <li><strong>Token type</strong> — required to be {@code access}, so a stolen refresh
 *       token cannot be replayed here as a bearer credential.</li>
 * </ul>
 *
 * <p>No database is touched.
 */
@Service
public class JwtTokenValidator {

    /** Claim naming the token's purpose, so access and refresh cannot be swapped. */
    static final String CLAIM_TYPE = "type";

    /** Claim carrying the user's surrogate id. */
    static final String CLAIM_USER_ID = "uid";

    /** Claim carrying the authorisation role. */
    static final String CLAIM_ROLE = "role";

    static final String TYPE_ACCESS = "access";

    /**
     * Built once and reused. {@code JwtParser} is thread-safe and immutable after
     * {@code build()}.
     */
    private final JwtParser parser;

    public JwtTokenValidator(JwtProperties properties) {
        SecretKey signingKey =
                Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));

        this.parser = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer())
                .require(CLAIM_TYPE, TYPE_ACCESS)
                .build();
    }

    /**
     * Verifies a bearer token and extracts the caller's identity from it.
     *
     * @param token the raw compact JWT from the {@code Authorization} header
     * @return the caller the token asserts
     * @throws JwtException             if the signature, expiry, issuer or token type check
     *                                  fails
     * @throws IllegalArgumentException if the token is null, blank, or carries a {@code uid}
     *                                  claim that is not a UUID
     */
    public AuthenticatedUser validate(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();

        String userId = claims.get(CLAIM_USER_ID, String.class);
        if (userId == null) {
            throw new IllegalArgumentException("Token carries no " + CLAIM_USER_ID + " claim");
        }

        return new AuthenticatedUser(
                UUID.fromString(userId),
                claims.getSubject(),
                claims.get(CLAIM_ROLE, String.class));
    }
}
