import { api } from "../api.js";
import {
  el, toast, skeletonRow, emptyState, timeAgo, fmtDate, fmtDateTime, maskTracking,
  counter, celebrate, statusStep, statusPill, PLATFORM_COLORS, weatherIcon,
} from "../ui.js";
import { state, layout } from "../app.js";

let filters = { status: "", platform: "", q: "", sort: "-lastUpdated" };
let previousStatuses = {};

export function renderDashboard() {
  const content = el("div", {});
  load(content);
  layout(content, "#/dashboard");
  mountDemoPanel();
}

async function load(content) {
  content.replaceChildren(skeletonRow(6));
  try {
    const [orders, stats] = await Promise.all([
      api(`/orders?status=${filters.status || ""}&platform=${filters.platform || ""}&q=${encodeURIComponent(filters.q || "")}&sort=${filters.sort}`),
      api("/stats"),
    ]);
    detectDeliveries(orders);
    content.replaceChildren(
      ...[
      statCards(stats),
      attentionStrip(orders),
      filterBar(content),
      orders.length ? orderGrid(orders, content) : emptyGrid(content),
      ].filter(Boolean));
  } catch (e) {
    content.replaceChildren(emptyState("📡", "Couldn't load your orders", e.message,
      el("button", { class: "btn small", onclick: () => load(content) }, "Try again")));
  }
}

function detectDeliveries(orders) {
  for (const o of orders) {
    const before = previousStatuses[o.id];
    if (before && before !== "DELIVERED" && o.status === "DELIVERED") celebrate();
    if (before && before !== "CANCELLED" && o.status === "CANCELLED") toast("An order was cancelled — see the 🚫 card", "info");
    previousStatuses[o.id] = o.status;
  }
}

function statCards(stats) {
  const active = (stats.byStatus.ORDERED || 0) + (stats.byStatus.SHIPPED || 0) +
    (stats.byStatus.IN_TRANSIT || 0) + (stats.byStatus.OUT_FOR_DELIVERY || 0) + (stats.byStatus.EXCEPTION || 0);
  const items = [
    { ico: "🚚", num: active, lbl: "Active" },
    { ico: "🛵", num: stats.byStatus.OUT_FOR_DELIVERY || 0, lbl: "Out for delivery" },
    { ico: "⚠️", num: stats.atRisk, lbl: "At risk" },
    { ico: "✅", num: stats.delivered, lbl: "Delivered" },
  ];
  return el("div", { class: "stat-grid" },
    ...items.map((it) => {
      const numNode = el("div", { class: "num" }, "0");
      requestAnimationFrame(() => counter(numNode, it.num));
      return el("div", { class: "card stat" },
        el("div", { class: "ico", "aria-hidden": "true" }, it.ico), numNode, el("div", { class: "lbl" }, it.lbl));
    }));
}

function attentionStrip(orders) {
  const risky = orders.filter((o) => o.risk && o.risk.level !== "LOW"
    && o.status !== "DELIVERED" && o.status !== "CANCELLED");
  if (!risky.length) return null;
  return el("div", { class: "attention" },
    el("div", { class: "t" }, "⚠️ Needs attention"),
    el("div", { class: "row" },
      ...risky.map((o) => el("button", {
        class: `chip ${o.risk.level.toLowerCase()}`,
        onclick: () => openDetail(o.id),
        style: "cursor:pointer",
      }, `${o.platform.charAt(0) + o.platform.slice(1).toLowerCase()} — ${maskTracking(o.trackingNumber)} (${o.risk.level.toLowerCase()} risk)`))));
}

