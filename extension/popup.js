// Popup controller: shows login or send view, forwards clicks to background.js,
// and renders the result. Never calls the API or touches the page directly.

const $ = (id) => document.getElementById(id);

const loginView = $("login-view");
const sendView = $("send-view");
const logoutBtn = $("logout");
const result = $("result");

// ---------- Messaging ----------

/** Sends { type, ...payload } to background.js; resolves with data or throws the error text. */
async function ask(type, payload = {}) {
  const reply = await chrome.runtime.sendMessage({ type, ...payload });
  if (!reply) throw new Error("Extension background didn't respond. Reload the extension.");
  if (!reply.ok) throw new Error(reply.error);
  return reply.data;
}

// ---------- Rendering ----------

function showLoggedIn(user) {
  $("who").textContent = user?.displayName || maskPhone(user?.phone) || "you";
  loginView.hidden = true;
  sendView.hidden = false;
  logoutBtn.hidden = false;
}

function showLoggedOut() {
  loginView.hidden = false;
  sendView.hidden = true;
  logoutBtn.hidden = true;
  $("phone").focus();
}

function showError(message) {
  result.className = "result err";
  result.replaceChildren(message);
  result.hidden = false;
}

const OUTCOME_TEXT = {
  CREATED: "✓ Added to your EkRaah dashboard",
  UPDATED: "✓ Tracking added to an order already on your dashboard",
  ALREADY_TRACKED: "✓ You're already tracking this order",
};

function showSuccess(outcome, order, facts) {
  result.className = "result ok";
  const rows = [
    ["Store", facts.platform],
    ["Courier", order.courier || facts.courierHint || "Detecting…"],
    ["Tracking", maskTracking(order.trackingNumber)],
    ["Status", order.status],
  ];
  const dl = document.createElement("dl");
  for (const [k, v] of rows) {
    const dt = document.createElement("dt");
    const dd = document.createElement("dd");
    dt.textContent = k;
    dd.textContent = v ?? "—";
    dl.append(dt, dd);
  }
  result.replaceChildren(OUTCOME_TEXT[outcome] || OUTCOME_TEXT.CREATED, dl);
  result.hidden = false;
}

function clearResult() {
  result.hidden = true;
  result.replaceChildren();
}

/** Disables a button and swaps its label while an async action runs. */
async function busy(button, label, action) {
  const original = button.textContent;
  button.disabled = true;
  button.textContent = label;
  try {
    await action();
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

// Same masking as com.ekraah.util.Mask on the backend.
function maskPhone(phone) {
  return phone && phone.length >= 10 ? phone.slice(0, 2) + "****" + phone.slice(-4) : phone;
}
function maskTracking(awb) {
  return awb ? "****" + awb.slice(-4) : "—";
}

// ---------- Events ----------

loginView.addEventListener("submit", (e) => {
  e.preventDefault();                              // stay in the popup; don't reload it
  clearResult();
  busy($("login-btn"), "Logging in…", async () => {
    try {
      const { user } = await ask("login", { phone: $("phone").value, password: $("password").value });
      $("password").value = "";
      showLoggedIn(user);
    } catch (err) {
      showError(err.message);
    }
  });
});

$("send-btn").addEventListener("click", () => {
  clearResult();
  busy($("send-btn"), "Reading this page…", async () => {
    try {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      if (!tab?.id) throw new Error("No active tab found.");
      const { outcome, order, facts } = await ask("sendOrder", { tabId: tab.id });
      showSuccess(outcome, order, facts);
    } catch (err) {
      if (/log in/i.test(err.message)) showLoggedOut();   // token expired mid-session
      showError(err.message);
    }
  });
});

logoutBtn.addEventListener("click", async () => {
  await ask("logout");
  clearResult();
  showLoggedOut();
});

// ---------- Start ----------

(async () => {
  try {
    const { loggedIn, user } = await ask("status");
    loggedIn ? showLoggedIn(user) : showLoggedOut();
  } catch (err) {
    showLoggedOut();
    showError(err.message);
  }
})();

// - ask(): the single bridge to background.js; background replies { ok, data } or { ok:false, error }
//   (see its onMessage router), so every caller just uses try/catch.
// - chrome.tabs.query works without the "tabs" permission: we only need tab.id, not its URL/title.
//   Opening this popup via the toolbar icon is what grants activeTab for that tab.
// - All page/server text is inserted with textContent / replaceChildren(string), never innerHTML,
//   so a malicious value (e.g. a crafted courier name) can't inject HTML into the popup.
// - busy(): prevents double-clicks sending the same order twice while the request is in flight.
// - The password field is cleared after a successful login; only the JWT is kept (in background).
// - maskPhone / maskTracking mirror the backend's Mask utility so the popup never shows full ids.
// - If a send fails with a "log in" message (expired/revoked JWT), we switch to the login view.
