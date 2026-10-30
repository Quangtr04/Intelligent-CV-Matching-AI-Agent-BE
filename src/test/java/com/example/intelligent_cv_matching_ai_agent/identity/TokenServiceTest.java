package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.TokenRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenServiceTest {

    private static final String TEST_SECRET = TokenService.random("") + TokenService.random("");

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = {
            "",
            " ",
            "too-short",
            "development-only-change-me-to-a-long-random-secret",
            "${APP_SECURITY_JWT_SECRET_MUST_BE_CONFIGURED}"}
    )
    void signingSecretMustBeExplicitAndStrongEnough(
        String secret) {
        TokenRepository db = mock(TokenRepository.class);
        assertThrows(IllegalArgumentException.class, () -> new TokenService(db, secret, 900, 2592000));
        verifyNoInteractions(db);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void tokenLifetimesMustBePositive(
        int seconds) {
        TokenRepository db = mock(TokenRepository.class);
        assertThrows(IllegalArgumentException.class, () -> new TokenService(db, TEST_SECRET, seconds, 2592000));
        assertThrows(IllegalArgumentException.class, () -> new TokenService(db, TEST_SECRET, 900, seconds));
        verifyNoInteractions(db);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "a.b", "a.b.c", "a.b.c.d", "r_invalid"})
    void invalidAccessTokenFormatCannotReachDatabase(
        String access) {
        assertRejectedBeforeDatabase(access);
    }

    @Test
    void refreshTokensCannotBeUsedAsBearerAccess() {
        SqlStore db = mock(SqlStore.class);
        assertUnauthorized(
            new TokenService(new TokenRepository(db), TEST_SECRET, 900, 2592000),
            TokenService.random("r_")
        );
        verifyNoInteractions(db);
    }

    @Test
    void sessionSecretsAreStoredOnlyAsHashes() {
        SqlStore db = mock(SqlStore.class);
        UUID user = UUID.randomUUID();
        when(db.optional(contains("FROM users"), eq(user))).thenReturn(Optional.of(Map.of("id", user)));
        var result = new TokenService(new TokenRepository(db), TEST_SECRET, 900, 2592000).issue(user);
        assertTrue(result.get("accessToken").toString().contains("."));
        verify(db, times(2)).insert(
            eq("refresh_tokens"),
            argThat(
                fields -> fields.get("token_hash").toString().matches("[0-9a-f]{64}")
                    && !fields.containsValue(result.get("accessToken"))
                    && !fields.containsValue(result.get("refreshToken"))
            )
        );
    }

    @Test
    void issuedAccessTokensUseHs256AndIncludeRequiredClaims() throws Exception {
        TokenRepository db = mock(TokenRepository.class);
        UUID user = UUID.randomUUID();
        when(db.findUsersByIdForUpdate(user)).thenReturn(Optional.of(Map.of("id", user)));
        Map<String, Object> result = new TokenService(db, TEST_SECRET, 900, 2592000).issue(user);
        SignedJWT access = SignedJWT.parse(result.get("accessToken").toString());
        assertEquals(JWSAlgorithm.HS256, access.getHeader().getAlgorithm());
        assertTrue(access.verify(new MACVerifier(TEST_SECRET.getBytes(StandardCharsets.UTF_8))));
        JWTClaimsSet claims = access.getJWTClaimsSet();
        assertEquals(user.toString(), claims.getSubject());
        assertFalse(claims.getJWTID().isBlank());
        assertFalse(claims.getIssueTime().toInstant().isAfter(Instant.now()));
        assertTrue(claims.getExpirationTime().toInstant().isAfter(Instant.now()));
        assertEquals("Bearer", result.get("tokenType"));
        assertEquals(900, result.get("expiresIn"));
        assertTrue(result.get("refreshToken").toString().matches("r_[A-Za-z0-9_-]{43}"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void configuredLifetimesControlJwtStoredSessionsAndResponse() throws Exception {
        TokenRepository db = mock(TokenRepository.class);
        UUID user = UUID.randomUUID();
        when(db.findUsersByIdForUpdate(user)).thenReturn(Optional.of(Map.of("id", user)));
        int accessLifetime = 37;
        int refreshLifetime = 1234;
        Instant beforeIssue = Instant.now();
        Map<String, Object> result = new TokenService(db, TEST_SECRET, accessLifetime, refreshLifetime).issue(user);
        Instant afterIssue = Instant.now();

        JWTClaimsSet claims = SignedJWT.parse(result.get("accessToken").toString()).getJWTClaimsSet();
        assertEquals(
            accessLifetime,
            Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()).getSeconds()
        );
        assertEquals(accessLifetime, result.get("expiresIn"));

        ArgumentCaptor<Map<String, Object>> saved = ArgumentCaptor.forClass((Class) Map.class);
        verify(db, times(2)).insertRefreshTokens(saved.capture());
        Map<String, Object> accessSession = saved.getAllValues().stream()
            .filter(row -> TokenService.hash(result.get("accessToken").toString()).equals(row.get("token_hash")))
            .findFirst().orElseThrow();
        Instant accessExpiry = ((OffsetDateTime) accessSession.get("expires_at")).toInstant();
        assertEquals(claims.getExpirationTime().toInstant(), accessExpiry);

        Map<String, Object> refreshSession = saved.getAllValues().stream()
            .filter(row -> TokenService.hash(result.get("refreshToken").toString()).equals(row.get("token_hash")))
            .findFirst().orElseThrow();
        Instant refreshExpiry = ((OffsetDateTime) refreshSession.get("expires_at")).toInstant();
        assertFalse(refreshExpiry.isBefore(beforeIssue.plusSeconds(refreshLifetime)));
        assertFalse(refreshExpiry.isAfter(afterIssue.plusSeconds(refreshLifetime)));
    }

    @Test
    void independentlySignedAccessTokenAuthenticatesWithMatchingStoredOwner() throws Exception {
        TokenRepository db = mock(TokenRepository.class);
        UUID user = UUID.randomUUID();
        String access = sign(validClaims(user).build());
        Map<String, Object> session = Map.of("id", user, "role", "CANDIDATE", "full_name", "Test User");
        when(db.findRefreshTokensByTokenHash(TokenService.hash(access))).thenReturn(Optional.of(session));
        assertSame(session, new TokenService(db, TEST_SECRET, 900, 2592000).authenticate(access));
        verify(db).findRefreshTokensByTokenHash(TokenService.hash(access));
    }

    @Test
    void signingSecretLengthIsMeasuredInUtf8BytesAndPreservesRawKeyEncoding() throws Exception {
        String secret = "é".repeat(16);
        TokenRepository db = mock(TokenRepository.class);
        UUID user = UUID.randomUUID();
        String access = sign(validClaims(user).build(), secret, JWSAlgorithm.HS256);
        Map<String, Object> session = Map.of("id", user);
        when(db.findRefreshTokensByTokenHash(TokenService.hash(access))).thenReturn(Optional.of(session));
        assertSame(session, new TokenService(db, secret, 900, 2592000).authenticate(access));
    }

    @Test
    void tamperedPayloadCannotAuthenticate() throws Exception {
        String access = sign(validClaims(UUID.randomUUID()).build());
        String[] parts = access.split("\\.");
        String replacement =
            new Payload(validClaims(UUID.randomUUID()).build().toJSONObject()).toBase64URL().toString();
        assertRejectedBeforeDatabase(parts[0] + "." + replacement + "." + parts[2]);
    }

    @Test
    void tokenSignedWithDifferentSecretCannotAuthenticate() throws Exception {
        String access = sign(validClaims(UUID.randomUUID()).build(), TokenService.random(""), JWSAlgorithm.HS256);
        assertRejectedBeforeDatabase(access);
    }

    @Test
    void unsupportedSigningAlgorithmCannotAuthenticate() throws Exception {
        String access = sign(validClaims(UUID.randomUUID()).build(), TEST_SECRET, JWSAlgorithm.HS512);
        assertRejectedBeforeDatabase(access);
    }

    @Test
    void signedMalformedJsonCannotAuthenticate() throws Exception {
        UUID user = UUID.randomUUID();
        String payload = "{\"sub\":\"" + user + "\",\"exp\":" + Instant.now().plusSeconds(300).getEpochSecond()
            + ",\"iat\":" + Instant.now().minusSeconds(30).getEpochSecond() + ",\"jti\":\"test\"} trailing";
        JWSObject access = new JWSObject(
            new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
            new Payload(payload)
        );
        access.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        assertRejectedBeforeDatabase(access.serialize());
    }

    @Test
    void nestedClaimsCannotStandInForRequiredTopLevelClaims() throws Exception {
        JWTClaimsSet claims =
            new JWTClaimsSet.Builder().claim("nested", validClaims(UUID.randomUUID()).build().toJSONObject()).build();
        assertRejectedBeforeDatabase(sign(claims));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub", "exp", "iat", "jti"})
    void requiredClaimsCannotBeOmitted(
        String claim)
        throws Exception {
        JWTClaimsSet claims = validClaims(UUID.randomUUID()).claim(claim, null).build();
        assertRejectedBeforeDatabase(sign(claims));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-a-uuid"})
    void subjectMustIdentifyAUuidUser(
        String subject)
        throws Exception {
        assertRejectedBeforeDatabase(sign(validClaims(UUID.randomUUID()).subject(subject).build()));
    }

    @Test
    void jwtIdCannotBeBlank() throws Exception {
        assertRejectedBeforeDatabase(sign(validClaims(UUID.randomUUID()).jwtID(" ").build()));
    }

    @Test
    void expiredAccessTokenIsRejectedWithoutClockSkewGrace() throws Exception {
        JWTClaimsSet claims =
            validClaims(UUID.randomUUID()).expirationTime(Date.from(Instant.now().minusSeconds(1))).build();
        assertRejectedBeforeDatabase(sign(claims));
    }

    @Test
    void accessTokenCannotBeIssuedInTheFuture() throws Exception {
        JWTClaimsSet claims =
            validClaims(UUID.randomUUID()).issueTime(Date.from(Instant.now().plusSeconds(60))).build();
        assertRejectedBeforeDatabase(sign(claims));
    }

    @Test
    void expirationMustBeAfterIssueTime() throws Exception {
        Instant issued = Instant.now().minusSeconds(30);
        JWTClaimsSet claims = validClaims(UUID.randomUUID()).issueTime(Date.from(issued))
            .expirationTime(Date.from(issued.minusSeconds(1))).build();
        assertRejectedBeforeDatabase(sign(claims));
    }

    @Test
    void accessTokenCannotBeUsedBeforeItsNotBeforeTime() throws Exception {
        JWTClaimsSet claims =
            validClaims(UUID.randomUUID()).notBeforeTime(Date.from(Instant.now().plusSeconds(60))).build();
        assertRejectedBeforeDatabase(sign(claims));
    }

    @Test
    void storedSessionMustBelongToJwtSubject() throws Exception {
        TokenRepository db = mock(TokenRepository.class);
        String access = sign(validClaims(UUID.randomUUID()).build());
        when(db.findRefreshTokensByTokenHash(TokenService.hash(access)))
            .thenReturn(Optional.of(Map.of("id", UUID.randomUUID())));
        assertUnauthorized(new TokenService(db, TEST_SECRET, 900, 2592000), access);
    }

    @Test
    void revokedExpiredOrUnavailableStoredSessionCannotAuthenticate() throws Exception {
        TokenRepository db = mock(TokenRepository.class);
        String access = sign(validClaims(UUID.randomUUID()).build());
        when(db.findRefreshTokensByTokenHash(TokenService.hash(access))).thenReturn(Optional.empty());
        assertUnauthorized(new TokenService(db, TEST_SECRET, 900, 2592000), access);
        verify(db).findRefreshTokensByTokenHash(TokenService.hash(access));
    }

    @Test
    void inactiveAccountCannotReceiveNewSessionsAfterRevocation() {
        SqlStore db = mock(SqlStore.class);
        UUID user = UUID.randomUUID();
        when(db.optional(anyString(), eq(user))).thenReturn(Optional.empty());
        assertEquals(
            401,
            assertThrows(
                ApiException.class,
                () -> new TokenService(new TokenRepository(db), TEST_SECRET, 900, 2592000).issue(user)
            ).status()
        );
        verify(db).optional(contains("FOR NO KEY UPDATE"), eq(user));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void refreshRechecksRevocationAfterAcquiringAccountLock() {
        SqlStore db = mock(SqlStore.class);
        UUID user = UUID.randomUUID();
        String raw = TokenService.random("r_");
        when(db.optional(startsWith("SELECT user_id FROM refresh_tokens"), eq(TokenService.hash(raw))))
            .thenReturn(Optional.of(Map.of("user_id", user)));
        when(db.optional(contains("FROM users"), eq(user))).thenReturn(Optional.of(Map.of("id", user)));
        when(db.optional(startsWith("SELECT t.id,t.user_id"), eq(TokenService.hash(raw)))).thenReturn(Optional.empty());
        assertEquals(
            401,
            assertThrows(
                ApiException.class,
                () -> new TokenService(new TokenRepository(db), TEST_SECRET, 900, 2592000).rotate(raw)
            ).status()
        );
        var order = inOrder(db);
        order.verify(db).optional(startsWith("SELECT user_id FROM refresh_tokens"), eq(TokenService.hash(raw)));
        order.verify(db).optional(contains("FOR NO KEY UPDATE"), eq(user));
        order.verify(db).optional(contains("FOR UPDATE OF t"), eq(TokenService.hash(raw)));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void lockedRefreshTokenMustMatchInitiallyResolvedOwner() {
        TokenRepository db = mock(TokenRepository.class);
        UUID owner = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        String refresh = TokenService.random("r_");
        when(db.findRefreshTokensByTokenHash2(TokenService.hash(refresh)))
            .thenReturn(Optional.of(Map.of("user_id", owner)));
        when(db.findUsersByIdForUpdate(owner)).thenReturn(Optional.of(Map.of("id", owner)));
        when(db.findRefreshTokensByTokenHashForUpdate(TokenService.hash(refresh)))
            .thenReturn(Optional.of(Map.of("id", tokenId, "user_id", UUID.randomUUID())));
        assertEquals(
            401,
            assertThrows(ApiException.class, () -> new TokenService(db, TEST_SECRET, 900, 2592000).rotate(refresh))
                .status()
        );
        verify(db, never()).updateRefreshTokensById(any(), any());
        verify(db, never()).insertRefreshTokens(anyMap());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void refreshMustConsumeExactlyOneSessionBeforeIssuingNewTokens(
        int consumed) {
        TokenRepository db = mock(TokenRepository.class);
        UUID owner = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        String refresh = TokenService.random("r_");
        when(db.findRefreshTokensByTokenHash2(TokenService.hash(refresh)))
            .thenReturn(Optional.of(Map.of("user_id", owner)));
        when(db.findUsersByIdForUpdate(owner)).thenReturn(Optional.of(Map.of("id", owner)));
        when(db.findRefreshTokensByTokenHashForUpdate(TokenService.hash(refresh)))
            .thenReturn(Optional.of(Map.of("id", tokenId, "user_id", owner)));
        when(db.updateRefreshTokensById(tokenId, owner)).thenReturn(consumed);
        assertEquals(
            401,
            assertThrows(ApiException.class, () -> new TokenService(db, TEST_SECRET, 900, 2592000).rotate(refresh))
                .status()
        );
        verify(db).updateRefreshTokensById(tokenId, owner);
        verify(db, never()).insertRefreshTokens(anyMap());
    }

    @Test
    void consumedRefreshTokenCannotBeReplayed() {
        TokenRepository db = mock(TokenRepository.class);
        UUID owner = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        String refresh = TokenService.random("r_");
        when(db.findRefreshTokensByTokenHash2(TokenService.hash(refresh)))
            .thenReturn(Optional.of(Map.of("user_id", owner)), Optional.empty());
        when(db.findUsersByIdForUpdate(owner)).thenReturn(Optional.of(Map.of("id", owner)));
        when(db.findRefreshTokensByTokenHashForUpdate(TokenService.hash(refresh)))
            .thenReturn(Optional.of(Map.of("id", tokenId, "user_id", owner)));
        when(db.updateRefreshTokensById(tokenId, owner)).thenReturn(1);
        TokenService service = new TokenService(db, TEST_SECRET, 900, 2592000);
        Map<String, Object> session = service.rotate(refresh);
        assertNotEquals(refresh, session.get("refreshToken"));
        assertTrue(session.get("refreshToken").toString().matches("r_[A-Za-z0-9_-]{43}"));
        assertEquals(401, assertThrows(ApiException.class, () -> service.rotate(refresh)).status());
        verify(db).updateRefreshTokensById(tokenId, owner);
        verify(db, times(2)).insertRefreshTokens(anyMap());
    }

    private static JWTClaimsSet.Builder validClaims(
        UUID user) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().subject(user.toString()).jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now.minusSeconds(30))).expirationTime(Date.from(now.plusSeconds(300)));
    }

    private static String sign(
        JWTClaimsSet claims)
        throws Exception {
        return sign(claims, TEST_SECRET, JWSAlgorithm.HS256);
    }

    private static String sign(
        JWTClaimsSet claims,
        String secret,
        JWSAlgorithm algorithm)
        throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).build(), claims);
        token.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
    }

    private static void assertRejectedBeforeDatabase(
        String access) {
        TokenRepository db = mock(TokenRepository.class);
        assertUnauthorized(new TokenService(db, TEST_SECRET, 900, 2592000), access);
        verifyNoInteractions(db);
    }

    private static void assertUnauthorized(
        TokenService service,
        String access) {
        assertEquals(401, assertThrows(ApiException.class, () -> service.authenticate(access)).status());
    }
}
