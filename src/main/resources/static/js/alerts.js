// Delay alerts: the 🔔 bell in the top bar, polling, and optional desktop notifications.
// Alerts are created server-side (AlertService) when an order turns HIGH risk, hits a courier
// exception, or goes out for delivery; this module only shows them.
import { api } from "./api.js";
import { el, toast, timeAgo } from "./ui.js";

const POLL_MS = 60_000;
const ICON = { HIGH_RISK: "🌧️", EXCEPTION: "⚠️", OUT_FOR_DELIVERY: "🛵", OVERDUE: "⏰" };

let latest = { unread: 0, alerts: [] };
let seen = null;        // alert ids already shown to this tab (null until the first poll)
let timer = null;

/** Starts polling after login; the first poll only records what exists, so old alerts don't toast. */
export function startAlertPolling() {
  if (timer) return;
  poll();
  timer = setInterval(poll, POLL_MS);
}

export function stopAlertPolling() {
  clearInterval(timer);
  timer = null;
  seen = null;
  latest = { unread: 0, alerts: [] };
}

async function poll() {
  try {
    latest = await api("/alerts");
  } catch {
    return;   // offline / logged out: the bell just keeps its last state
  }
  const fresh = seen ? latest.alerts.filter((a) => !a.read && !seen.has(a.id)) : [];
  seen = new Set(latest.alerts.map((a) => a.id));
  for (const a of fresh.reverse()) notify(a);
  renderBadge();
}

function notify(alert) {
  toast(`${ICON[alert.kind] || "🔔"} ${alert.message}`, alert.kind === "OUT_FOR_DELIVERY" ? "ok" : "info");
  if ("Notification" in window && Notification.permission === "granted" && document.hidden) {
    try {
      const n = new Notification("EkRaah", { body: alert.message, tag: alert.id });
      n.onclick = () => { window.focus(); openOrder(alert.orderId); n.close(); };
    } catch { /* some browsers only allow notifications from a service worker */ }
  }
}

function renderBadge() {
  const badge = document.getElementById("alert-badge");
  if (!badge) return;
  badge.textContent = latest.unread > 9 ? "9+" : String(latest.unread);
  badge.hidden = latest.unread === 0;
  document.getElementById("alert-btn")?.setAttribute("aria-label",
    latest.unread ? `Alerts, ${latest.unread} unread` : "Alerts");
}

async function openOrder(orderId) {
  if (!orderId) return;
  location.hash = "#/dashboard";
  // Imported lazily: dashboard.js imports app.js, which imports this module.
  const { openDetail } = await import("./views/dashboard.js");
  setTimeout(() => openDetail(orderId), 150);
}

/** The bell button + dropdown, re-created by layout() on every view. */
export function alertBell() {
  const wrap = el("div", { class: "avatar-wrap" });
  const badge = el("span", { id: "alert-badge", class: "alert-badge", hidden: true });
  const btn = el("button", {
    id: "alert-btn", class: "bell-btn", "aria-haspopup": "menu", "aria-expanded": "false", "aria-label": "Alerts",
  }, "🔔", badge);
  const menu = el("div", { class: "avatar-menu alert-menu", hidden: true, role: "menu", "aria-label": "Alerts" });

  const close = () => {
    menu.hidden = true;
    btn.setAttribute("aria-expanded", "false");
    document.removeEventListener("click", onDoc, true);
    document.removeEventListener("keydown", onEsc);
  };
  const onDoc = (e) => { if (!wrap.contains(e.target)) close(); };
  const onEsc = (e) => e.key === "Escape" && close();

  const open = async () => {
    drawMenu();
    menu.hidden = false;
    btn.setAttribute("aria-expanded", "true");
    document.addEventListener("click", onDoc, true);
    document.addEventListener("keydown", onEsc);
    if (latest.unread) {
      // Opening the menu counts as reading them; the list keeps its unread dots until next open.
      await api("/alerts/read", { method: "POST" }).catch(() => {});
      latest = { ...latest, unread: 0 };
      renderBadge();
    }
  };

  function drawMenu() {
    const items = latest.alerts.length
      ? latest.alerts.map((a) => el("button", {
          class: "menu-item alert-item" + (a.read ? "" : " unread"), role: "menuitem",
          onclick: () => { close(); openOrder(a.orderId); },
        },
          el("span", { class: "alert-ico", "aria-hidden": "true" }, ICON[a.kind] || "🔔"),
          el("span", {}, el("span", { class: "alert-msg" }, a.message),
            el("span", { class: "alert-time" }, timeAgo(a.createdAt)))))
      : [el("div", { class: "alert-empty" }, "No alerts yet. You'll be told here when an order may be delayed or is out for delivery.")];

    const desktop = "Notification" in window && Notification.permission === "default"
      ? el("button", { class: "menu-item", role: "menuitem", onclick: async () => {
          const p = await Notification.requestPermission();
          toast(p === "granted" ? "Desktop notifications on (while EkRaah is open)" : "Desktop notifications not allowed", p === "granted" ? "ok" : "info");
          drawMenu();
        } }, "🖥️ Turn on desktop notifications")
      : null;

    menu.replaceChildren(
      el("div", { class: "menu-head" }, el("div", { style: "font-weight:800" }, "Alerts"),
        el("div", { style: "font-size:12.5px;color:var(--text-soft)" }, "Turn them off in Settings → Delay-risk notifications")),
      el("div", { class: "alert-list" }, ...items),
      ...(desktop ? [desktop] : []));
  }

  btn.addEventListener("click", (e) => { e.stopPropagation(); menu.hidden ? open() : close(); });
  wrap.append(btn, menu);
  queueMicrotask(renderBadge);   // the badge element must be in the DOM first
  return wrap;
}

// - Polling (60 s) instead of push: no push service or VAPID keys needed. Desktop notifications
//   use the Notification API, so they appear only while an EkRaah tab is open (even in background).
// - `seen` starts null: the first poll after login records existing alerts without toasting them.
// - Opening the menu marks everything read server-side (POST /api/alerts/read).
