package com.coop.racemgr;

import com.coop.racemgr.audit.AuditEventRepository;
import com.coop.racemgr.model.AdminUser;
import com.coop.racemgr.repositories.AdminCredentialRepository;
import com.coop.racemgr.repositories.AdminUserRepository;
import com.coop.racemgr.repositories.RaceMgrConfigRepository;
import com.coop.racemgr.repositories.RaceRepository;
import com.coop.racemgr.repositories.RaceRotationRepository;
import com.coop.racemgr.repositories.RaceSessionRepository;
import com.coop.racemgr.repositories.SessionPresetRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class AdminRolesTests {
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

    private static final String OWNER_PASSWORD = "correct-horse-battery";
    private static final String GOOD_PASSWORD = "a-long-enough-password";

    // The "database": the mocked repository keeps the users in a map
    private final Map<String, AdminUser> stored = new ConcurrentHashMap<>();

    @BeforeEach
    void inMemoryUsers() {
        stored.clear();
        Mockito.when(adminUserRepository.findByUsername(Mockito.anyString())).thenAnswer(i -> stored.get(i.<String>getArgument(0)));
        Mockito.when(adminUserRepository.save(Mockito.any(AdminUser.class))).thenAnswer(i -> {
            AdminUser u = i.getArgument(0);
            stored.put(u.getUsername(), u);
            return u;
        });
        Mockito.doAnswer(i -> {
            stored.remove(i.<AdminUser>getArgument(0).getUsername());
            return null;
        }).when(adminUserRepository).delete(Mockito.any(AdminUser.class));
        Mockito.when(adminUserRepository.count()).thenAnswer(i -> (long) stored.size());
        Mockito.when(adminUserRepository.findAllByOrderByUsernameAsc()).thenAnswer(i -> new ArrayList<>(stored.values()).stream()
                .sorted(java.util.Comparator.comparing(AdminUser::getUsername)).toList());
    }

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

    private Cookie login(String user, String password) throws Exception {
        var res = mvc.perform(json(post("/api/v1/authenticate"), "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}")).andReturn().getResponse();
        if (res.getStatus() != 200) {
            return null;
        }
        String jwt = res.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("JWT-TOKEN=")).findFirst().orElseThrow();
        return new Cookie("JWT-TOKEN", jwt.substring("JWT-TOKEN=".length(), jwt.indexOf(';')));
    }

    private int code(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private void add(Cookie owner, String name, String role) throws Exception {
        mvc.perform(json(post("/api/v1/admin/users"), "{\"username\":\"" + name + "\",\"password\":\"" + GOOD_PASSWORD + "\",\"role\":\"" + role + "\"}", owner))
                .andExpect(status().isOk());
    }

    @Test
    void onlyTheOwnerManagesAdminsAndInputIsChecked() throws Exception {
        mvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
        Cookie owner = login("admin", OWNER_PASSWORD);

        for (String bad : new String[]{
                "{\"username\":\"ab\",\"password\":\"" + GOOD_PASSWORD + "\",\"role\":\"STEWARD\"}",
                "{\"username\":\"has space\",\"password\":\"" + GOOD_PASSWORD + "\",\"role\":\"STEWARD\"}",
                "{\"username\":\"../x\",\"password\":\"" + GOOD_PASSWORD + "\",\"role\":\"STEWARD\"}",
                "{\"username\":\"steve\",\"password\":\"short\",\"role\":\"STEWARD\"}",
                "{\"username\":\"steve\",\"password\":\"" + GOOD_PASSWORD + "\",\"role\":\"OWNER\"}",
                "{\"username\":\"steve\",\"password\":\"" + GOOD_PASSWORD + "\",\"role\":\"root\"}",
                "{\"username\":\"steve\",\"password\":\"" + GOOD_PASSWORD + "\"}"}) {
            assertThat(code(json(post("/api/v1/admin/users"), bad, owner))).as(bad).isEqualTo(400);
        }
        // The owner's own name is taken, in any case
        assertThat(code(json(post("/api/v1/admin/users"), "{\"username\":\"ADMIN\",\"password\":\"" + GOOD_PASSWORD + "\",\"role\":\"ADMIN\"}", owner))).isEqualTo(409);

        add(owner, "Steve", "STEWARD");
        assertThat(stored).containsOnlyKeys("steve");
        assertThat(stored.get("steve").getPasswordHash()).startsWith("$2").doesNotContain(GOOD_PASSWORD);
        assertThat(code(json(post("/api/v1/admin/users"), "{\"username\":\"STEVE\",\"password\":\"" + GOOD_PASSWORD + "\",\"role\":\"ADMIN\"}", owner))).isEqualTo(409);

        mvc.perform(get("/api/v1/admin/users").cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("admin")).andExpect(jsonPath("$.data[0].role").value("OWNER"))
                .andExpect(jsonPath("$.data[1].username").value("steve")).andExpect(jsonPath("$.data[1].role").value("STEWARD"))
                .andExpect(jsonPath("$.data[1].passwordHash").doesNotExist());
        assertThat(code(json(put("/api/v1/admin/users/steve"), "{}", owner))).isEqualTo(400);
        assertThat(code(json(put("/api/v1/admin/users/nobody"), "{\"role\":\"ADMIN\"}", owner))).isEqualTo(404);
        assertThat(code(json(delete("/api/v1/admin/users/nobody"), "{}", owner))).isEqualTo(404);
        // The owner can't be edited or removed through this API: it isn't in the user store
        assertThat(code(json(delete("/api/v1/admin/users/admin"), "{}", owner))).isEqualTo(404);
        assertThat(code(json(put("/api/v1/admin/users/admin"), "{\"role\":\"STEWARD\"}", owner))).isEqualTo(404);
    }

    @Test
    void aStewardCanRunARaceDayButNotTheServer() throws Exception {
        Cookie owner = login("admin", OWNER_PASSWORD);
        add(owner, "steve", "STEWARD");
        Cookie steward = login("Steve", GOOD_PASSWORD);   // user names are not case sensitive
        assertThat(steward).isNotNull();

        mvc.perform(get("/api/v1/admin/me").cookie(steward)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("steve")).andExpect(jsonPath("$.data.role").value("STEWARD"));

        // Race-day actions get past the role check (and then fail on their own input or the missing game server)
        assertThat(code(json(post("/api/v1/admin/gameserver/kick"), "{}", steward))).isEqualTo(400);
        assertThat(code(json(post("/api/v1/admin/gameserver/chat"), "{}", steward))).isEqualTo(400);
        assertThat(code(json(post("/api/v1/admin/gameserver/session/next"), "{}", steward))).isEqualTo(400);
        assertThat(code(get("/api/v1/admin/gameserver/presets").cookie(steward))).isEqualTo(200);
        assertThat(code(json(post("/api/v1/admin/gameserver/presets/" + "a".repeat(24) + "/apply"), "{}", steward))).isEqualTo(404);
        assertThat(code(json(put("/api/v1/admin/race/events/" + "a".repeat(64) + "/penalties"), "{\"penalties\":[]}", steward))).isEqualTo(404);
        assertThat(code(json(put("/api/v1/admin/race/events/" + "a".repeat(64) + "/video"), "{\"url\":\"\"}", steward))).isEqualTo(404);
        assertThat(code(get("/api/v1/admin/gameserver/log").cookie(steward))).isNotIn(401, 403);
        assertThat(code(get("/api/v1/admin/gameserver/session").cookie(steward))).isNotIn(401, 403);

        // Everything else needs an admin
        List<MockHttpServletRequestBuilder> forbidden = List.of(
                json(post("/api/v1/admin/gameserver"), "{\"status\":\"stop\"}", steward),
                get("/api/v1/admin/gameserver/banlist").cookie(steward),
                json(post("/api/v1/admin/gameserver/banlist"), "{\"steamId\":\"76561198000000001\"}", steward),
                get("/api/v1/admin/gameserver/whitelist").cookie(steward),
                get("/api/v1/admin/gameserver/config").cookie(steward),
                json(put("/api/v1/admin/gameserver/config"), "{\"name\":\"x\"}", steward),
                get("/api/v1/admin/gameserver/performance").cookie(steward),
                json(post("/api/v1/admin/gameserver/presets"), "{\"name\":\"x\",\"settings\":{\"RaceLength\":5}}", steward),
                json(delete("/api/v1/admin/gameserver/presets/" + "a".repeat(24)), "{}", steward),
                json(post("/api/v1/admin/race/events/" + "a".repeat(64) + "/void"), "{\"voided\":true}", steward),
                json(post("/api/v1/admin/race/session"), "{\"name\":\"x\",\"raceRotationIds\":[]}", steward),
                json(put("/api/v1/admin/config"), "{}", steward),
                json(post("/api/v1/admin/race/rotation"), "{}", steward),
                json(post("/api/v1/admin/race/rotation/generate"), "{}", steward),
                get("/api/v1/admin/audit").cookie(steward),
                get("/api/v1/admin/users").cookie(steward),
                json(post("/api/v1/admin/users"), "{\"username\":\"evil\",\"password\":\"" + GOOD_PASSWORD + "\",\"role\":\"ADMIN\"}", steward),
                json(put("/api/v1/admin/users/steve"), "{\"role\":\"ADMIN\"}", steward),
                json(delete("/api/v1/admin/users/steve"), "{}", steward),
                // An endpoint nobody has written yet is admin-only
                get("/api/v1/admin/anything-new").cookie(steward));
        for (var request : forbidden) {
            assertThat(code(request)).isEqualTo(403);
        }
        assertThat(stored.get("steve").getRole()).isEqualTo("STEWARD");
    }

    @Test
    void anAdminDoesEverythingExceptManageAdmins() throws Exception {
        Cookie owner = login("admin", OWNER_PASSWORD);
        add(owner, "ann", "ADMIN");
        Cookie admin = login("ann", GOOD_PASSWORD);

        assertThat(code(json(post("/api/v1/admin/gameserver/banlist"), "{\"steamId\":\"nope\"}", admin))).isEqualTo(400);
        assertThat(code(json(post("/api/v1/admin/gameserver/presets"), "{\"name\":\"x\",\"settings\":{\"RaceLength\":5}}", admin))).isEqualTo(200);
        assertThat(code(json(post("/api/v1/admin/race/events/" + "a".repeat(64) + "/void"), "{\"voided\":true}", admin))).isEqualTo(404);
        assertThat(code(get("/api/v1/admin/audit").cookie(admin))).isEqualTo(200);
        assertThat(code(json(post("/api/v1/admin/gameserver/kick"), "{}", admin))).isEqualTo(400);

        assertThat(code(get("/api/v1/admin/users").cookie(admin))).isEqualTo(403);
        assertThat(code(json(put("/api/v1/admin/users/ann"), "{\"role\":\"OWNER\"}", admin))).isEqualTo(403);
        assertThat(code(json(delete("/api/v1/admin/users/ann"), "{}", admin))).isEqualTo(403);
    }

    @Test
    void roleChangesPasswordResetsAndRemovalTakeEffectAtOnce() throws Exception {
        Cookie owner = login("admin", OWNER_PASSWORD);
        add(owner, "steve", "STEWARD");
        Cookie steward = login("steve", GOOD_PASSWORD);
        assertThat(code(get("/api/v1/admin/gameserver/banlist").cookie(steward))).isEqualTo(403);

        // Promoted: the same session now gets in
        assertThat(code(json(put("/api/v1/admin/users/steve"), "{\"role\":\"ADMIN\"}", owner))).isEqualTo(200);
        assertThat(code(get("/api/v1/admin/gameserver/banlist").cookie(steward))).isNotIn(401, 403);
        // Demoted again
        assertThat(code(json(put("/api/v1/admin/users/steve"), "{\"role\":\"STEWARD\"}", owner))).isEqualTo(200);
        assertThat(code(get("/api/v1/admin/gameserver/banlist").cookie(steward))).isEqualTo(403);

        // A password reset signs the person out everywhere, and the old password stops working
        assertThat(code(json(put("/api/v1/admin/users/steve"), "{\"password\":\"another-long-password\"}", owner))).isEqualTo(200);
        assertThat(code(get("/api/v1/admin/me").cookie(steward))).isEqualTo(401);
        assertThat(login("steve", GOOD_PASSWORD)).isNull();
        Cookie again = login("steve", "another-long-password");
        assertThat(again).isNotNull();
        assertThat(code(get("/api/v1/admin/me").cookie(again))).isEqualTo(200);

        // Removed: the session is dead at once and the name can't sign in
        assertThat(code(json(delete("/api/v1/admin/users/steve"), "{}", owner))).isEqualTo(200);
        assertThat(code(get("/api/v1/admin/me").cookie(again))).isEqualTo(401);
        assertThat(login("steve", "another-long-password")).isNull();
        assertThat(stored).isEmpty();
    }

    @Test
    void everyoneChangesTheirOwnPasswordAndOnlyThatOne() throws Exception {
        Cookie owner = login("admin", OWNER_PASSWORD);
        add(owner, "steve", "STEWARD");
        Cookie steward = login("steve", GOOD_PASSWORD);
        Cookie otherSession = login("steve", GOOD_PASSWORD);

        // Their own current password is needed: the owner's does not work for them
        assertThat(code(json(post("/api/v1/admin/password"), "{\"currentPassword\":\"" + OWNER_PASSWORD + "\",\"newPassword\":\"yet-another-password\"}", steward))).isEqualTo(403);
        var changed = mvc.perform(json(post("/api/v1/admin/password"), "{\"currentPassword\":\"" + GOOD_PASSWORD + "\",\"newPassword\":\"yet-another-password\"}", steward)).andReturn().getResponse();
        assertThat(changed.getStatus()).isEqualTo(200);

        assertThat(code(get("/api/v1/admin/me").cookie(otherSession))).isEqualTo(401);
        assertThat(login("steve", GOOD_PASSWORD)).isNull();
        assertThat(login("steve", "yet-another-password")).isNotNull();
        // The owner's session and password are untouched
        assertThat(code(get("/api/v1/admin/me").cookie(owner))).isEqualTo(200);
        assertThat(login("admin", OWNER_PASSWORD)).isNotNull();
    }

    @Test
    void whenTheDatabaseCannotBeReadOnlyTheOwnerSignsIn() throws Exception {
        Cookie owner = login("admin", OWNER_PASSWORD);
        add(owner, "steve", "STEWARD");
        Cookie steward = login("steve", GOOD_PASSWORD);
        Mockito.when(adminUserRepository.findByUsername(Mockito.anyString())).thenThrow(new RuntimeException("database down"));

        assertThat(code(get("/api/v1/admin/me").cookie(steward))).isEqualTo(401);
        assertThat(login("steve", GOOD_PASSWORD)).isNull();
        assertThat(login("admin", OWNER_PASSWORD)).isNotNull();
    }

    @Test
    void csrfCookieIsStrictAndSecureOverHttpsWithHsts() throws Exception {
        var plain = mvc.perform(get("/api")).andReturn().getResponse();
        Cookie plainCookie = plain.getCookie("XSRF-TOKEN");
        assertThat(plainCookie.getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(plainCookie.getSecure()).isFalse();
        assertThat(plain.getHeader("Strict-Transport-Security")).isNull();

        var tls = mvc.perform(get("/api").secure(true)).andReturn().getResponse();
        Cookie tlsCookie = tls.getCookie("XSRF-TOKEN");
        assertThat(tlsCookie.getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(tlsCookie.getSecure()).isTrue();
        assertThat(tls.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000");
    }
}
