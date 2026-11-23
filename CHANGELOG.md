# Changelog

Notable changes, newest first. This file starts with the security and modernisation work on this fork; the original project's history is in git.

## Unreleased

### Added
- **Live timing** page: positions, laps, sector times, last and best lap of the running session, for everyone.
- **Records** page: career totals per driver and the lap record at each track, across all championships.
- **Penalties** on finished races (time penalties and disqualifications), re-ordering the result and the points; **video links** (YouTube/Twitch) on races.
- **Admins and stewards**: the owner gives friends their own sign-in with a role (admin or steward); sessions follow role changes, password resets and removal at once.
- **Presets**: save the next-session settings under a name and apply them with one click.
- **Whitelist** editor and **game server log** viewer in the admin tools.
- Optional **`ams2_stats`** import (`RM_STATS_SOURCE=ams2_stats`): sector times, ideal lap, lap-time chart, DNF/DSQ handling. Disqualified drivers and drivers who retired before 90% of the winner's distance score no points.
- Championship rules (dropped rounds, point adjustments, teams), voided races, CSV export, driver pages, fastest laps table, shareable race pages.
- Admin tools: server.cfg form, rotation builder, next-session settings, ban list, balance of performance, activity log, password change. Live session panel with kick, temporary ban and chat. Discord notifications.
- Docker: optional build target that downloads the game server into the image (`docker/build-with-server.sh`, Steam credentials as BuildKit secrets).
- Docker: one container with Wine and the game server, or Race Manager against a game server that runs elsewhere (`RM_GAMESERVER_CONTROL=external`). `docker/backup-mongo.sh` and `docker/restore-mongo.sh`.
- Lightweight Race Manager image for a game server on a NAS (`race_mgr-external`), user and image settings in the NAS compose file, Synology notes.
- GitHub workflow that builds and checks the Docker image on every push and publishes it (without game server files) on a version tag.
- End-to-end tests (`e2e/`) and a CI workflow. The README screenshots come from them (`E2E_SCREENSHOTS=folder npm test`).

### Changed
- Spring Boot 4, Java 21, React 19, MUI 9, Vite 8; all dependencies updated.
- Admin login uses an HttpOnly session cookie with CSRF protection, a lockout after repeated failures and a password that can be changed in the UI. Race Manager listens on `127.0.0.1` unless told otherwise; every `/api` route not listed as public is refused.

### Fixed
- Races imported from `ams2_stats` were hidden from the leaderboard by the AI-strength filter (the addon doesn't record it).
- The game server hangs up on HTTP/2 upgrade requests; Race Manager now speaks HTTP/1.1 to it.
- The ban list uses the game server's real `[ ... ]` file layout.
