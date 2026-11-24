package com.coop.racemgr.security;

import com.coop.racemgr.audit.AuditEventRepository;
import com.coop.racemgr.model.AdminRole;
import com.coop.racemgr.repositories.AdminCredentialRepository;
import com.coop.racemgr.repositories.AdminUserRepository;
import com.coop.racemgr.repositories.RaceMgrConfigRepository;
import com.coop.racemgr.repositories.RaceRepository;
import com.coop.racemgr.repositories.RaceRotationRepository;
import com.coop.racemgr.repositories.RaceSessionRepository;
import com.coop.racemgr.repositories.SessionPresetRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "RM_JWT_SECRET=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "RM_ADMIN_USER=admin",
        "RM_ADMIN_PASSWORD=correct-horse-battery",
        "spring.data.mongodb.auto-index-creation=false",
        "spring.mongodb.uri=mongodb://localhost:27017/racemgr_test",
        "RM_GAMESERVER_HOST=localhost",
        "RM_GAMESERVER_PORT=1"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SynologySsoTests {
    static final String ISSUER = "https://nas.example:4931/webman/sso";
    static final String CLIENT = "racemgr-client";
    static final KeyPair KEYS = keys();
    static final KeyPair OTHER_KEYS = keys();

    @TestConfiguration
    static class TrustTestKey {
        @Bean
        @Primary
        SynologySso testSso() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(SynologySso.validator(ISSUER, CLIENT));
            return new SynologySso(ISSUER, CLIENT, "Alice", "bob", "racemgr-admins", "racemgr-stewards", decoder);
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RaceRepository raceRepository;
    @MockitoBean
    private RaceMgrConfigRepository raceMgrConfigRepository;
    @MockitoBean
    private RaceRotationRepository raceRotationRepository;
    @MockitoBean
    private RaceSessionRepository raceSessionRepository;
    @MockitoBean
    private AuditEventRepository auditEventRepository;
    @MockitoBean
    private SessionPresetRepository sessionPresetRepository;
    @MockitoBean
    private AdminCredentialRepository adminCredentialRepository;
    @MockitoBean
    private AdminUserRepository adminUserRepository;

    private static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String idToken(KeyPair keys, String issuer, String audience, String username, List<String> groups, Instant expires) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer).audience(audience).subject(username).claim("username", username)
                .issueTime(Date.from(Instant.now().minusSeconds(10))).expirationTime(Date.from(expires));
        if (groups != null) {
            claims.claim("groups", groups);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }

    static String idToken(String username, List<String> groups) throws Exception {
        return idToken(KEYS, ISSUER, CLIENT, username, groups, Instant.now().plusSeconds(180));
    }

    private Cookie xsrf() throws Exception {
        var res = mvc.perform(get("/api")).andReturn().getResponse();
        return res.getCookie("XSRF-TOKEN");
    }

    private MockHttpServletRequestBuilder ssoLogin(String bearer) throws Exception {
        Cookie x = xsrf();
        var request = post("/api/v1/sso/login").cookie(x).header("X-XSRF-TOKEN", x.getValue());
        return bearer == null ? request : request.header("Authorization", "Bearer " + bearer);
    }

    private Cookie session(String bearer) throws Exception {
        var res = mvc.perform(ssoLogin(bearer)).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return res.getCookie(SessionCookies.TOKEN);
    }

    @Test
    void listedDsmUsersAndGroupsGetTheirRole() throws Exception {
        Cookie alice = session(idToken("alice", null));
        String me = mvc.perform(get("/api/v1/admin/me").cookie(alice)).andReturn().getResponse().getContentAsString();
        assertThat(me).contains("synology:alice").contains("ADMIN");
        // An admin page works, and keeps working while the proxy sends a (later expired) Synology token along
        assertThat(mvc.perform(get("/api/v1/admin/audit").cookie(alice)
                .header("Authorization", "Bearer " + idToken("alice", null))).andReturn().getResponse().getStatus()).isEqualTo(200);

        Cookie bob = session(idToken("bob", List.of("users")));
        assertThat(mvc.perform(get("/api/v1/admin/gameserver/presets").cookie(bob)).andReturn().getResponse().getStatus()).isNotIn(401, 403);
        assertThat(mvc.perform(get("/api/v1/admin/audit").cookie(bob)).andReturn().getResponse().getStatus()).isEqualTo(403);

        // Groups, with or without a domain part
        Cookie carol = session(idToken("carol", List.of("racemgr-admins@nas")));
        assertThat(mvc.perform(get("/api/v1/admin/audit").cookie(carol)).andReturn().getResponse().getStatus()).isEqualTo(200);
        Cookie dave = session(idToken("dave", List.of("Racemgr-Stewards")));
        assertThat(mvc.perform(get("/api/v1/admin/audit").cookie(dave)).andReturn().getResponse().getStatus()).isEqualTo(403);

        // Nobody becomes the owner through Synology
        assertThat(mvc.perform(get("/api/v1/admin/users").cookie(alice)).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void unlistedExpiredForgedOrForeignTokensGetNoSession() throws Exception {
        // Signed in to Synology, but no Race Manager role
        assertThat(mvc.perform(ssoLogin(idToken("eve", List.of("users")))).andReturn().getResponse().getStatus()).isEqualTo(403);

        Instant later = Instant.now().plusSeconds(180);
        Instant past = Instant.now().minusSeconds(600);
        List<String> refused = List.of(
                idToken(KEYS, ISSUER, CLIENT, "alice", null, past),                        // expired
                idToken(OTHER_KEYS, ISSUER, CLIENT, "alice", null, later),                 // signed by someone else
                idToken(KEYS, "https://elsewhere/webman/sso", CLIENT, "alice", null, later), // another issuer
                idToken(KEYS, ISSUER, "another-app", "alice", null, later),                // issued to another app
                "not-a-token");
        for (String token : refused) {
            var res = mvc.perform(ssoLogin(token)).andReturn().getResponse();
            assertThat(res.getStatus()).isEqualTo(401);
            assertThat(res.getContentAsString()).contains("freshSignIn");
            assertThat(res.getCookie(SessionCookies.TOKEN)).isNull();
        }
        assertThat(mvc.perform(ssoLogin(null)).andReturn().getResponse().getStatus()).isEqualTo(401);

        // A Synology token is never a Race Manager session by itself
        assertThat(mvc.perform(get("/api/v1/admin/me").header("Authorization", "Bearer " + idToken("alice", null)))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        // and the password form can't be used to sign in as an SSO user
        Cookie x = xsrf();
        assertThat(mvc.perform(post("/api/v1/authenticate").cookie(x).header("X-XSRF-TOKEN", x.getValue())
                .contentType("application/json").content("{\"username\":\"synology:alice\",\"password\":\"\"}"))
                .andReturn().getResponse().getStatus()).isIn(400, 401);
    }

    @Test
    void theLoginPageCanTellWhetherSynologySignInIsOn() throws Exception {
        assertThat(mvc.perform(get("/api/v1/sso")).andReturn().getResponse().getContentAsString()).contains("\"enabled\":true");
    }

    @Test
    void rolesComeFromTheCurrentSettings() {
        var sso = new SynologySso(ISSUER, CLIENT, "alice", "", "", "racemgr-stewards", null);
        assertThat(sso.roleFor("ALICE", List.of())).isEqualTo(AdminRole.ADMIN);
        assertThat(sso.roleFor("zed", List.of("racemgr-stewards"))).isEqualTo(AdminRole.STEWARD);
        assertThat(sso.roleFor("zed", List.of("other"))).isNull();
        assertThat(new SynologySso("", "", "alice", "", "", "", null).roleFor("alice", List.of())).isNull();
    }
}
