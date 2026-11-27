# Changelog

Notable changes, newest first. This file starts with the security and modernisation work on this fork; the original project's history is in git.

## Unreleased

### Added
- **Discord rotation notifications & live Steam reachability**: activating a rotation announces its rounds (tracks, classes, laps, AI) to Discord; server startup posts when the backend HTTP API is up, polls Steam Master Server (`GetServersAtAddress`) until listed, and shows live reachability status in the top header bar.
- **Single-player race ingestion & SimHub integration**: upload and browse single-player qualifying and race sessions (`POST /api/v1/race/ingest`, `/single-player`) using personal ingest tokens or SimHub browser SSO (`/simhub-auth`), paired with the companion [simhub-ams2-racemgr](https://github.com/flaviorodrigo/simhub-ams2-racemgr) plugins (`Agy.AMS2SpRecorder.Plugin` and `Agy.AMS2RealNames.Plugin`).
- **Start the rotation from round 1** without a restart (racemgr_rotate in use): the next session is round 1 and nobody is disconnected. Restart game server *from round 1* now also resets the new addon's position.
- The track name in the leaderboard's race list opens that race's own page (result, qualifying, sector times, lap chart); the chain icon still copies its link.
- **Rotation addon (no restart)**: the `racemgr_rotate` Lua addon replaces `sms_rotate` and picks up a changed rotation from Race Manager while the game server runs, keeping its position, so adding a track no longer needs a restart. Installed, checked and shown from the Server tab; tested on Lua 5.3 in CI.
- **Whitelist switch**: turn the game server's use of `whitelist.cfg` on or off from the Whitelist tool (one line of `server.cfg`), so only listed Steam IDs can join.
- **Default session settings (server.cfg)**: edit the `sessionAttributes` block of `server.cfg` with the full settings editor; changes values in place, adds missing ones, keeps comments and a `.bak`.
- **Live lobby vs rotation** on Race day: the live session's settings next to the rotation file on the game server (default setup plus the round), with the differences highlighted.
- **Real driver names for AI**: generic multiplayer AI names (e.g. "Jose Lopez (AI)") are turned into real drivers from `AgyAMS2RealNames.dat`, matched by car class, track and skill tier like the SimHub plugin, and still marked as AI.
- Long dropdowns in the settings editor are searchable; each setting's explanation shows on mouseover.
- **Sign in with Synology**: behind oauth2-proxy and the Synology SSO Server, DSM users and groups listed in `RM_SSO_*` sign in as admins or stewards; the ID token is checked against the SSO Server's keys, issuer, audience and expiry (`docker/docker-compose.sso-proxy.yml`).
- Restart a game server that runs in its own container from *Race day*, optionally starting its rotation from round 1: Race Manager writes a signal file into the shared folder and `docker/game-server/entrypoint.sh` restarts the server (`RM_GAMESERVER_RESTART=signal`).
- Saved rotations can be changed in place (*Save changes*), copied (*Save as new*) or deleted from the Rotation builder; the active rotation can't be deleted.
- **Racing look** (on by default, toggle in the top bar): carbon and chequered-flag theme, motorsport type, position badges, a championship podium, TV timing colours, and freely licensed photos of Fittipaldi, Piquet, Senna and Schumacher in the background with an *About the images* credits page.
- Race pages open with the winner, pole and fastest lap; the leaderboard is titled with the championship; the racing look works on phones (credits in the footer there).
- Manage Server in tabs: Race day, Championships, Server, People.
- **Next session settings and presets** use the full settings editor (any setting, sent as the numbers the game server takes); **Race next** sends a rotation round to the next session, and rounds can be saved as presets (one or all).
- **Full rotation builder**: every session setting the game server describes (grouped, with the right control and the server's description), for the default setup and per round; cars per round.
- **Live timing** page: positions, laps, sector times, last and best lap of the running session, for everyone.
- **Records** page: career totals per driver and the lap record at each track, across all championships.
- **Penalties** on finished races (time penalties and disqualifications), re-ordering the result and the points; **video links** (YouTube/Twitch) on races.
- **Admins and stewards**: the owner gives friends their own sign-in with a role (admin or steward); sessions follow role changes, password resets and removal at once.
- **Presets**: save the next-session settings under a name and apply them with one click.
- **Whitelist** editor and **game server log** viewer in the admin tools.
- Optional **`ams2_stats`** import (`RM_STATS_SOURCE=ams2_stats`): sector times, ideal lap, lap-time chart, DNF/DSQ handling. Disqualified drivers and drivers who retired before 90% of the winner's distance score no points.
- Championship rules (dropped rounds, point adjustments, teams), voided races, CSV export, driver pages, fastest laps table, shareable race pages.
- Admin tools: server.cfg form, rotation builder, next-session settings, ban list, balance of performance, activity log, password change. Live session panel with kick, temporary ban and chat. Discord notifications.
- Docker: optional build target that downloads the game server into the image (`docker/build-with-server.sh`). SteamCMD logs in anonymously (a Steam account is optional, as BuildKit secrets).
- Docker: one container with Wine and the game server, or Race Manager against a game server that runs elsewhere (`RM_GAMESERVER_CONTROL=external`). `docker/backup-mongo.sh` and `docker/restore-mongo.sh`.
- Lightweight Race Manager image for a game server on a NAS (`race_mgr-external`), user and image settings in the NAS compose file, Synology notes.
- GitHub workflow that builds and checks the Docker image on every push and publishes it (without game server files) on a version tag.
- End-to-end tests (`e2e/`) and a CI workflow. The README screenshots come from them (`E2E_SCREENSHOTS=folder npm test`).

### Changed
- Spring Boot 4, Java 21, React 19, MUI 9, Vite 8; all backend and frontend dependencies bumped with all known security vulnerabilities resolved as of the release date (November 2026).
- Admin login uses an HttpOnly session cookie with CSRF protection, a lockout after repeated failures and a password that can be changed in the UI. Race Manager listens on `127.0.0.1` unless told otherwise; every `/api` route not listed as public is refused.

### Fixed
- The bundled `ams2_stats` addon no longer saves empty sessions (the placeholder it makes at load, a stage nobody drove); they only filled its list of kept sessions. Update the addon from the Server tab and restart the game server to get it. CI's Lua tests for it pass again.
- **AI names and cars on races whose results carry car ids the game server's list doesn't know** (the car column was blank and the AI kept their generic names): the converter now falls back to the session's class whatever its sign (class ids are signed, `F-V8_Gen2` is negative), and shows the car of the participant in the same slot.
- The live strip and live timing lost the track for any track whose id is negative (about half of them).
- Saving penalties on a race could write the converted AI names into the database; the check now runs on a copy.
- The rotation file keeps setting names (`"Manual"`, `"FULL"`, `"ABS_ALLOWED,SC_ALLOWED"`) again: the rotation addon turns them into numbers itself, and a file written while the game server's lists couldn't be fetched no longer mixes names and numbers.
- Leaderboard crashes on races with missing values; better Flags and Fill-with-AI defaults in the rotation builder and presets.
- *Restart game server* said the server hadn't answered when the restart was simply quicker than the page's check; it now follows the request being picked up by the game server's entrypoint, and says so if it never is.
- A rotation saved in the Rotation builder only appeared in the Rotation dropdown after leaving the page.
- *Set active* also adds the rotation to the championship, so the leaderboard lists it under that championship (only *Update Server* did before).
- A car class in a round or preset was left out when sent to the game server (*Race next*, presets): car classes were looked up by the wrong field.
- Car and track lists are alphabetical and show the game's name with the id rotation files use ("Formula V8 Gen2 · F-V8_Gen2").
- First setup: the leaderboard said "Error Loading Config"; it now says *Not set up yet* and what to do. **Set active** chooses the championship and rotation without rewriting the game server's rotation file.
- Importing the rotation addon's live file (`lua_config/sms_rotate_config.json`) said "not valid JSON": the Rotation builder now reads the server's relaxed format, and accepts a vehicle class per round.
- The NAS compose file also tried to build the image from source (Container Manager's *Build* failed with "unable to evaluate symlinks in Dockerfile path"): it now only pulls `RACEMGR_IMAGE`; building is an optional extra file.
- A rotation from a real server (grid, AI, damage and pit-stop settings, numeric weather and flag values) was refused; rotations now accept every writable session setting, and importing then saving one in the Rotation builder keeps it exactly.
- A `server.cfg` mounted into the container on its own (as on some NAS setups) could not be saved: it is now written in place.
- Race Manager's own fields of a race (database id, void, penalties, video link) are never taken from the stats file.
- Races imported from `ams2_stats` were hidden from the leaderboard by the AI-strength filter (the addon doesn't record it).
- The game server hangs up on HTTP/2 upgrade requests; Race Manager now speaks HTTP/1.1 to it.
- The ban list uses the game server's real `[ ... ]` file layout.

### Security
- A session cookie now wins over an `Authorization` header, which behind the sign-in proxy carries the Synology token.
- The CSRF cookie is `SameSite=Strict`, and `Secure` with `RM_COOKIE_SECURE=true`, like the session cookies; that setting also sends Strict-Transport-Security for the Race Manager host name.