function filterBar(content) {
  const chip = (label, key, value) => {
    const on = filters[key] === value;
    return el("button", { class: `chip fchip${on ? " on" : ""}`, onclick: () => { filters[key] = on ? "" : value; load(content); } }, label);
  };
  const search = el("input", {
    class: "input", placeholder: "Search tracking / courier…", value: filters.q, "aria-label": "Search orders",
  });
  let debounce;
  search.addEventListener("input", () => {
    clearTimeout(debounce);
    debounce = setTimeout(() => { filters.q = search.value; load(content); }, 350);
  });
  const sort = el("select", { class: "input", "aria-label": "Sort orders" },
    el("option", { value: "-lastUpdated" }, "Latest update"),
    el("option", { value: "eta" }, "Delivery ETA"),
    el("option", { value: "risk" }, "Highest risk"));
  sort.value = filters.sort;
  sort.addEventListener("change", () => { filters.sort = sort.value; load(content); });

  return el("div", { class: "filters" },
    el("div", { class: "chips" },
      chip("Shipped", "status", "SHIPPED"), chip("In transit", "status", "IN_TRANSIT"),
      chip("Out for delivery", "status", "OUT_FOR_DELIVERY"), chip("Delivered", "status", "DELIVERED")),
    el("div", { class: "chips" },
      chip("Flipkart", "platform", "FLIPKART"), chip("Amazon", "platform", "AMAZON"),
      chip("Myntra", "platform", "MYNTRA"), chip("Nykaa", "platform", "NYKAA")),
    search, sort);
}

function emptyGrid(content) {
  return emptyState("📦", "No orders here yet",
    "Add a tracking number, or simulate a forwarded email from the Demo Control Panel.",
    el("button", { class: "btn small", onclick: () => openAddModal(content) }, "+ Add your first order"));
}

function orderGrid(orders, content) {
  return el("div", { class: "order-grid" },
    ...orders.map((o, i) => orderCard(o, content, i)));
}

function orderCard(o, content, index) {
  const risk = o.risk && o.status !== "DELIVERED" && o.status !== "CANCELLED"
    ? el("span", { class: `chip ${o.risk.level.toLowerCase()}`, title: o.risk.message || "" },
        riskIcon(o.risk.level), " ", o.risk.level === "LOW" ? "On track" : "Possible delay")
    : o.status === "DELIVERED" ? el("span", { class: "chip low" }, "🎉 Delivered")
    : o.status === "CANCELLED" ? el("span", { class: "chip high", title: "Cancellation email received from the retailer" }, "🚫 Cancelled") : null;

  const fill = statusStep(o.status);
  const exception = o.status === "EXCEPTION" || o.status === "CANCELLED";
  const segs = Array.from({ length: 5 }, (_, i) =>
    el("div", { class: `seg${i <= fill ? (exception ? " exception" : " fill") : ""}` }));

  const refreshBtn = el("button", {
    class: "btn ghost small", "aria-label": "Refresh this order", title: "Refresh",
  }, "⟳");
  refreshBtn.addEventListener("click", async (ev) => {
    ev.stopPropagation();
    refreshBtn.textContent = "◌";
    refreshBtn.style.animation = "spin 0.8s linear infinite";
    try { await api(`/orders/${o.id}/refresh`, { method: "POST" }); toast("Order refreshed", "ok"); await load(content); }
    catch (e) { toast(e.message, "err"); }
    refreshBtn.style.animation = "";
  });

  return el("article", {
    class: "card order-card", role: "button", tabindex: "0",
    style: `animation-delay:${Math.min(index * 0.05, 0.4)}s`,
    onclick: () => openDetail(o.id),
    onkeydown: (e) => e.key === "Enter" && openDetail(o.id),
  },
    el("div", { class: "oc-top" },
      el("span", { class: "oc-title" }, platformName(o.platform)),
      refreshBtn),
    el("div", { class: "oc-meta" },
      el("span", { class: "chip platform", style: `color:${PLATFORM_COLORS[o.platform] || ""}` }, o.platform),
      o.courier ? el("span", { class: "chip courier" }, o.courier) : null,
      o.trackingMode === "EMAIL_ONLY" ? el("span", { class: "chip", title: "Live scanning unavailable — followed via emails" }, "✉️ email-only") : null,
      o.trackingMode === "EMAIL_PENDING" ? el("span", { class: "chip", title: "Order placed — tracking number will appear when the shipping email arrives" }, "⏳ placed · tracking soon") : null,
      o.trackingMode === "PAGE_ONLY" ? el("span", { class: "chip", title: "The store doesn't share a tracking number — updated from its order page via the EkRaah extension" }, "🧩 via store page") : null,
      risk),
    el("div", { class: "oc-track" }, o.trackingMode === "EMAIL_PENDING" ? "Tracking number on its way…" : maskTracking(o.trackingNumber)),
    el("div", { class: "progress", "aria-label": "Delivery progress" }, segs),
    el("div", { class: "oc-bottom" },
      statusPill(o.status),
      el("span", {}, o.status === "DELIVERED" ? "Delivered " + timeAgo(o.deliveredAt)
        : o.status === "CANCELLED" ? "Cancelled " + timeAgo(o.lastUpdated)
        : "ETA " + fmtDate(o.estimatedDelivery)),
      el("span", {}, timeAgo(o.lastUpdated))));
}

