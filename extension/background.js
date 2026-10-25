// EkRaah extension service worker: the only part of the extension that talks to the backend.
// The popup asks it to log in / send an order; it injects the extractor into the active tab,
// then posts the extracted facts (never the page itself) to /api/orders/import.

const API_BASE = "http://localhost:8080/api";   // must match host_permissions in manifest.json
const AUTH_KEY = "ekraah_auth";   // chrome.storage.local: { token, expiresAt, refreshToken, sessionExpiresAt, user }
const SESSION_DAYS = 30;           // matches SessionService.LIFETIME on the backend

// ---------- Auth storage ----------
// The backend gives the extension its own revocable session ("client": "extension"): a 15-minute
// access token that can only call POST /orders/import, plus a 30-day refresh token. Logging out
// here, resetting the password or deleting the account ends it server-side immediately.

async function getAuth() {
  const { [AUTH_KEY]: auth } = await chrome.storage.local.get(AUTH_KEY);
  if (!auth || !auth.refreshToken) return null;            // nothing stored, or the old token format
  if (Date.now() >= auth.sessionExpiresAt) {               // session itself is over: show login
    await clearAuth();
    return null;
  }
  return auth;
}

async function saveAuth(data, previous) {
  const auth = {
    token: data.token,
    expiresAt: Date.now() + data.expiresIn * 1000,
    refreshToken: data.refreshToken,
    sessionExpiresAt: previous?.sessionExpiresAt ?? Date.now() + SESSION_DAYS * 86_400_000,
    user: data.user ?? previous?.user,
  };
  await chrome.storage.local.set({ [AUTH_KEY]: auth });
  return auth;
}

async function clearAuth() {
  await chrome.storage.local.remove(AUTH_KEY);
}

/** Trades the refresh token for a new access token (and a rotated refresh token). */
async function renew(auth) {
  const res = await fetch(API_BASE + "/auth/refresh", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken: auth.refreshToken }),
  }).catch(() => null);
  if (!res) throw { status: 0, message: "Can't reach EkRaah — is the server running?" };
  if (!res.ok) {                                  // revoked, expired or reused: log in again
    await clearAuth();
    throw { status: 401, message: "Session expired, please log in again." };
  }
  return saveAuth(await res.json(), auth);
}

// ---------- API helper (mirrors static/js/api.js) ----------

async function api(path, { method = "GET", body } = {}, retried = false) {
  const headers = { "Content-Type": "application/json" };
  let auth = path.startsWith("/auth/") ? null : await getAuth();
  if (auth && Date.now() >= auth.expiresAt - 30_000) auth = await renew(auth);   // renew just before expiry
  if (auth) headers["Authorization"] = "Bearer " + auth.token;

  let res;
  try {
    res = await fetch(API_BASE + path, {
      method, headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (e) {
    throw { status: 0, message: "Can't reach EkRaah — is the server running?" };
  }

  if (res.status === 401 && auth) {
    if (!retried) {                               // access token rejected: renew once and retry
      await renew(auth);
      return api(path, { method, body }, true);
    }
    await clearAuth();
    throw { status: 401, message: "Session expired, please log in again." };
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw { status: res.status, message: data.error || "Request failed (" + res.status + ")" };
  return data;
}

// ---------- Actions ----------

async function login(phone, password) {
  const data = await api("/auth/login", { method: "POST", body: { phone, password, client: "extension" } });
  const auth = await saveAuth(data, null);
  return { user: auth.user };
}

async function logout() {
  const auth = await getAuth();
  await clearAuth();
  if (auth) {                                     // end the session server-side, not just locally
    await fetch(API_BASE + "/auth/logout", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: auth.refreshToken }),
    }).catch(() => {});
  }
  return { loggedIn: false };
}

async function status() {
  const auth = await getAuth();
  return { loggedIn: !!auth, user: auth ? auth.user : null };
}

/** Runs the extractor in the tab the user clicked on, then imports the result. */
async function sendOrder(tabId) {
  if (!(await getAuth())) throw { status: 401, message: "Please log in first." };

  // Inject the per-site rules first, then the extractor that uses them. Both run only now,
  // because the user clicked — activeTab gives us this one tab, nothing else.
  const results = await chrome.scripting.executeScript({
    target: { tabId },
    files: ["content/retailers.js", "content/extract.js"],
  });
  const facts = results.at(-1)?.result;          // value of the last file (extract.js)

  if (!facts || facts.error) {
    throw { status: 0, message: facts?.error || "Couldn't read this page." };
  }

  const { outcome, order } = await api("/orders/import", {
    method: "POST",
    body: {
      platform: facts.platform,
      trackingNumber: facts.trackingNumber,
      courierHint: facts.courierHint,
      retailerOrderId: facts.retailerOrderId,
      status: facts.status,                      // page status; DELIVERED -> server uses the order id
      expectedDelivery: facts.expectedDelivery,  // "yyyy-mm-dd" or null
      deliveredOn: facts.deliveredOn,
    },
  });
  return { outcome, order, facts };
}

// ---------- Message router (popup -> background) ----------

const handlers = {
  login: (msg) => login(msg.phone, msg.password),
  logout: () => logout(),
  status: () => status(),
  sendOrder: (msg) => sendOrder(msg.tabId),
};

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  const handler = handlers[msg?.type];
  if (!handler) return false;

  handler(msg)
    .then((data) => sendResponse({ ok: true, data }))
    .catch((err) => sendResponse({ ok: false, error: err?.message || String(err) }));
  return true;                                   // keep the channel open for the async reply
});

// - API_BASE: the backend origin; because it's listed in host_permissions, fetch() from this
//   service worker isn't blocked by CORS, so SecurityConfig needs no CORS change.
// - getAuth()/renew(): the access token lasts 15 min and is renewed with the refresh token just
//   before it expires (or once after a 401); a failed renewal means the session was ended — log in.
// - api(): same contract as the SPA's api.js — backend errors arrive as { error: "..." }.
// - sendOrder(): executeScript with two files runs them in order in the same isolated world,
//   so extract.js can read the rules retailers.js defined; results.at(-1) is extract.js's value.
// - Only the extracted facts are sent (ids, courier hint, status, two dates) — never page text,
//   matching the "facts only" privacy rule of the email pipeline.
// - /orders/import replies { outcome: CREATED | UPDATED | ALREADY_TRACKED, order }
//   (OrderController.importOrder -> OrderService.importFromExtension).
// - onMessage returns true: tells Chrome the reply comes later (we're awaiting a fetch).
