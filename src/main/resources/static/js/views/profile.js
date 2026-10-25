import { api, downloadFile } from "../api.js";
import { el, toast, fmtDate, emptyState } from "../ui.js";
import { layout, state, setTheme, logout } from "../app.js";

export function renderProfile() {
  const content = el("div", {});
  layout(content, "#/profile");
  load(content);
}

export function renderSettings() {
  const content = el("div", {});
  layout(content, "#/settings");
  content.replaceChildren(
    el("h2", { style: "margin:2px 0 14px;font-size:20px" }, "⚙️ Settings"),
    el("p", { style: "color:var(--text-soft);font-size:13px;margin:0 0 14px" },
      "Changes save automatically as you toggle them."),
    preferencesCard());
}

async function load(content) {
  content.replaceChildren(el("div", { class: "card skeleton", style: "min-height:220px" }));
  let stats, info;
  try {
    [stats, info] = await Promise.all([api("/stats"), api("/setup/info")]);
  } catch (e) {
    content.replaceChildren(emptyState("👤", "Couldn't load profile", e.message));
    return;
  }
  content.replaceChildren(
    el("div", { class: "profile-grid" },
      personalCard(), statsCard(stats), trackingCard(info),
      el("div", { class: "wide" }, preferencesCard()),
      el("div", { class: "wide" }, dataCard()),
      el("div", { class: "wide" }, dangerCard())));
}

function cardTitle(icon, text) {
  return el("h3", { class: "card-title" }, icon + " " + text);
}

// ---- (a) Personal details -------------------------------------------------

function personalCard() {
  const me = state.me;
  const nameInput = el("input", {
    class: "input", value: me?.displayName || "", maxlength: "40",
    "aria-label": "Display name", placeholder: "Your name",
  });
  const save = async () => {
    const name = nameInput.value.trim().replace(/\s+/g, " ");
    if (name.length < 2 || name.length > 40) {
      toast("Display name must be 2-40 characters", "err");
      return;
    }
    try {
      await api("/me", { method: "PATCH", body: { displayName: name } });
      await refreshMe();
      toast("Profile updated", "ok");
    } catch (e) { toast(e.message, "err"); }
  };
  nameInput.addEventListener("keydown", (e) => e.key === "Enter" && save());
  const saveBtn = el("button", { class: "btn small", onclick: save }, "Save");

  const prettyPhone = (p) => (p && p.length === 10 ? `+91 ${p.slice(0, 5)} ${p.slice(5)}` : p || "—");

  return el("div", { class: "card pcard" },
    cardTitle("🙋", "Personal details"),
    el("label", { class: "lbl" }, "Display name"),
    el("div", { class: "copy-row" }, nameInput, saveBtn),
    el("div", { class: "kv" }, el("span", { class: "k" }, "Phone"),
      el("span", { class: "mono" }, prettyPhone(me?.phone))),
    el("div", { style: "font-size:11.5px;color:var(--text-soft);margin:-4px 0 8px" },
      "Visible only to you — never shown elsewhere in the app."),
    el("div", { class: "kv" }, el("span", { class: "k" }, "Member since"),
      el("span", {}, fmtDate(me?.createdAt))));
}

// ---- (b) Account stats with animated counters ------------------------------

function statsCard(stats) {
  const tile = (label, value) => {
    const num = el("div", { class: "num" }, "0");
    countUp(num, Number(value) || 0);
    return el("div", { class: "stat-tile" }, num, el("div", { class: "lbl" }, label));
  };
  return el("div", { class: "card pcard" },
    cardTitle("📊", "Account stats"),
    el("div", { class: "stat-row" },
      tile("Orders tracked", stats.total),
      tile("Delivered", stats.delivered),
      tile("At risk", stats.atRisk)),
    el("div", { style: "font-size:11.5px;color:var(--text-soft);margin-top:10px" },
      "Counted live from your dashboard data."));
}

function countUp(node, target, ms = 900) {
  const t0 = performance.now();
  const frame = (t) => {
    const p = Math.min(1, (t - t0) / ms);
    node.textContent = Math.round(target * (1 - Math.pow(1 - p, 3)));
    if (p < 1) requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}

// ---- (c) Tracking email ----------------------------------------------------

function trackingCard(info) {
  const addr = info?.forwardingAddress || "—";
  const copyBtn = el("button", { class: "btn small", "aria-label": "Copy tracking email address" }, "📋 Copy");
  copyBtn.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(addr);
      toast("Tracking address copied", "ok");
    } catch { toast("Couldn't access clipboard — select and copy manually", "err"); }
  });
  const badge = info?.forwardingVerified
    ? el("span", { class: "chip low" }, "✅ Verified")
    : el("span", { class: "chip medium" }, "⏳ Waiting for first email");
  return el("div", { class: "card pcard" },
    cardTitle("✉️", "Tracking email"),
    el("div", { style: "display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap" },
      el("span", { class: "mono", style: "font-weight:700" }, addr), copyBtn),
    el("div", { style: "margin-top:12px" }, badge),
    el("p", { style: "font-size:12px;color:var(--text-soft);margin:10px 0 0" },
      "Forward retailer shipping emails here. Only allowlisted senders (Amazon, Flipkart, Myntra…) are ingested."));
}

