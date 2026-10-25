// The 15-minute access token lives in page memory only — never localStorage, where any injected
// script could read it. The 30-day session is an HttpOnly cookie (scoped to /api/auth) that page
// scripts can't see; refresh() trades it for a new access token after a reload or on expiry.
let token = null;

// Tokens from before sessions existed were kept in localStorage; drop any leftover copy.
try { localStorage.removeItem("pp_token"); } catch { /* storage blocked: nothing to clean */ }

export function getToken() { return token; }
export function setToken(t) { token = t; }
export function clearToken() { token = null; }
export function isLoggedIn() { return !!token; }

let refreshing = null;

/** New access token from the session cookie. Concurrent callers share one request. @return logged in? */
export function refresh() {
  if (!refreshing) {
    refreshing = fetch("/api/auth/refresh", { method: "POST", credentials: "same-origin" })
      .then(async (res) => {
        if (!res.ok) { token = null; return false; }
        token = (await res.json()).token;
        return true;
      })
      .catch(() => false)
      .finally(() => { refreshing = null; });
  }
  return refreshing;
}

/** Ends the session server-side (its access tokens stop working at once) and forgets the token. */
export async function logoutSession() {
  token = null;
  await fetch("/api/auth/logout", { method: "POST", credentials: "same-origin", keepalive: true }).catch(() => {});
}

export async function api(path, { method = "GET", body } = {}, retried = false) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers["Authorization"] = "Bearer " + token;

  let res;
  try {
    res = await fetch("/api" + path, {
      method, headers, credentials: "same-origin",
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (e) {
    throw { status: 0, message: "Network error — is the server running?" };
  }

  if (res.status === 401 && token && !path.startsWith("/auth/")) {
    // Access tokens last 15 minutes: renew from the session once and retry the request.
    if (!retried && await refresh()) return api(path, { method, body }, true);
    token = null;
    location.hash = "#/login";
    throw { status: 401, message: "Session expired, please log in again." };
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw { status: res.status, message: data.error || "Request failed (" + res.status + ")", data };
  return data;
}

export async function downloadFile(path, filename, retried = false) {
  const headers = {};
  if (token) headers["Authorization"] = "Bearer " + token;
  const res = await fetch("/api" + path, { headers, credentials: "same-origin" });
  if (res.status === 401 && !retried && await refresh()) return downloadFile(path, filename, true);
  if (!res.ok) throw { status: res.status, message: "Download failed" };
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = filename; a.click();
  URL.revokeObjectURL(url);
}
