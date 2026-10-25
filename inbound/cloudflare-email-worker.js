// Cloudflare Email Worker: relays mail for ALIAS@<your inbound domain> to EkRaah's signed webhook,
// so every user gets a private address and no shared Gmail inbox (or its password) exists.
//
// Setup (see inbound/README.md):
//   1. Cloudflare dashboard → your domain → Email → Email Routing → enable; add a catch-all rule
//      "Send to a Worker" → this worker.
//   2. Worker vars/secrets:  EKRAAH_URL = https://<backend>/api/inbound/email
//                            EKRAAH_SECRET = same value as the backend's INBOUND_WEBHOOK_SECRET
//   3. npm i postal-mime   (MIME parser; bundled by `wrangler deploy`)
import PostalMime from "postal-mime";

export default {
  async email(message, env) {
    const parsed = await PostalMime.parse(message.raw);
    const body = JSON.stringify({
      messageId: parsed.messageId || message.headers.get("Message-ID"),
      from: message.headers.get("From") || message.from,
      to: message.to,                                    // envelope recipient: ALIAS@domain
      subject: parsed.subject || "",
      text: parsed.text || null,
      html: parsed.text ? null : parsed.html || null,    // backend converts HTML to text
      // The topmost Authentication-Results is the receiving server's DKIM/DMARC verdict; the
      // backend only trusts it if its authserv-id equals INBOUND_TRUSTED_AUTHSERV_ID.
      authenticationResults: message.headers.get("Authentication-Results"),
    });

    const timestamp = Math.floor(Date.now() / 1000).toString();
    const key = await crypto.subtle.importKey(
      "raw", new TextEncoder().encode(env.EKRAAH_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${timestamp}.${body}`));
    const signature = [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, "0")).join("");

    const res = await fetch(env.EKRAAH_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-EkRaah-Timestamp": timestamp, "X-EkRaah-Signature": signature },
      body,
    });
    // A 5xx makes Email Routing retry later; 4xx (bad signature, ignored mail) is final.
    if (res.status >= 500) throw new Error(`EkRaah returned ${res.status}`);
  },
};
