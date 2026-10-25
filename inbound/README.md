# Per-user inbound email (replaces the shared Gmail inbox)

With the IMAP setup every user forwards to one Gmail inbox (`demoinbox+ALIAS@gmail.com`), so
whoever holds its password can read everyone's order mail. The inbound webhook removes that
inbox: each user gets `ALIAS@<inbound domain>`, an email relay posts each message to
`POST /api/inbound/email`, and no mailbox password exists.

## Backend settings

| Env var | Value |
|---|---|
| `INBOUND_WEBHOOK_SECRET` | Long random string (`openssl rand -hex 32`). Blank = webhook off. |
| `INBOUND_EMAIL_DOMAIN` | e.g. `in.ekraah.app`. Setup page then shows `ALIAS@in.ekraah.app`. |
| `INBOUND_TRUSTED_AUTHSERV_ID` | The authserv-id at the start of the relay's `Authentication-Results` header (see below). |

## Request format

```
POST /api/inbound/email
X-EkRaah-Timestamp: <epoch seconds>                    # must be within 5 minutes
X-EkRaah-Signature: hex(HMAC-SHA256(secret, "<timestamp>.<raw body>"))
Content-Type: application/json

{ "messageId", "from", "to", "subject", "text" | "html", "authenticationResults" }
```

`to` must be the recipient address (`ALIAS@domain`); the alias is its local part. The response is
`{"outcome": "CREATED" | "UPDATED" | "DUPLICATE" | "IGNORED_SENDER" | "UNVERIFIED_SENDER" | …}`.

## Cloudflare Email Routing

`cloudflare-email-worker.js` is a ready-to-adapt Email Worker (free tier). It has **not** been run
against Cloudflare yet. Before relying on it, send a real retailer email to a test alias and check:

1. The worker's log shows the `Authentication-Results` value; its first token (before `;`) is the
   authserv-id to put in `INBOUND_TRUSTED_AUTHSERV_ID`.
2. That header shows `dkim=pass` for the retailer's domain. If Cloudflare does not provide the
   header, the relay must supply the verdict another way, or every message is rejected as
   `UNVERIFIED_SENDER` — by design, since an unverified From header can be forged.

Other relays (Amazon SES receipt rules, Mailgun routes) work the same way: post the fields above,
signed with the shared secret, with the provider's DKIM verdict in `authenticationResults`.
