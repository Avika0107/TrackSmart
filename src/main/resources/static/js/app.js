import { api, isLoggedIn, refresh, logoutSession } from "./api.js";
import { el, toast, maskPhone } from "./ui.js";
import { renderLogin } from "./views/auth.js";
import { renderSetup } from "./views/setup.js";
import { renderDashboard } from "./views/dashboard.js";
import { renderInsights } from "./views/insights.js";
import { renderPrivacy } from "./views/privacy.js";
import { renderProfile, renderSettings } from "./views/profile.js";
import { alertBell, startAlertPolling, stopAlertPolling } from "./alerts.js";

export const state = {
  me: null,
  demoMode: false,
  demoOpen: false,
};

export function setTheme(theme) {
  document.documentElement.dataset.theme = theme;
  localStorage.setItem("pp_theme", theme);
  const btn = document.getElementById("theme-toggle");
  if (btn) btn.textContent = themeLabel(theme);
  if (state.me) api("/me/settings", { method: "PATCH", body: { theme } }).catch(() => {});
}

export function initTheme() {
  const saved = localStorage.getItem("pp_theme") || "dark";
  document.documentElement.dataset.theme = saved;
}

export async function loadMe() {
  // Demo mode is needed by every view (demo chip, Demo Control Panel), not only Setup,
  // so it is loaded with the profile before the first view renders.
  const [me, mode] = await Promise.all([api("/me"), api("/auth/mode").catch(() => ({}))]);
  state.me = me;
  state.demoMode = mode.demoMode === true;
  return state.me;
}

export async function logout() {
  stopAlertPolling();
  state.me = null;
  // Must finish before navigating: the login route would otherwise renew the access token from
  // the still-valid session cookie and log straight back in.
  await logoutSession();
  location.hash = "#/login";
}

function themeToggle() {
  const dark = document.documentElement.dataset.theme === "dark";
  return el("button", {
    id: "theme-toggle", class: "navlink", style: "margin-top:6px", "aria-label": "Toggle light/dark theme",
    onclick: () => setTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark"),
  }, themeLabel(dark ? "dark" : "light"));
}

/** The toggle offers the other theme; one helper so a toggle keeps the same label format. */
function themeLabel(theme) {
  return theme === "dark" ? "☀️ Light mode" : "🌙 Dark mode";
}

const NAV = [
  { hash: "#/dashboard", icon: "📦", label: "Dashboard", view: renderDashboard },
  { hash: "#/insights", icon: "📊", label: "Insights", view: renderInsights },
  { hash: "#/setup", icon: "✉️", label: "Setup", view: renderSetup },
  { hash: "#/privacy", icon: "🛡️", label: "Privacy", view: renderPrivacy },
  // reachable via the avatar menu / mobile bar, not the desktop sidebar
  { hash: "#/profile", icon: "👤", label: "Profile", view: renderProfile, hidden: true },
  { hash: "#/settings", icon: "⚙️", label: "Settings", view: renderSettings, hidden: true },
];

export function layout(content, activeHash) {
  const nav = (where) => NAV.filter((n) => !n.hidden).map((n) =>
    el("button", {
      class: "navlink" + (n.hash === activeHash ? " active" : ""),
      onclick: () => (location.hash = n.hash),
    }, where === "tabs" ? el("span", { class: "ico", "aria-hidden": "true" }, n.icon) : el("span", { "aria-hidden": "true" }, n.icon + " " + n.label)));

  const app = document.getElementById("app");
  app.replaceChildren(
    el("div", { class: "layout" },
      el("aside", { class: "sidebar" },
        el("div", { class: "brand" }, el("span", { class: "logo", "aria-hidden": "true" }, "📦"), "EkRaah"),
        ...nav("side"),
        el("div", { class: "spacer" }),
        themeToggle(),
        el("button", { class: "navlink", onclick: logout }, "🚪 Log out"),
      ),
      el("main", { class: "main" },
        el("div", { class: "topbar" },
          el("div", {},
            el("h1", {}, greeting()),
            el("div", { class: "sub" }, "All your orders, one dashboard"),
          ),
          el("div", { class: "chips" },
            state.demoMode ? el("span", { class: "chip demo" }, "Demo mode") : null,
            alertBell(),
            avatarButton(),
          ),
        ),
        content,
      ),
      el("nav", { class: "tabbar", "aria-label": "Main navigation" },
        ...nav("tabs"),
        el("button", { class: activeHash === "#/profile" ? "active" : "",
                       onclick: () => (location.hash = "#/profile"), "aria-label": "Profile" },
          el("span", { class: "ico" }, "👤"), el("span", {}, "Profile")),
      ),
    ));
}

