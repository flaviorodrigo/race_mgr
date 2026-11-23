package com.coop.racemgr;

import com.coop.racemgr.repositories.RaceMgrConfigRepository;
import com.coop.racemgr.repositories.RaceRepository;
import com.coop.racemgr.repositories.RaceRotationRepository;
import com.coop.racemgr.repositories.RaceSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "RM_JWT_SECRET=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "RM_ADMIN_USER=admin",
        "RM_ADMIN_PASSWORD=correct-horse-battery",
        "spring.data.mongodb.auto-index-creation=false",
        // Never connected to: the repositories are mocked
        "spring.mongodb.uri=mongodb://localhost:27017/racemgr_test",
        "RM_GAMESERVER_HOST=localhost",
        "RM_GAMESERVER_PORT=1"
})
@AutoConfigureMockMvc
class RacemgrApplicationTests {
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
    private com.coop.racemgr.audit.AuditEventRepository auditEventRepository;
    @MockitoBean
    private com.coop.racemgr.repositories.AdminCredentialRepository adminCredentialRepository;

    private static final String LOGIN_OK = "{\"username\":\"admin\",\"password\":\"correct-horse-battery\"}";
    private static final String LOGIN_BAD = "{\"username\":\"admin\",\"password\":\"wrong\"}";

    // Real browser flow: GET /api sets the XSRF-TOKEN cookie, which is echoed back in a header.
    // (Not csrf(): that swaps the shared CsrfFilter's token repository for every later test.)
    private Cookie xsrfCookie() throws Exception {
        var res = mvc.perform(get("/api")).andExpect(status().isOk()).andReturn().getResponse();
        Cookie xsrf = res.getCookie("XSRF-TOKEN");
        if (xsrf == null) {
            // Newer Spring Security versions send the cookie as a Set-Cookie header
            String header = res.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("XSRF-TOKEN=")).findFirst().orElse(null);
            assertThat(header).as("XSRF-TOKEN cookie").isNotNull();
            xsrf = new Cookie("XSRF-TOKEN", header.substring("XSRF-TOKEN=".length(), header.indexOf(';')));
        }
        return xsrf;
    }

    private MockHttpServletRequestBuilder loginRequest(String body) throws Exception {
        Cookie xsrf = xsrfCookie();
        return post("/api/v1/authenticate").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private int login(String body, String remoteAddr) throws Exception {
        return mvc.perform(loginRequest(body).with(r -> {
                    r.setRemoteAddr(remoteAddr);
                    return r;
                })).andReturn().getResponse().getStatus();
    }

    @Test
    void adminPathsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/admin/anything")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/anything").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unlistedApiPathsAreDeniedByDefault() throws Exception {
        mvc.perform(get("/api/v1/some/new/endpoint")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpointsStayPublic() throws Exception {
        mvc.perform(get("/api")).andExpect(status().isOk());
        // Reaches the controller (no config document in the mocked repository)
        mvc.perform(get("/api/v1/config")).andExpect(status().isNotFound());
    }

    @Test
    void validTokenReachesAdminPaths() throws Exception {
        MvcResult res = mvc.perform(loginRequest(LOGIN_OK)).andExpect(status().isOk()).andReturn();
        String token = new tools.jackson.databind.json.JsonMapper()
                .readTree(res.getResponse().getContentAsString()).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        // Authenticated: passes security and falls through to "no such handler" instead of 401
        int code = mvc.perform(get("/api/v1/admin/anything").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(code).isNotIn(401, 403);
    }

    @Test
    void browserSessionUsesAnHttpOnlyCookieAndLogoutClearsIt() throws Exception {
        var res = mvc.perform(loginRequest(LOGIN_OK)).andExpect(status().isOk()).andReturn().getResponse();
        var setCookies = res.getHeaders("Set-Cookie");
        String jwt = setCookies.stream().filter(h -> h.startsWith("JWT-TOKEN=")).findFirst().orElseThrow();
        String indicator = setCookies.stream().filter(h -> h.startsWith("RM-SESSION=")).findFirst().orElseThrow();
        assertThat(jwt).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/").contains("Max-Age=");
        assertThat(indicator).doesNotContain("HttpOnly").contains("SameSite=Strict");

        // The cookie alone authenticates admin requests (no Authorization header)
        Cookie session = new Cookie("JWT-TOKEN", jwt.substring("JWT-TOKEN=".length(), jwt.indexOf(';')));
        int code = mvc.perform(get("/api/v1/admin/anything").cookie(session)).andReturn().getResponse().getStatus();
        assertThat(code).isNotIn(401, 403);

        // ...but a cookie-authenticated state-changing request still needs the CSRF token
        mvc.perform(post("/api/v1/admin/gameserver").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"nope\"}")).andExpect(status().isForbidden());

        // The CSRF token must survive authenticated requests: the SPA fetches GET /api right before every
        // state-changing call, and that request now carries the session cookie.
        Cookie xsrfBefore = xsrfCookie();
        var authedGet = mvc.perform(get("/api").cookie(session, xsrfBefore)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(authedGet.getHeaders("Set-Cookie")).noneMatch(h -> h.startsWith("XSRF-TOKEN=;") || h.contains("Max-Age=0"));
        mvc.perform(post("/api/v1/admin/gameserver").cookie(session, xsrfBefore).header("X-XSRF-TOKEN", xsrfBefore.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"bogus\"}")).andExpect(status().isBadRequest());

        // A tampered cookie is not accepted
        mvc.perform(get("/api/v1/admin/anything").cookie(new Cookie("JWT-TOKEN", session.getValue() + "x")))
                .andExpect(status().isUnauthorized());

        // Logout expires both cookies
        Cookie xsrf = xsrfCookie();
        var out = mvc.perform(post("/api/v1/logout").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(out.getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith("JWT-TOKEN=;") && h.contains("Max-Age=0"))
                .anyMatch(h -> h.startsWith("RM-SESSION=;") && h.contains("Max-Age=0"));
    }

    @Test
    void loginIsRateLimitedAfterRepeatedFailures() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login(LOGIN_BAD, "10.0.0.9")).isEqualTo(401);
        }
        // Locked out, even with the right password
        assertThat(login(LOGIN_OK, "10.0.0.9")).isEqualTo(429);
        // Other clients are unaffected
        assertThat(login(LOGIN_OK, "10.0.0.10")).isEqualTo(200);
    }

    @Test
    void paginationIsBoundedAndHasAStableShape() throws Exception {
        org.mockito.Mockito.when(raceSessionRepository.findAll(org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenAnswer(inv -> {
                    var pageable = (org.springframework.data.domain.Pageable) inv.getArgument(0);
                    // The controller must have clamped the client's page=-5&limit=999999999
                    assertThat(pageable.getPageNumber()).isZero();
                    assertThat(pageable.getPageSize()).isEqualTo(1000);
                    return new org.springframework.data.domain.PageImpl<com.coop.racemgr.model.RaceSession>(java.util.List.of(), pageable, 0);
                });

        mvc.perform(get("/api/v1/race/session?page=-5&limit=999999999")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.content").isArray())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void staticFilesGetTheirContentTypeAndTheSpaServesUnknownRoutes() throws Exception {
        // These exist once the frontend has been built into the jar (the normal ./mvnw package order)
        mvc.perform(get("/favicon.ico")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/x-icon"));
        mvc.perform(get("/some/client/route")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/assets/does-not-exist.woff2")).andExpect(status().isNotFound());
        mvc.perform(get("/../etc/passwd")).andExpect(status().is4xxClientError());
    }

    @Test
    void responsesCarryASecurityPolicy() throws Exception {
        var res = mvc.perform(get("/")).andReturn().getResponse();
        assertThat(res.getHeader("Content-Security-Policy")).contains("default-src 'self'").contains("frame-ancestors 'none'")
                .contains("object-src 'none'");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("Referrer-Policy")).isEqualTo("same-origin");
    }

    @Test
    void crossOriginRequestsGetNoCorsHeaders() throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/config").header("Origin", "https://evil.example")).andReturn();
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    // The browser flow the SPA uses: GET /api sets the XSRF-TOKEN cookie, the token is echoed in a header
    @Test
    void csrfCookieFlowWorksForTheSpa() throws Exception {
        Cookie xsrf = xsrfCookie();

        mvc.perform(post("/api/v1/authenticate").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(LOGIN_OK)).andExpect(status().isOk());

        // No header, or a wrong one, is rejected
        mvc.perform(post("/api/v1/authenticate").cookie(xsrf)
                .contentType(MediaType.APPLICATION_JSON).content(LOGIN_OK)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/authenticate").cookie(xsrf).header("X-XSRF-TOKEN", "forged")
                .contentType(MediaType.APPLICATION_JSON).content(LOGIN_OK)).andExpect(status().isForbidden());
    }

    // ---- Session scoring ----

    private Cookie adminSession() throws Exception {
        var res = mvc.perform(loginRequest(LOGIN_OK)).andExpect(status().isOk()).andReturn().getResponse();
        String jwt = res.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("JWT-TOKEN=")).findFirst().orElseThrow();
        return new Cookie("JWT-TOKEN", jwt.substring("JWT-TOKEN=".length(), jwt.indexOf(';')));
    }

    private MockHttpServletRequestBuilder adminJson(MockHttpServletRequestBuilder builder, Cookie session, String body) throws Exception {
        Cookie xsrf = xsrfCookie();
        return builder.cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void sessionsCanBeCreatedWithAScoringTable() throws Exception {
        Cookie session = adminSession();
        var captor = org.mockito.ArgumentCaptor.forClass(com.coop.racemgr.model.RaceSession.class);

        mvc.perform(adminJson(post("/api/v1/admin/race/session"), session,
                "{\"name\":\"Cup\",\"raceRotationIds\":[],\"scoring\":{\"type\":\"table\",\"positions\":[25,18,15],\"poleBonus\":1,\"fastestLapBonus\":2}}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(raceSessionRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        var saved = captor.getValue().getScoring();
        assertThat(saved.getPositions()).containsExactly(25, 18, 15);
        assertThat(saved.getPoleBonus()).isEqualTo(1);
        assertThat(saved.getFastestLapBonus()).isEqualTo(2);
    }

    @Test
    void invalidScoringIsRejected() throws Exception {
        Cookie session = adminSession();
        for (String bad : new String[]{
                "{\"type\":\"table\",\"positions\":[]}",
                "{\"type\":\"table\",\"positions\":[-1]}",
                "{\"type\":\"table\",\"positions\":[5000]}",
                "{\"type\":\"table\",\"positions\":[1],\"poleBonus\":-3}",
                "{\"type\":\"mystery\",\"positions\":[1]}"}) {
            mvc.perform(adminJson(post("/api/v1/admin/race/session"), session,
                    "{\"name\":\"Bad\",\"raceRotationIds\":[],\"scoring\":" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void updatingASessionKeepsItsScoringUnlessToldOtherwise() throws Exception {
        Cookie session = adminSession();
        var existing = new com.coop.racemgr.model.RaceSession("Cup", new java.util.ArrayList<>());
        var scoring = new com.coop.racemgr.model.ScoringConfig();
        scoring.setPositions(java.util.List.of(10, 5));
        existing.setScoring(scoring);
        org.mockito.Mockito.when(raceSessionRepository.findItemById("abc")).thenReturn(existing);

        // Rotation-only update, as the UI sends when a rotation is added
        mvc.perform(adminJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/race/session/abc"),
                session, "{\"name\":\"Cup\",\"raceRotationIds\":[\"r1\"]}")).andExpect(status().isOk());
        assertThat(existing.getScoring()).isNotNull();
        assertThat(existing.getRaceRotationIds()).containsExactly("r1");

        // Explicitly going back to the built-in formula clears it
        mvc.perform(adminJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/race/session/abc"),
                session, "{\"name\":\"Cup\",\"raceRotationIds\":[],\"scoring\":{\"type\":\"default\"}}")).andExpect(status().isOk());
        assertThat(existing.getScoring()).isNull();
    }

    // ---- Live session ----

    @Test
    void liveSessionAdminEndpointsRequireLogin() throws Exception {
        mvc.perform(get("/api/v1/admin/gameserver/session")).andExpect(status().isUnauthorized());
        Cookie xsrf = xsrfCookie();
        mvc.perform(post("/api/v1/admin/gameserver/kick").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"refid\":3}")).andExpect(status().isUnauthorized());
    }

    @Test
    void kickAndChatValidateTheirInput() throws Exception {
        Cookie session = adminSession();
        for (String bad : new String[]{"{}", "{\"refid\":-4}", "{\"refid\":99999999}", "{\"refid\":\"abc\"}",
                "{\"refid\":3,\"banSeconds\":0}", "{\"refid\":3,\"banSeconds\":-1}", "{\"refid\":3,\"banSeconds\":999999999}"}) {
            int code = mvc.perform(adminJson(post("/api/v1/admin/gameserver/kick"), session, bad)).andReturn().getResponse().getStatus();
            assertThat(code).as(bad).isIn(400, 422);
        }
        for (String bad : new String[]{"{}", "{\"message\":\"   \"}", "{\"message\":\"" + "x".repeat(201) + "\"}"}) {
            mvc.perform(adminJson(post("/api/v1/admin/gameserver/chat"), session, bad)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void publicLiveSessionFailsQuietlyWhenTheGameServerIsDown() throws Exception {
        // No game server is listening in tests: the public endpoint answers with a generic failure, no details
        var res = mvc.perform(get("/api/v1/gameserver/session")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(207);
        assertThat(res.getContentAsString()).contains("Request failed").doesNotContain("Connection").doesNotContain("127.0.0.1");
    }

    @Test
    void startStopRestartAreRefusedWhenTheGameServerIsManagedElsewhere() throws Exception {
        Cookie session = adminSession();
        System.setProperty("RM_GAMESERVER_CONTROL", "external");
        try {
            mvc.perform(adminJson(post("/api/v1/admin/gameserver"), session, "{\"status\":\"restart\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(get("/api/v1/gameserver")).andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.managed").value(false));
        } finally {
            System.clearProperty("RM_GAMESERVER_CONTROL");
        }
    }

    // ---- Small admin features ----

    @Test
    void auditLogAndNewAdminEndpointsRequireLogin() throws Exception {
        mvc.perform(get("/api/v1/admin/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/gameserver/banlist")).andExpect(status().isUnauthorized());
        Cookie xsrf = xsrfCookie();
        mvc.perform(post("/api/v1/admin/gameserver/session/next").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"RaceLength\":5}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/password").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void settingsForTheNextSessionAreValidatedBeforeAnythingIsSent() throws Exception {
        Cookie session = adminSession();
        for (String bad : new String[]{"{}", "{\"RaceLength\":0}", "{\"ServerControlsSetup\":1}", "{\"TrackId\":99999999999}", "{\"OpponentDifficulty\":101}"}) {
            int code = mvc.perform(adminJson(post("/api/v1/admin/gameserver/session/next"), session, bad)).andReturn().getResponse().getStatus();
            assertThat(code).as(bad).isIn(400, 422);
        }
    }

    @Test
    void banListEndpointsRejectBadSteamIds() throws Exception {
        Cookie session = adminSession();
        mvc.perform(adminJson(post("/api/v1/admin/gameserver/banlist"), session, "{\"steamId\":\"nope\"}")).andExpect(status().isBadRequest());
        mvc.perform(adminJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/admin/gameserver/banlist/12345"), session, "{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSingleRaceCanBeFetchedByItsHashOnly() throws Exception {
        String hash = "a".repeat(64);
        mvc.perform(get("/api/v1/race/events/not-a-hash")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/race/events/" + hash)).andExpect(status().isNotFound());

        var race = new com.coop.racemgr.model.Race();
        race.setRace_id(hash);
        race.setIndex(3);
        org.mockito.Mockito.when(raceRepository.findItemByRaceId(hash)).thenReturn(race);
        mvc.perform(get("/api/v1/race/events/" + hash)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.race_id").value(hash));
    }

    @Test
    void auditLogShowsRecentAdminActivityToAdminsOnly() throws Exception {
        Cookie session = adminSession();
        org.mockito.Mockito.when(auditEventRepository.findAllByOrderByTimeDesc(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(new com.coop.racemgr.audit.AuditEvent(java.time.Instant.parse("2026-01-01T10:00:00Z"), "admin", "kick", "player refid 7")));
        mvc.perform(get("/api/v1/admin/audit").cookie(session)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].action").value("kick"));
    }
}