function riskIcon(level) {
  return level === "HIGH" ? "🌧️" : level === "MEDIUM" ? "⛅" : "🌤️";
}

function platformName(p) {
  return p.charAt(0) + p.slice(1).toLowerCase().replace("flipkart", "Flipkart");
}

/* ---------------- detail slide-over ---------------- */

export async function openDetail(orderId) {
  const overlay = document.getElementById("overlay-root");
  let detail;
  try { detail = await api(`/orders/${orderId}`); } catch (e) { toast(e.message, "err"); return; }
  const o = detail.order;

  const close = () => overlay.replaceChildren();
  const bg = el("div", { class: "slideover-bg", onclick: close });
  const panel = el("aside", { class: "slideover", role: "dialog", "aria-label": "Order details" });

  const weather = detail.weather;
  const risk = o.risk;
  panel.replaceChildren(
    el("div", { style: "display:flex;justify-content:space-between;align-items:center" },
      el("h2", { style: "font-size:18px" }, platformName(o.platform)),
      el("button", { class: "btn ghost small", onclick: close, "aria-label": "Close" }, "✕")),
    el("div", { class: "oc-meta", style: "margin-top:12px" },
      el("span", { class: "chip platform", style: `color:${PLATFORM_COLORS[o.platform]}` }, o.platform),
      o.courier ? el("span", { class: "chip courier" }, o.courier) : null,
      statusPill(o.status)),
    el("div", { class: "oc-track", style: "margin:10px 0" },
      o.trackingMode === "EMAIL_PENDING" ? "Tracking number on its way…" : maskTracking(o.trackingNumber),
      o.trackingMode !== "EMAIL_PENDING" ? " " : null,
      o.trackingMode !== "EMAIL_PENDING" ? el("button", { class: "chip", style: "cursor:pointer", onclick: () => {
        navigator.clipboard.writeText(o.trackingNumber).then(() => toast("Tracking number copied", "ok"));
      } }, "📋 copy") : null),
    o.trackingMode === "EMAIL_ONLY" ? el("div", { class: "info-banner" },
      el("b", {}, "Tracked via email only. "),
      "Amazon's internal tracking numbers can't be read by third-party APIs, so this order follows the shipping emails forwarded from Amazon.") : null,

    o.trackingMode === "PAGE_ONLY" ? el("div", { class: "info-banner" },
      el("b", {}, "Followed via the store's order page. "),
      o.status === "DELIVERED" || o.status === "CANCELLED"
        ? "This order is finished, so the store's order ID is all we keep — nothing left to track."
        : "This store shows only its order ID, not the courier's tracking number. Click Send in the EkRaah extension on the order page to update the status — or, if the courier sent you a tracking number (e.g. by SMS), add it below for live tracking.") : null,

    o.trackingMode === "PAGE_ONLY" && o.status !== "DELIVERED" && o.status !== "CANCELLED"
      ? attachTrackingForm(o, close) : null,

    o.trackingMode === "EMAIL_PENDING" ? el("div", { class: "info-banner" },
      el("b", {}, "Order placed — tracking number coming. "),
      "We got your order confirmation from the forwarded email. The tracking number will be picked up automatically from the retailer's shipped email — usually within a few hours, and the card updates itself.") : null,

    o.currentCity ? el("div", { class: "weather-card" },
      el("span", { class: "ico", "aria-hidden": "true" }, weatherIcon(weather)),
      el("div", {},
        el("div", { style: "font-weight:800" }, `Now near ${o.currentCity}`),
        el("div", { style: "color:var(--text-soft);font-size:12.5px" }, weather
          ? `${Math.round(weather.tempC)}°C · ${weather.precipitationMm24h.toFixed(1)} mm rain/24h · ${Math.round(weather.windKmph)} km/h wind`
            + (weather.windGustsKmph > weather.windKmph ? ` (gusts ${Math.round(weather.windGustsKmph)})` : "")
            + (weather.precipitationProbabilityPct > 0 ? ` · ${Math.round(weather.precipitationProbabilityPct)}% rain chance` : "")
            + (weather.source === "mock" ? " · demo weather" : " · live forecast")
          : "Weather unavailable right now"))) : null,

    risk && o.status !== "DELIVERED" && o.status !== "CANCELLED" ? el("div", { class: `gauge ${risk.level.toLowerCase()}` },
      el("div", {},
        el("div", { class: "score" }, `${risk.score}`),
        el("div", { style: "font-size:11px;color:var(--text-soft)" }, "risk score")),
      el("div", { style: "flex:1" },
        el("div", { style: "font-weight:800" }, `${risk.level} delay risk`),
        el("div", { style: "font-size:13px;margin-top:4px" }, risk.message || ""),
        risk.reasons?.length ? el("details", { class: "why" },
          el("summary", { style: "cursor:pointer;font-weight:700;font-size:12.5px;color:var(--accent)" }, "Why we think this"),
          el("ul", {}, ...risk.reasons.map((r) => el("li", {}, r)))) : null)) : null,

    o.status === "DELIVERED" ? el("div", { class: "gauge low" },
      el("div", { style: "font-weight:800" }, "🎉 Delivered" + (o.deliveredAt ? " " + timeAgo(o.deliveredAt) : ""))) : null,

    el("h3", { style: "font-size:14px;margin-top:18px" }, "Timeline"),
    timeline(o),

    el("div", { style: "display:flex;gap:10px;margin-top:20px" },
      el("button", { class: "btn small", onclick: async () => {
        try { await api(`/orders/${o.id}/refresh`, { method: "POST" }); toast("Refreshed", "ok"); close(); renderDashboard(); }
        catch (e) { toast(e.message, "err"); }
      } }, "⟳ Refresh"),
      el("button", { class: "btn danger small", onclick: async () => {
        if (!confirm("Remove this order from your dashboard?")) return;
        try { await api(`/orders/${o.id}`, { method: "DELETE" }); toast("Order removed", "ok"); close(); renderDashboard(); }
        catch (e) { toast(e.message, "err"); }
      } }, "🗑 Delete")),
  );
  overlay.replaceChildren(bg, panel);
}

