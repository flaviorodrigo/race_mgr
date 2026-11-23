package com.coop.racemgr;

import com.coop.racemgr.audit.AuditEventRepository;
import com.coop.racemgr.model.AdminCredential;
import com.coop.racemgr.repositories.AdminCredentialRepository;
import com.coop.racemgr.repositories.RaceMgrConfigRepository;
import com.coop.racemgr.repositories.RaceRepository;
import com.coop.racemgr.repositories.RaceRotationRepository;
import com.coop.racemgr.repositories.RaceSessionRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Changes the admin password, which affects every login in the application context, so it gets its own. */
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
class AccountTests {
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
    private AdminCredentialRepository adminCredentialRepository;

    private Cookie xsrf() throws Exception {
        var res = mvc.perform(get("/api")).andReturn().getResponse();
        Cookie c = res.getCookie("XSRF-TOKEN");
        if (c != null) {
            return c;
        }
        String h = res.getHeaders("Set-Cookie").stream().filter(x -> x.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        return new Cookie("XSRF-TOKEN", h.substring("XSRF-TOKEN=".length(), h.indexOf(';')));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body, Cookie... cookies) throws Exception {
        Cookie x = xsrf();
        MockHttpServletRequestBuilder r = b.cookie(x).header("X-XSRF-TOKEN", x.getValue());
        if (cookies.length > 0) {
            r = r.cookie(cookies);
        }
        return r.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private Cookie login(String password) throws Exception {
        var res = mvc.perform(json(post("/api/v1/authenticate"), "{\"username\":\"admin\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse();
        if (res.getStatus() != 200) {
            return null;
        }
        String jwt = res.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("JWT-TOKEN=")).findFirst().orElseThrow();
        return new Cookie("JWT-TOKEN", jwt.substring("JWT-TOKEN=".length(), jwt.indexOf(';')));
    }

    private String change(Cookie session, String current, String next) throws Exception {
        var res = mvc.perform(json(post("/api/v1/admin/password"),
                "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}", session)).andReturn().getResponse();
        return res.getStatus() + (res.getStatus() == 200 ? " " + res.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("JWT-TOKEN=")).findFirst().orElse("") : "");
    }

    @Test
    void changingThePasswordWorksAndSignsEveryOtherSessionOut() throws Exception {
        Cookie phone = login("correct-horse-battery");
        Cookie laptop = login("correct-horse-battery");
        assertThat(phone).isNotNull();

        // Wrong current password, too short, same as before: all refused and nothing changes
        assertThat(change(laptop, "wrong-password-here", "a-brand-new-password")).isEqualTo("403");
        assertThat(change(laptop, "correct-horse-battery", "short")).isEqualTo("400");
        assertThat(change(laptop, "correct-horse-battery", "correct-horse-battery")).isEqualTo("400");
        assertThat(login("correct-horse-battery")).isNotNull();

        String result = change(laptop, "correct-horse-battery", "a-brand-new-password");
        assertThat(result).startsWith("200 JWT-TOKEN=");
        Cookie fresh = new Cookie("JWT-TOKEN", result.substring("200 JWT-TOKEN=".length(), result.indexOf(';')));

        // The session that changed it carries on; the old one and the old password are gone
        assertThat(mvc.perform(get("/api/v1/admin/audit").cookie(fresh)).andReturn().getResponse().getStatus()).isNotIn(401, 403);
        mvc.perform(get("/api/v1/admin/audit").cookie(phone)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audit").cookie(laptop)).andExpect(status().isUnauthorized());
        assertThat(login("correct-horse-battery")).isNull();
        assertThat(login("a-brand-new-password")).isNotNull();

        // Only a hash is stored
        var saved = org.mockito.ArgumentCaptor.forClass(AdminCredential.class);
        org.mockito.Mockito.verify(adminCredentialRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).startsWith("$2").doesNotContain("brand-new");
    }
}
