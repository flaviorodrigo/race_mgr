package com.coop.racemgr.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RaceNotesTest {
    @Test
    void onlyHttpsYouTubeAndTwitchLinksAreAccepted() {
        assertThat(RaceNotes.cleanVideoUrl("https://www.youtube.com/watch?v=abc")).isEqualTo("https://www.youtube.com/watch?v=abc");
        assertThat(RaceNotes.cleanVideoUrl(" https://youtu.be/abc ")).isEqualTo("https://youtu.be/abc");
        assertThat(RaceNotes.cleanVideoUrl("https://www.twitch.tv/videos/123")).isNotNull();
        assertThat(RaceNotes.cleanVideoUrl("")).isEmpty();
        assertThat(RaceNotes.cleanVideoUrl(null)).isEmpty();

        for (String bad : new String[]{"javascript:alert(1)", "data:text/html,x", "http://youtu.be/abc", "https://youtu.be.evil.example/abc",
                "https://evil.example/youtu.be", "https://user@youtu.be/abc", "https://youtu.be:8443/abc", "https://youtu.be/a b", "https://youtu.be/\"onmouseover=",
                "https://youtu.be/" + "a".repeat(300), "//youtu.be/abc", "youtu.be/abc"}) {
            assertThat(RaceNotes.cleanVideoUrl(bad)).as(bad).isNull();
        }
    }

    @Test
    void penaltiesNameADriverOfTheRaceAndStayInRange() {
        var drivers = Set.of("Ann", "Bob");
        var ok = RaceNotes.cleanPenalties(List.of(new RaceNotes.Penalty("Ann", 10, false, " track\nlimits "), new RaceNotes.Penalty("Bob", 99, true, null)), drivers);
        assertThat(ok).containsExactly(new RaceNotes.Penalty("Ann", 10, false, "track limits"), new RaceNotes.Penalty("Bob", 0, true, ""));
        assertThat(RaceNotes.cleanPenalties(List.of(), drivers)).isEmpty();

        assertThat(RaceNotes.cleanPenalties(null, drivers)).isNull();
        assertThat(RaceNotes.cleanPenalties(List.of(new RaceNotes.Penalty("Zed", 5, false, "")), drivers)).isNull();
        assertThat(RaceNotes.cleanPenalties(List.of(new RaceNotes.Penalty("Ann", 0, false, "")), drivers)).isNull();
        assertThat(RaceNotes.cleanPenalties(List.of(new RaceNotes.Penalty("Ann", null, null, "")), drivers)).isNull();
        assertThat(RaceNotes.cleanPenalties(List.of(new RaceNotes.Penalty("Ann", -5, false, "")), drivers)).isNull();
        assertThat(RaceNotes.cleanPenalties(List.of(new RaceNotes.Penalty("Ann", 3601, false, "")), drivers)).isNull();
        assertThat(RaceNotes.cleanPenalties(List.of(new RaceNotes.Penalty("Ann", 5, false, "x".repeat(121))), drivers)).isNull();
    }
}
