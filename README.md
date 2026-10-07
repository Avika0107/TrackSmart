# 📦 ParcelPilot — Unified Order Tracker with Weather-Based Delay Alerts

One dashboard for every Indian online-shopping order — Amazon, Flipkart, Myntra, Nykaa, AJIO — with live status and a **transparent, rule-based weather delay risk**.

> **Two honest truths baked into the product**
> 1. Retailers do **not** share order data by phone number. Phone number = login only. The **data source is email forwarding**: you forward retailer shipping emails to your personal ParcelPilot address.
> 2. Amazon's internal TBA tracking IDs and order IDs **cannot** be tracked by third-party APIs. Amazon orders are shown as **"Tracked via email only"** cards using the delivery date parsed from the email.
>
> The delay prediction is a **rule-based risk score using live weather** (thresholds visible in the UI), **not a trained ML model**. No accuracy is ever claimed.

---

## What it does

```
Gmail (demo inbox) ──IMAP──▶ ImapPoller ──▶ AliasResolver + allowlist ──▶ EmailParser
                                                                                │
Manual "add by number" ──────────────────────────────────────────────▶ OrderService
                                                                                │
                                                        TrackingProvider (mock/Ship24/17TRACK)
                                                                                │
                                                        WeatherClient (mock/Open-Meteo)
                                                                                │
                                                        DelayRiskService (rule engine)
                                                                                │
                      MongoDB ◀─────────────────────────────────────────────────┘
                          │
                     REST API (/api/**) ──▶ Static SPA (vanilla JS, served by Spring Boot)
```

- **Email pipeline**: `ImapPoller` (IMAP, every 2 min, configurable) → resolve `demoinbox+ALIAS@gmail.com` → sender allowlist → pure `EmailParser` (per-courier regexes) → idempotent upsert by `Message-ID` → plain-language audit log.
- **Tracking**: `TrackingProvider` strategy — `MockTrackingProvider` (default, deterministic, zero keys), `Ship24Provider`, `Track17Provider`, wrapped by `FallbackTrackingProvider` (falls back to mock on error / missing key / **daily budget exhausted**). Cached 3 h.
- **Weather**: `WeatherClient` strategy — `MockWeatherClient` (default, demo "stormy" toggle) or `OpenMeteoClient` (free, **no API key, non-commercial use only**). Cached 1 h; geocoding cached permanently.
- **Risk engine** (`DelayRiskService`): precipitation > 20 mm/24 h → +3; thunderstorm WMO 95–99 → +3; wind > 40 km/h → +2; visibility < 1 km → +1; heat > 42 °C → +1. `OUT_FOR_DELIVERY` halves the score; `DELIVERED` hides risk. 0–2 LOW, 3–4 MEDIUM, 5+ HIGH. Every triggered rule is listed as a reason in the UI ("Why we think this").

## Milestone map (build order)

| Milestone | Where |
|---|---|
| M1 skeleton, Mongo, auth w/ demo OTP, health | `config/`, `security/`, `model/`, `repository/` |
| M2 orders CRUD, mock tracker, email parser + simulate pipeline, audit | `service/OrderService`, `mail/EmailParser`, `mail/GmailMessageProcessor` |
| M3 SPA shell | `static/` (login, dashboard, add-modal, theme, skeletons, toasts) |
| M4 weather + risk + UI | `weather/`, `risk/`, risk chips/gauge/why-list |
| M5 real providers + fallback + budgets + jobs | `tracking/`, `scheduler/` |
| M6 IMAP poller + Gmail filter XML + forwarding-code detection | `mail/ImapPoller`, `service/SetupService` |
| M7 insights, privacy page, demo panel, README | `static/js/views/insights.js`, `privacy.js`, demo drawer |

## Tech stack

Java 17 · Spring Boot 3.5 (Web, Validation, Security, Scheduling, Data MongoDB, Actuator, Mail) · Maven · MongoDB (Atlas M0 or local Docker) · Jakarta Mail (IMAP) · jsoup · Caffeine · JJWT · springdoc-openapi (Swagger UI) · Vanilla-JS SPA (ES modules, no build step) with Chart.js + canvas-confetti via CDN · JUnit 5, Mockito-free pure unit tests, MockWebServer, Testcontainers.

## Run in demo mode in under 5 minutes

```bash
# 1. Start MongoDB (docker compose starts Mongo only, or both services)
docker compose up mongo -d

# 2. Run the app (demo profile is the default: mock providers, fixed OTP, dev endpoints)
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run

# 3. Open http://localhost:8080
```

