import { api } from "../api.js";
import { el, emptyState, PLATFORM_COLORS, statusPill } from "../ui.js";
import { layout } from "../app.js";

export function renderInsights() {
  const content = el("div", {});
  layout(content, "#/insights");
  load(content);
}

async function load(content) {
  content.replaceChildren(el("div", { class: "charts" },
    ...[0, 1, 2].map(() => el("div", { class: "card chart-card skeleton", style: "min-height:280px" }))));
  let stats, orders;
  try {
    [stats, orders] = await Promise.all([api("/stats"), api("/orders")]);
  } catch (e) {
    content.replaceChildren(emptyState("📊", "Couldn't load insights", e.message));
    return;
  }

  const hasData = stats.total > 0;
  const charts = el("div", { class: "charts" });

  // 1. Orders per platform (donut)
  charts.append(el("div", { class: "card chart-card" },
    el("h3", {}, "Orders per platform"),
    chartBox(hasData && Object.keys(stats.byPlatform).length, (box) =>
      new Chart(box, {
        type: "doughnut",
        data: {
          labels: Object.keys(stats.byPlatform),
          datasets: [{ data: Object.values(stats.byPlatform), backgroundColor: Object.keys(stats.byPlatform).map((p) => PLATFORM_COLORS[p] || "#8b93b0"), borderWidth: 0 }],
        },
        options: { maintainAspectRatio: false, plugins: { legend: { position: "bottom", labels: { color: cssVar("--text-soft"), boxWidth: 10 } } } },
      }))));

  // 2. Deliveries over the last 14 days
  const days = last14Days();
  const counts = days.map((d) => orders.filter((o) => o.status === "DELIVERED" && (o.deliveredAt || "").startsWith(d)).length);
  charts.append(el("div", { class: "card chart-card" },
    el("h3", {}, "Deliveries over time (last 14 days)"),
    chartBox(hasData, (box) => new Chart(box, {
      type: "line",
      data: {
        labels: days.map((d) => d.slice(8) + "/" + d.slice(5, 7)),
        datasets: [{ data: counts, borderColor: cssVar("--accent"), backgroundColor: "transparent", tension: 0.35, pointRadius: 3 }],
      },
      options: {
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: { x: { ticks: { color: cssVar("--text-soft"), maxTicksLimit: 7 }, grid: { display: false } },
                  y: { beginAtZero: true, ticks: { color: cssVar("--text-soft"), precision: 0 }, grid: { color: cssVar("--border") } } },
      },
    }))));

  // 3. On-time vs at-risk
  charts.append(el("div", { class: "card chart-card" },
    el("h3", {}, "On track vs at risk"),
    chartBox(hasData, (box) => new Chart(box, {
      type: "bar",
      data: {
        labels: ["On track", "At risk", "Delivered"],
        datasets: [{ data: [stats.onTime, stats.atRisk, stats.delivered], backgroundColor: [cssVar("--accent-2"), cssVar("--medium"), cssVar("--low")], borderRadius: 10 }],
      },
      options: {
        indexAxis: "y", maintainAspectRatio: false, plugins: { legend: { display: false } },
        scales: { x: { beginAtZero: true, ticks: { color: cssVar("--text-soft"), precision: 0 }, grid: { color: cssVar("--border") } },
                  y: { ticks: { color: cssVar("--text-soft") }, grid: { display: false } } },
      },
    }))));

  content.replaceChildren(charts);
}

function chartBox(hasData, makeChart) {
  const box = el("div", { class: "chart-box" });
  if (!hasData || typeof Chart === "undefined") {
    box.append(emptyState(typeof Chart === "undefined" ? "📶" : "🧁",
      typeof Chart === "undefined" ? "Charts library not loaded (offline?)" : "No data yet",
      "Add a few orders and this fills up."));
    return box;
  }
  const canvas = el("canvas", { "aria-label": "Chart", role: "img" });
  box.append(canvas);
  requestAnimationFrame(() => makeChart(canvas));
  return box;
}

function last14Days() {
  return Array.from({ length: 14 }, (_, i) => {
    const d = new Date(Date.now() - (13 - i) * 86400000);
    return d.toISOString().slice(0, 10);
  });
}

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}
