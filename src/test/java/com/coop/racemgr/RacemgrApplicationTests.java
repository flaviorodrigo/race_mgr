package com.coop.racemgr;

import com.coop.racemgr.repositories.RaceMgrConfigRepository;
import com.coop.racemgr.repositories.RaceRepository;
import com.coop.racemgr.repositories.RaceRotationRepository;
import com.coop.racemgr.repositories.RaceSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.servlet.http.Cookie;

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
        "spring.data.mongodb.uri=mongodb://localhost:27017/racemgr_test",
        "RM_GAMESERVER_HOST=localhost",
        "RM_GAMESERVER_PORT=1"
})
@AutoConfigureMockMvc
class RacemgrApplicationTests {
    @Autowired
    private MockMvc mvc;

    @MockBean
    private RaceRepository raceRepository;
    @MockBean
    private RaceMgrConfigRepository raceMgrConfigRepository;
    @MockBean
    private RaceRotationRepository raceRotationRepository;
    @MockBean
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
        String token = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(res.getResponse().getContentAsString()).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        // Authenticated: passes security and falls through to "no such handler" instead of 401
        int code = mvc.perform(get("/api/v1/admin/anything").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
        assertThat(code).isNotIn(401, 403);
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
    void paginationIsBounded() throws Exception {
        mvc.perform(get("/api/v1/race/session?page=-5&limit=999999999")).andExpect(status().is2xxSuccessful());
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