function timeline(o) {
  const events = [...(o.events || [])].sort((a, b) => Date.parse(b.time) - Date.parse(a.time));
  if (!events.length) return el("p", { class: "empty" }, "No events yet.");
  return el("div", { class: "timeline" },
    ...events.map((ev, i) => el("div", { class: "tl-item" },
      el("div", { class: "tl-dot" }),
      el("div", { class: "tl-body" },
        el("div", { class: "d" }, ev.description || "Update"),
        el("div", { class: "m" },
          [ev.location, fmtDateTime(ev.time)].filter(Boolean).join(" · "))))));
}

/* ---------------- add tracking number to a page-only order ---------------- */

function attachTrackingForm(o, close) {
  const input = el("input", { class: "input", placeholder: "e.g. FMPP1234567890 from the courier's SMS", "aria-label": "Courier tracking number" });
  const btn = el("button", { class: "btn small", style: "margin-top:8px" }, "Track live");
  btn.addEventListener("click", async () => {
    const number = input.value.trim();
    if (!number) { input.focus(); return; }
    btn.disabled = true;
    try {
      await api(`/orders/${o.id}/tracking`, { method: "POST", body: { trackingNumber: number } });
      toast("Tracking number added — fetching live status", "ok");
      close(); renderDashboard();
    } catch (e) { toast(e.message, "err"); btn.disabled = false; }
  });
  input.addEventListener("keydown", (e) => e.key === "Enter" && btn.click());
  return el("div", { style: "margin:10px 0" },
    el("label", { class: "lbl" }, "Have the courier's tracking number?"), input, btn);
}

/* ---------------- add order modal ---------------- */