- Log in with **phone + password** — knowing the number alone is not enough. New accounts use **Create account** (phone + OTP + choose password); **Forgot password** resets it via OTP. Demo OTP is **123456** (clearly labelled *Demo mode* in the UI).
- Demo credentials: `9999999999` / `demo1234` (any other number can create its own account).
- The **Demo Control Panel** (bottom-left, demo profile only) lets you simulate each of the 9 sample emails, toggle 🌧️ stormy / 🌤️ weather, force status changes and trigger an inbox poll — a full 3-minute demo with zero waiting.
- Seed data loads on first run: demo user `9999999999` (alias `rahul55`) with 6 varied orders including one HIGH-risk stormy example and one delivered.

### Or run everything containerized

```bash
docker compose up --build      # app on :8080, mongo on :27017
```

### MongoDB Atlas instead of local

```bash
export MONGODB_URI="mongodb+srv://user:pass@cluster.mongodb.net/parcelpilot"
./mvnw spring-boot:run
```

## Configuration (all optional in demo mode)

Copy `.env.example` → `.env`. Nothing is required for the demo; these unlock real behaviour:

| Env var | Purpose |
|---|---|
| `MONGODB_URI` | Mongo connection string (local Docker default) |
| `JWT_SECRET` | JWT signing key (dev default is fine locally, **change in prod**) |
| `SPRING_PROFILES_ACTIVE` | `demo` (default) or `prod` |
| `MAIL_USER` / `MAIL_APP_PASSWORD` | Gmail address + **App Password** of the shared demo inbox for real IMAP polling |
| `MAIL_FORWARD_TO` | Forwarding address shown on the Setup page |
| `SHIP24_API_KEY` | Real tracking via Ship24 (free tier: 10 shipments/month, key on signup) |
| `TRACK17_API_KEY` | Real tracking via 17TRACK (new accounts: one-time 200 free numbers) |
| `WEATHER_PROVIDER` | `mock` (default) or `open-meteo` |
| `TRACKING_PROVIDER` | `mock` (default), `ship24`, `17track` |
| `LLM_ENABLED` + `ANTHROPIC_API_KEY` | Optional: LLM only *rephrases* the template message; it never decides risk |
| `FAST2SMS_API_KEY` | Real SMS OTP (Fast2SMS). Set `app.otp.sender=sms` to activate |
| `FAST2SMS_SENDER_ID` / `FAST2SMS_TEMPLATE_ID` | Approved DLT header + OTP template — **required** for the ₹0.25/SMS DLT route; without them the no-DLT Quick SMS route bills **₹5 per SMS unit** |

> Verify current Ship24 / 17TRACK free-tier limits before relying on them — they change.
>
> **Fast2SMS OTP pricing gotcha:** the advertised ₹0.25/SMS is the *DLT route* rate and needs (1) a wallet recharge of ₹100+, (2) DLT registration of your entity, 6-letter header and OTP template, and (3) messages ≤160 chars (longer texts bill as multiple units). Without DLT the Quick SMS route silently bills ₹5 per unit — a 3-unit message costs ₹15, not ₹0.25.
> **Shiprocket is intentionally not used as a provider**: its tracking API is oriented to a seller's own shipments (possible future seller-side integration, not a shopper-facing tracker).

## API

`/api` prefix, all JSON. Swagger UI at **/swagger-ui.html**.

| Method + path | Purpose |
|---|---|
| `POST /auth/login` | Phone + password login (BCrypt hashes; demo user `9999999999` / `demo1234`) |
| `POST /auth/request-otp`, `POST /auth/register` | Signup / first password: OTP `123456` in demo, rate-limited |
| `POST /auth/reset-password` | Forgot password: phone + OTP + new password (no JWT issued) |
| `GET /me`, `PATCH /me/settings`, `DELETE /me` | Profile, theme/notifications, full data deletion |
| `GET /orders?status=&platform=&q=&sort=` | Dashboard list (auto-recomputes stale risk >1 h) |
| `GET /orders/{id}`, `POST /orders`, `DELETE /orders/{id}`, `POST /orders/{id}/refresh` | Detail (+weather), add by number, delete, force refresh |
| `GET /orders/detect-courier?number=` | Courier auto-detection |
| `GET /setup/info` | Alias, forwarding address, Gmail code, verification status |
| `GET /setup/gmail-filter.xml` | Downloadable Gmail filter (allowlist pre-filled) |
| `GET /privacy/audit` | Plain-language audit log |
| `GET /stats` | Counts by status / platform / risk |
| `POST /dev/simulate-email`, `POST /dev/poll-now`, `POST /dev/set-weather?mode=`, `POST /dev/orders/{id}/status`, `GET /dev/samples` | **Demo profile only** |