// ---- Profile avatar menu (top-right) ---------------------------------------

function initialsOf(name, phone) {
  if (name && name.trim()) {
    return name.trim().split(/\s+/).slice(0, 2).map((w) => w[0].toUpperCase()).join("");
  }
  return (phone || "PP").slice(0, 2);
}

function avatarButton() {
  const me = state.me;
  const wrap = el("div", { class: "avatar-wrap" });
  const btn = el("button", {
    class: "avatar-btn", "aria-haspopup": "menu", "aria-expanded": "false",
    "aria-label": "Open profile menu",
  }, initialsOf(me?.displayName, me?.phone));

  const close = () => {
    menu.hidden = true;
    btn.setAttribute("aria-expanded", "false");
    document.removeEventListener("click", onDoc, true);
    document.removeEventListener("keydown", onEsc);
  };
  const open = () => {
    menu.hidden = false;
    btn.setAttribute("aria-expanded", "true");
    document.addEventListener("click", onDoc, true);
    document.addEventListener("keydown", onEsc);
  };
  const toggle = () => (menu.hidden ? open() : close());
  const onDoc = (e) => { if (!wrap.contains(e.target)) close(); };
  const onEsc = (e) => e.key === "Escape" && close();
  const go = (hash) => { close(); location.hash = hash; };

  const menu = el("div", { class: "avatar-menu", hidden: true, role: "menu", "aria-label": "Profile menu" },
    el("div", { class: "menu-head" },
      el("div", { style: "font-weight:800" }, me?.displayName || "EkRaah user"),
      el("div", { style: "font-size:12.5px;color:var(--text-soft)" }, "+91 " + maskPhone(me?.phone))),
    el("button", { class: "menu-item", role: "menuitem", onclick: () => go("#/profile") }, "👤 My Profile"),
    el("button", { class: "menu-item", role: "menuitem", onclick: () => go("#/settings") }, "⚙️ Settings"),
    el("button", { class: "menu-item", role: "menuitem", onclick: () => go("#/privacy") }, "🛡️ Privacy & Activity"),
    el("button", { class: "menu-item", role: "menuitem", onclick: () => { close(); howItWorks(); } }, "💡 How it works"),
    el("button", { class: "menu-item danger", role: "menuitem", onclick: () => { close(); logout(); } }, "🚪 Log out"));

  btn.addEventListener("click", (e) => { e.stopPropagation(); toggle(); });
  wrap.append(btn, menu);
  return wrap;
}

function howItWorks() {
  const overlay = document.getElementById("overlay-root");
  const close = () => overlay.replaceChildren();
  overlay.replaceChildren(
    el("div", { class: "modal-bg", onclick: (e) => e.target === e.currentTarget && close() },
      el("div", { class: "modal", role: "dialog", "aria-modal": "true", "aria-label": "How EkRaah works" },
        el("h2", { style: "font-size:17px;margin:0 0 12px" }, "💡 How EkRaah works"),
        el("div", { class: "steps3" },
          el("div", {}, el("span", { class: "n" }, "1"), "Log in with your phone number and password"),
          el("div", {}, el("span", { class: "n" }, "2"), "Forward retailer shipping emails to your tracking address"),
          el("div", {}, el("span", { class: "n" }, "3"), "See every parcel with live status and weather-based delay risk")),
        el("p", { style: "font-size:12.5px;color:var(--text-soft);margin:12px 0 0" },
          "Only allowlisted retailer senders are ingested. Export or delete your data anytime from Privacy & Activity."),
        el("button", { class: "btn", style: "width:100%;margin-top:14px", onclick: close }, "Got it"))));
}

function greeting() {
  const h = new Date().getHours();
  const name = state.me?.displayName || "there";
  return (h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening") + ", " + name.split(" ")[0];
}

async function route() {
  const hash = location.hash || "#/dashboard";
  // The Demo Control Panel belongs to the dashboard (its actions re-render it), so it is removed
  // on every route change — including logout to the login page; renderDashboard re-mounts it,
  // and reopens it if state.demoOpen.
  document.querySelector(".demo-fab")?.remove();
  document.querySelector(".demo-drawer")?.remove();
  // After a reload the access token is gone (memory only); the HttpOnly session cookie renews it.
  if (!isLoggedIn() && !(await refresh())) { renderLogin(); return; }
  if (!state.me) {
    try { await loadMe(); } catch (e) { return; }
  }
  startAlertPolling();   // no-op when already running
  const view = NAV.find((n) => hash.startsWith(n.hash)) || NAV[0];
  view.view(); // views attach themselves via layout(); route() must not wrap them again
}

window.addEventListener("hashchange", route);
initTheme();
route();