function openAddModal(content) {
  const overlay = document.getElementById("overlay-root");
  const close = () => overlay.replaceChildren();
  const input = el("input", { class: "input", placeholder: "e.g. FMP4471203 or 11234567891", "aria-label": "Tracking number" });
  const detect = el("span", { class: "chip", style: "visibility:hidden" });
  let debounce;
  input.addEventListener("input", () => {
    clearTimeout(debounce);
    debounce = setTimeout(async () => {
      const n = input.value.trim();
      if (n.length < 6) { detect.style.visibility = "hidden"; return; }
      const res = await api(`/orders/detect-courier?number=${encodeURIComponent(n)}`).catch(() => ({ courier: "" }));
      detect.textContent = res.courier ? "Courier: " + res.courier : "Courier: auto-detect at carrier";
      detect.style.visibility = "visible";
    }, 300);
  });
  const btn = el("button", { class: "btn", style: "width:100%;margin-top:16px" }, "Track it");
  btn.addEventListener("click", async () => {
    btn.disabled = true;
    try {
      await api("/orders", { method: "POST", body: { trackingNumber: input.value.trim() } });
      toast("Order added — fetching live status", "ok");
      close(); renderDashboard();
    } catch (e) { toast(e.message, "err"); btn.disabled = false; }
  });
  input.addEventListener("keydown", (e) => e.key === "Enter" && btn.click());

  overlay.replaceChildren(
    el("div", { class: "modal-bg", onclick: (e) => e.target === e.currentTarget && close() },
      el("div", { class: "modal", role: "dialog", "aria-label": "Add order" },
        el("h2", { style: "font-size:17px;margin-bottom:6px" }, "Add an order"),
        el("p", { style: "color:var(--text-soft);font-size:13px;margin-bottom:16px" },
          "Paste any Ekart / Delhivery / Blue Dart / Xpressbees / Shadowfax / India Post number."),
        el("label", { class: "lbl" }, "Tracking number"), input,
        el("div", { style: "margin-top:10px" }, detect), btn)));
}

/* ---------------- demo control panel ---------------- */

function mountDemoPanel() {
  if (!state.demoMode) return;
  const overlay = document.getElementById("overlay-root");

  const fab = el("button", { class: "btn ghost demo-fab", "aria-label": "Open demo control panel" }, "🎛️ Demo panel");
  let drawer = null;

  fab.addEventListener("click", async () => {
    if (drawer) { drawer.remove(); drawer = null; return; }
    const samples = await api("/dev/samples").catch(() => []);
    const list = el("div", { class: "grid" },
      ...samples.filter((s) => !s.name.startsWith("Gmail")).map((s) =>
        el("button", {
          class: "btn ghost small", style: "justify-content:flex-start;text-align:left",
          onclick: async () => {
            try {
              const { name, ...payload } = s;
              const res = await api("/dev/simulate-email", { method: "POST", body: payload });
              toast(`${name}: ${res.outcome}`, res.outcome === "CREATED" || res.outcome === "UPDATED" || res.outcome === "FORWARDING_CODE_SAVED" ? "ok" : "info");
              renderDashboard();
            } catch (e) { toast(e.message, "err"); }
          },
        }, "✉️ " + s.name)));
    const stormy = el("button", { class: "btn small" }, "🌧️ Stormy");
    const clear = el("button", { class: "btn ghost small" }, "🌤️ Clear");
    stormy.addEventListener("click", async () => {
      await api("/dev/set-weather?mode=stormy", { method: "POST" });
      toast("Weather set to stormy — reload risk scores", "ok");
      renderDashboard();
    });
    clear.addEventListener("click", async () => {
      await api("/dev/set-weather?mode=clear", { method: "POST" });
      toast("Weather set to clear", "ok");
      renderDashboard();
    });
    const poll = el("button", { class: "btn ghost small", style: "width:100%", onclick: async () => {
      const r = await api("/dev/poll-now", { method: "POST" });
      toast(`IMAP poll: ${r.processed} processed`, "ok");
    } }, "📬 Poll inbox now");

    drawer = el("div", { class: "card demo-drawer", role: "dialog", "aria-label": "Demo control panel" },
      el("h3", {}, "Demo control panel", el("button", { class: "chip", style: "cursor:pointer", onclick: () => { drawer.remove(); drawer = null; } }, "✕")),
      list,
      el("div", { class: "row2" }, stormy, clear),
      el("div", { style: "height:8px" }), poll);
    overlay.append(drawer);
  });

  document.querySelector(".demo-fab")?.remove();
  document.querySelector(".demo-drawer")?.remove();
  document.body.append(fab);
}
