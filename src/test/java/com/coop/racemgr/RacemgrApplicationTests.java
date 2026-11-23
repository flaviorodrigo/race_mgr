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
}
