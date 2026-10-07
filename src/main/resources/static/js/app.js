import { api, getToken, clearToken, isLoggedIn } from "./api.js";
import { el, toast } from "./ui.js";
import { renderLogin } from "./views/auth.js";
import { renderSetup } from "./views/setup.js";
import { renderDashboard } from "./views/dashboard.js";
import { renderInsights } from "./views/insights.js";
import { renderPrivacy } from "./views/privacy.js";

export const state = {
  me: null,
  demoMode: false,
  demoOpen: false,
};

export function setTheme(theme) {
  document.documentElement.dataset.theme = theme;
  localStorage.setItem("pp_theme", theme);
  const btn = document.getElementById("theme-toggle");
  if (btn) btn.textContent = theme === "dark" ? "☀️" : "🌙";
  if (state.me) api("/me/settings", { method: "PATCH", body: { theme } }).catch(() => {});
}

export function initTheme() {
  const saved = localStorage.getItem("pp_theme") || "dark";
  document.documentElement.dataset.theme = saved;
}

export async function loadMe() {
  state.me = await api("/me");
  return state.me;
}

export function logout() {
  clearToken();
  state.me = null;
  location.hash = "#/login";
}

function themeToggle() {
  const dark = document.documentElement.dataset.theme === "dark";
  return el("button", {
    id: "theme-toggle", class: "navlink", style: "margin-top:6px", "aria-label": "Toggle light/dark theme",
    onclick: () => setTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark"),
  }, dark ? "☀️ Light mode" : "🌙 Dark mode");
}

const NAV = [
  { hash: "#/dashboard", icon: "📦", label: "Dashboard", view: renderDashboard },
  { hash: "#/insights", icon: "📊", label: "Insights", view: renderInsights },
  { hash: "#/setup", icon: "✉️", label: "Setup", view: renderSetup },
  { hash: "#/privacy", icon: "🛡️", label: "Privacy", view: renderPrivacy },
];

export function layout(content, activeHash) {
  const nav = (where) => NAV.map((n) =>
    el("button", {
      class: "navlink" + (n.hash === activeHash ? " active" : ""),
      onclick: () => (location.hash = n.hash),
    }, where === "tabs" ? el("span", { class: "ico", "aria-hidden": "true" }, n.icon) : el("span", { "aria-hidden": "true" }, n.icon + " " + n.label)));

  const app = document.getElementById("app");
  app.replaceChildren(
    el("div", { class: "layout" },
      el("aside", { class: "sidebar" },
        el("div", { class: "brand" }, el("span", { class: "logo", "aria-hidden": "true" }, "📦"), "ParcelPilot"),
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
          ),
        ),
        content,
      ),
      el("nav", { class: "tabbar", "aria-label": "Main navigation" },
        ...nav("tabs"),
        el("button", { onclick: logout }, el("span", { class: "ico" }, "🚪"), el("span", {}, "Logout")),
      ),
    ));
}

function greeting() {
  const h = new Date().getHours();
  const name = state.me?.displayName || "there";
  return (h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening") + ", " + name.split(" ")[0];
}

async function route() {
  const hash = location.hash || "#/dashboard";
  if (!isLoggedIn()) { renderLogin(); return; }
  if (!state.me) {
    try { await loadMe(); } catch (e) { return; }
  }
  const view = NAV.find((n) => hash.startsWith(n.hash)) || NAV[0];
  view.view(); // views attach themselves via layout(); route() must not wrap them again
}

window.addEventListener("hashchange", route);
initTheme();
route();
