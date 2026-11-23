# Race Manager web UI

React 17 + TypeScript + Redux Toolkit/Saga + MUI, built with [Vite](https://vite.dev/).

## Scripts

Run from this directory (Node 22+; the Maven build downloads its own Node).

| Command | What it does |
|---|---|
| `npm install` | Install dependencies |
| `npm start` | Dev server at http://localhost:3000. `/api` is proxied to the backend on `http://localhost:8080` (see `vite.config.ts`) |
| `npm run build` | Typecheck, lint, then build into `build/` (Maven copies that into the jar) |
| `npm run lint` | ESLint only |
| `npm test` | Vitest unit tests |

## Notes

- Admin login is an HttpOnly session cookie set by the server, so the UI never sees the token. The readable
  `RM-SESSION` cookie only tells the UI that a session exists. See `src/api/RaceMgrApi.tsx`.
- The server sends a strict Content-Security-Policy (`script-src 'self'`, no inline scripts, no external hosts).
  Keep scripts, fonts and images bundled; don't add CDN links to `index.html`.
- Icons are imported from `@mui/icons-material/<Name>`; `vite.config.ts` aliases them to the ES module build.
- Light and dark colour schemes live in `src/theme.ts` (MUI colour schemes + CSS variables). The default follows the
  system setting; the app bar toggle overrides it and MUI stores the choice in `localStorage`.
- Below the `md` breakpoint the navigation becomes an overlay drawer. Use `sx` breakpoints for new responsive layout.