`userId` is **always derived from the JWT** — no request body can influence it (enforced by tests).

## 3-minute demo script

1. **Login (0:00)** — log in as `9999999999` / `demo1234`, land on the dashboard with seeded orders and the stormy HIGH-risk card. (Or show **Create account**: phone + OTP `123456` + choose a password.)
2. **Email simulation (0:40)** — open the 🎛️ Demo panel → simulate *"Flipkart — out for delivery"* → the existing Flipkart card flips to OUT_FOR_DELIVERY within a second (idempotent by AWB, audited).
3. **Weather risk (1:10)** — toggle 🌧️ Stormy in the panel → open an order → gauge shows HIGH with *"Thunderstorms are expected near Mumbai…"* and the exact rules under **"Why we think this"**. Toggle 🌤️ Clear → risk returns to LOW.
4. **Amazon email-only (1:50)** — simulate *"Amazon — email-only order"* → the card appears with the ✉️ **email-only** badge and an info banner explaining why live scanning isn't available.
5. **Not-everything-is-stored (2:20)** — simulate the promo email and the unknown-sender email → open **Privacy & Activity** → both show as *ignored* with the sender domain; note the privacy promise box.
6. **Add manually (2:40)** — click **+ Add order**, paste `11234567891` → courier auto-detects as Delhivery, live status fills in.

## Gmail forwarding setup (real emails)

1. Create/use a Gmail account as the shared demo inbox; generate an **App Password**; set `MAIL_USER` / `MAIL_APP_PASSWORD`.
2. On the Setup page, copy your personal address `demoinbox+<alias>@gmail.com`.
3. Gmail → Settings → Forwarding → add that address → Gmail sends a confirmation mail; the poller extracts the **confirmation code** and shows it on the Setup page automatically.
4. Download **`/api/setup/gmail-filter.xml`** and import it in Gmail → Settings → Filters (it forwards only allowlisted retailer domains).
5. The Setup stepper flips to green when the first order email arrives. Every user is free to register their own alias against the same inbox.

## Privacy design

- **Stored**: courier, tracking number, status, city, ETA/dates, event timeline, risk assessment, masked audit summaries.
- **Never stored**: raw email text, item names, prices, addresses. The parser mines facts and the body is dropped immediately.
- Non-allowlisted senders are **never parsed** — only a domain lands in the audit log.
- Logs mask phone numbers (`98****3210`) and tracking numbers (`****3456`).
- `DELETE /me` removes the user, all orders and the audit log (one click in the UI).

## Assumptions & honest limitations

- **One shared demo inbox** — anyone with the Gmail credentials sees the inbox; aliases only partition ParcelPilot's data. Production would use per-user inboxes or inbound-email webhooks.
- **AWB heuristics overlap** (Delhivery 10–18 digits vs Blue Dart 10 vs Xpressbees 12–15) — the sender-domain hint breaks ties; unknown formats are still tracked by carriers' auto-detect.
- **Rule-based risk, not ML** — thresholds are configuration, visible in the UI; no accuracy metric is claimed or implied.
- **Open-Meteo is free for non-commercial use only** — fine for a college project; budget for a commercial license in production.
- **In-memory OTP store and daily budget** — single-instance assumptions (Redis for a cluster).
- **Mock weather applies city-wide** — it's a demo mode; real Open-Meteo is per-city.
- Sample emails are plain-text/HTML JSON (not raw `.eml`) so the simulator matches real IMAP output.

## Testing

```bash
./mvnw test
```

64 tests: `EmailParser` (per-courier regexes, promo-mail rejection, ETA formats, facts-only record), `DelayRiskService` (threshold boundaries, OFD halving, delivered), `AliasResolver`, `CourierDetector`, `FallbackTrackingProvider` (failure/budget/unknown), `OtpService` (rate limits), `JwtService`/`JwtAuthFilter`, DTO guard (no `userId` in any request DTO), `OpenMeteoClient` (MockWebServer), `MockTrackingProvider` (determinism) and a full **`GmailPipelineTest`** integration test (Testcontainers Mongo; auto-skipped when Docker isn't running) covering login → simulate → dedupe → allowlist → audit → stormy risk.

## Future scope

- Cloudflare Email Routing / inbound-email webhooks (no IMAP polling)
- Real SMS/WhatsApp OTP and delay alerts (the `OtpSender` interface is the plug point)
- Gmail API integration after Google app verification
- Trained delay-prediction model on historical tracking+weather data (until then: transparent rules)
- Native mobile app; per-user inboxes; multi-instance OTP via Redis