// ---- (d) Preferences — auto-saving ----------------------------------------

function preferencesCard() {
  const me = state.me || {};
  const s = me.settings || {};
  const currentTheme = document.documentElement.dataset.theme || s.theme || "dark";

  const saved = () => toast("Preferences saved", "ok");
  const patch = async (body) => {
    try {
      state.me = await api("/me/settings", { method: "PATCH", body });
      saved();
    } catch (e) { toast(e.message, "err"); }
  };

  const segBtn = (label, theme) => el("button", {
    class: currentTheme === theme ? "on" : "",
    onclick: () => { setTheme(theme); saved(); },
  }, label);
  const themeSeg = el("div", { class: "seg", role: "group", "aria-label": "Theme" },
    segBtn("☀️ Light", "light"), segBtn("🌙 Dark", "dark"));

  const notif = el("input", {
    type: "checkbox", class: "switch", checked: !!s.notificationsOn,
    "aria-label": "Notifications",
  });
  notif.addEventListener("change", () => patch({ notificationsOn: notif.checked }));

  const cityInput = el("input", {
    class: "input", value: s.defaultCity || "", maxlength: "40",
    placeholder: "e.g. Mumbai", "aria-label": "Default city",
  });
  let cityTimer = null;
  cityInput.addEventListener("input", () => {
    clearTimeout(cityTimer);
    cityTimer = setTimeout(() => patch({ defaultCity: cityInput.value.trim() }), 600);
  });

  return el("div", { class: "card pcard" },
    cardTitle("🎛️", "Preferences"),
    el("div", { class: "switch-row" }, el("span", {}, "Theme"), themeSeg),
    el("div", { class: "switch-row" },
      el("span", {}, "Delay-risk notifications"),
      notif),
    el("div", { class: "switch-row" },
      el("span", {}, "Default city"),
      el("div", { style: "flex:1;max-width:220px" }, cityInput)),
    el("div", { style: "font-size:11.5px;color:var(--text-soft);margin-top:6px" },
      "Everything here saves automatically."));
}

// ---- (e) Data & privacy ----------------------------------------------------

function dataCard() {
  const dl = el("button", { class: "btn small" }, "⬇️ Download my data (JSON)");
  dl.addEventListener("click", async () => {
    try {
      await downloadFile("/me/export", "ekraah-export.json");
      toast("Export downloaded", "ok");
    } catch (e) { toast(e.message, "err"); }
  });
  return el("div", { class: "card pcard" },
    cardTitle("🔐", "Data & privacy"),
    el("p", { style: "font-size:13px;color:var(--text-soft);margin:0 0 12px" },
      "Your profile and orders, as JSON. Internal fields and your full phone number are never included."),
    dl,
    el("div", { style: "margin-top:12px" },
      el("a", { href: "#/privacy", class: "linklike" }, "View activity log →")));
}

// ---- (f) Danger zone -------------------------------------------------------

function dangerCard() {
  const btn = el("button", { class: "btn danger" }, "🗑 Delete my account");
  btn.addEventListener("click", confirmDelete);
  return el("div", { class: "card danger-card pcard" },
    cardTitle("⚠️", "Danger zone"),
    el("p", { style: "font-size:13px;color:var(--text-soft);margin:0 0 12px" },
      "Deleting your account removes your profile, every tracked order and your audit log. There is no undo."),
    btn);
}

function confirmDelete() {
  const overlay = document.getElementById("overlay-root");
  const close = () => { overlay.replaceChildren(); document.removeEventListener("keydown", onEsc); };
  const onEsc = (e) => e.key === "Escape" && close();

  const input = el("input", {
    class: "input", placeholder: 'Type DELETE to confirm',
    "aria-label": "Type DELETE to confirm deletion", autocomplete: "off",
  });
  const btn = el("button", { class: "btn danger", style: "flex:1", disabled: true },
    "Permanently delete my account");
  input.addEventListener("input", () => { btn.disabled = input.value !== "DELETE"; });
  btn.addEventListener("click", async () => {
    try {
      await api("/me", { method: "DELETE" });
      toast("Account deleted", "ok");
      logout();
    } catch (e) { toast(e.message, "err"); }
  });

  document.addEventListener("keydown", onEsc);
  overlay.replaceChildren(
    el("div", { class: "modal-bg", onclick: (e) => e.target === e.currentTarget && close() },
      el("div", { class: "modal", role: "alertdialog", "aria-modal": "true", "aria-label": "Delete account" },
        el("h2", { style: "font-size:17px;margin:0 0 8px" }, "Delete your account?"),
        el("p", { style: "color:var(--text-soft);font-size:13.5px;line-height:1.6" },
          "This permanently erases your profile, all tracked orders and your audit log. This cannot be undone."),
        el("label", { class: "lbl", style: "margin-top:10px" }, 'Type "DELETE" to enable the button'),
        input,
        el("div", { style: "display:flex;gap:8px;margin-top:14px" },
          el("button", { class: "btn", style: "flex:1", onclick: close }, "Cancel"),
          btn))));
  input.focus();
}

// refresh state.me (greeting + avatar) after a profile edit
async function refreshMe() {
  state.me = await api("/me");
  window.dispatchEvent(new HashChangeEvent("hashchange"));
}
