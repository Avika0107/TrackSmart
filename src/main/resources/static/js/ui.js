export function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === "class") node.className = v;
    else if (k === "dataset") Object.assign(node.dataset, v);
    else if (k.startsWith("on")) node.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === "value") node.value = v;
    else if (k === "checked") node.checked = Boolean(v);
    else if (k === "html") node.innerHTML = v;
    else node.setAttribute(k, v === true ? "" : v);
  }
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    node.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return node;
}

export function toast(message, kind = "info", ms = 3200) {
  const box = document.getElementById("toasts");
  const t = el("div", { class: `toast ${kind}` }, message);
  box.append(t);
  setTimeout(() => t.remove(), ms);
}

export function skeletonRow(n = 3) {
  return el("div", { class: "order-grid", "aria-busy": "true" },
    ...Array.from({ length: n }, (_, i) => el("div", { class: "skeleton", style: `animation-delay:${i * 0.12}s` })));
}

export function emptyState(icon, title, hint, cta) {
  return el("div", { class: "empty" },
    el("div", { class: "big", "aria-hidden": "true" }, icon),
    el("div", { style: "font-weight:800;color:var(--text);margin-bottom:6px" }, title),
    el("div", {}, hint),
    cta ? el("div", { style: "margin-top:14px" }, cta) : null);
}

export function timeAgo(instant) {
  if (!instant) return "—";
  const s = Math.max(0, (Date.now() - Date.parse(instant)) / 1000);
  if (s < 60) return "just now";
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min${m > 1 ? "s" : ""} ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} hour${h > 1 ? "s" : ""} ago`;
  const d = Math.floor(h / 24);
  return `${d} day${d > 1 ? "s" : ""} ago`;
}

export function fmtDate(instant) {
  if (!instant) return "—";
  return new Date(instant).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

export function fmtDateTime(instant) {
  if (!instant) return "—";
  return new Date(instant).toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
}

export function maskTracking(n) {
  if (!n) return "—";
  return n.length <= 4 ? "••••" : "••••" + n.slice(-4);
}

/** Animated number counter (respects reduced motion). */
export function counter(node, target) {
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduced || target === 0) { node.textContent = String(target); return; }
  const t0 = performance.now(), dur = 700;
  const tick = (t) => {
    const p = Math.min(1, (t - t0) / dur);
    node.textContent = String(Math.round(target * (1 - Math.pow(1 - p, 3))));
    if (p < 1) requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}

export function celebrate() {
  if (typeof confetti !== "function") return;
  confetti({ particleCount: 130, spread: 75, origin: { y: 0.7 }, zIndex: 300 });
  setTimeout(() => confetti({ particleCount: 60, angle: 60, spread: 60, origin: { x: 0, y: 0.8 }, zIndex: 300 }), 220);
  setTimeout(() => confetti({ particleCount: 60, angle: 120, spread: 60, origin: { x: 1, y: 0.8 }, zIndex: 300 }), 340);
}

export const STATUS_LABEL = {
  ORDERED: "Ordered", SHIPPED: "Shipped", IN_TRANSIT: "In transit", OUT_FOR_DELIVERY: "Out for delivery",
  DELIVERED: "Delivered", EXCEPTION: "Exception", UNKNOWN: "Pending",
};

/** 0..4 progress position for the 5-step bar (exceptions stay at step 3, red). */
export function statusStep(status) {
  switch (status) {
    case "ORDERED": return 0;
    case "SHIPPED": return 1;
    case "IN_TRANSIT": return 2;
    case "OUT_FOR_DELIVERY": return 3;
    case "DELIVERED": return 4;
    case "EXCEPTION": return 3;
    default: return 0;
  }
}

export function statusPill(status) {
  const colors = {
    ORDERED: "var(--text-soft)", SHIPPED: "var(--accent)", IN_TRANSIT: "var(--accent)",
    OUT_FOR_DELIVERY: "var(--accent-2)", DELIVERED: "var(--low)", EXCEPTION: "var(--high)", UNKNOWN: "var(--text-soft)",
  };
  const bg = {
    ORDERED: "var(--card)", SHIPPED: "color-mix(in srgb, var(--accent) 15%, transparent)",
    IN_TRANSIT: "color-mix(in srgb, var(--accent) 15%, transparent)",
    OUT_FOR_DELIVERY: "color-mix(in srgb, var(--accent-2) 16%, transparent)",
    DELIVERED: "var(--low-bg)", EXCEPTION: "var(--high-bg)", UNKNOWN: "var(--card)",
  };
  return el("span", { class: "status-pill", style: `color:${colors[status] || "var(--text-soft)"};background:${bg[status] || "var(--card)"}` },
    STATUS_LABEL[status] || status);
}

export const PLATFORM_COLORS = {
  AMAZON: "#ff9900", FLIPKART: "#2874f0", MYNTRA: "#ff3f6c", NYKAA: "#fc2779", AJIO: "#2c4152", OTHER: "#8b93b0",
};

export function weatherIcon(snap) {
  if (!snap) return "📦";
  if (snap.wmoCode >= 95) return "⛈️";
  if (snap.precipitationMm24h > 5) return "🌧️";
  if (snap.wmoCode >= 45 && snap.wmoCode <= 48) return "🌫️";
  if (snap.tempC >= 40) return "🔥";
  return "🌤️";
}
