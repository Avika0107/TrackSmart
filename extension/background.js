// EkRaah extension service worker: the only part of the extension that talks to the backend.
// The popup asks it to log in / send an order; it injects the extractor into the active tab,
// then posts the extracted facts (never the page itself) to /api/orders/import.

const API_BASE = "http://localhost:8080/api";   // must match host_permissions in manifest.json
const AUTH_KEY = "ekraah_auth";                 // chrome.storage.local key: { token, expiresAt, user }

// ---------- Auth storage ----------

async function getAuth() {
  const { [AUTH_KEY]: auth } = await chrome.storage.local.get(AUTH_KEY);
  if (!auth) return null;
  if (Date.now() >= auth.expiresAt) {           // JWT expired: forget it so the popup shows login
    await clearAuth();
    return null;
  }
  return auth;
}

async function setAuth(token, expiresInSeconds, user) {
  const auth = { token, expiresAt: Date.now() + expiresInSeconds * 1000, user };
  await chrome.storage.local.set({ [AUTH_KEY]: auth });
  return auth;
}

async function clearAuth() {
  await chrome.storage.local.remove(AUTH_KEY);
}

// ---------- API helper (mirrors static/js/api.js) ----------

async function api(path, { method = "GET", body } = {}) {
  const headers = { "Content-Type": "application/json" };
  const auth = await getAuth();
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

  if (res.status === 401 && auth) {              // token rejected server-side: log out locally too
    await clearAuth();
    throw { status: 401, message: "Session expired, please log in again." };
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw { status: res.status, message: data.error || "Request failed (" + res.status + ")" };
  return data;
}

// ---------- Actions ----------

async function login(phone, password) {
  const data = await api("/auth/login", { method: "POST", body: { phone, password } });
  const auth = await setAuth(data.token, data.expiresIn, data.user);
  return { user: auth.user };
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
    },
  });
  return { outcome, order, facts };
}

// ---------- Message router (popup -> background) ----------

const handlers = {
  login: (msg) => login(msg.phone, msg.password),
  logout: () => clearAuth().then(() => ({ loggedIn: false })),
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
// - getAuth(): checks expiry client-side (expiresIn comes from /auth/login, 720 min by default)
//   so the popup can show the login form without a wasted request.
// - api(): same contract as the SPA's api.js — backend errors arrive as { error: "..." }.
// - sendOrder(): executeScript with two files runs them in order in the same isolated world,
//   so extract.js can read the rules retailers.js defined; results.at(-1) is extract.js's value.
// - Only platform / trackingNumber / courierHint / retailerOrderId are sent — never page text,
//   matching the "facts only" privacy rule of the email pipeline.
// - /orders/import replies { outcome: CREATED | UPDATED | ALREADY_TRACKED, order }
//   (OrderController.importOrder -> OrderService.importFromExtension).
// - onMessage returns true: tells Chrome the reply comes later (we're awaiting a fetch).
