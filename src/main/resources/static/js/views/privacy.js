import { api } from "../api.js";
import { el, toast, fmtDateTime, emptyState } from "../ui.js";
import { layout, logout } from "../app.js";

export function renderPrivacy() {
  const content = el("div", {});
  layout(content, "#/privacy");
  load(content);
}

async function load(content) {
  content.replaceChildren(el("div", { class: "card skeleton", style: "min-height:200px" }));
  let audit;
  try { audit = await api("/privacy/audit"); } catch (e) {
    content.replaceChildren(emptyState("🛡️", "Couldn't load audit log", e.message)); return;
  }

  const table = audit.length
    ? el("table", { class: "audit-table" },
        el("thead", {}, el("tr", {},
          el("th", {}, "When"), el("th", {}, "Sender"), el("th", {}, "What happened"))),
        el("tbody", {}, ...audit.map((a) => el("tr", {},
          el("td", {}, fmtDateTime(a.time)),
          el("td", {}, a.senderDomain || "—"),
          el("td", {}, el("span", { class: `action ${a.action}` }, humanAction(a.action)), " ", a.summary || "")))))
    : emptyState("🗂️", "Nothing here yet", "Forward an email or simulate one from the demo panel.");

  const deleteBtn = el("button", { class: "btn danger" }, "🗑 Delete my data");
  deleteBtn.addEventListener("click", () => confirmModal(content));

  content.replaceChildren(
    el("div", { class: "card", style: "padding:22px;margin-bottom:18px" },
      el("h2", { style: "font-size:17px;margin-bottom:8px" }, "How ParcelPilot handles your data"),
      el("p", { style: "font-size:13.5px;color:var(--text-soft);line-height:1.7" },
        "We store only tracking facts: courier, tracking number, status, city and dates — never raw email text, item names, prices or addresses. ",
        "Everything below is the same audit trail your mail pipeline writes, in plain language. Delete your account and every row disappears.")),
    el("div", { class: "card", style: "padding:10px 6px" }, table),
    el("div", { class: "card", style: "padding:18px;margin-top:18px;display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap" },
      el("div", {},
        el("b", {}, "Right to be forgotten. "),
        el("span", { style: "color:var(--text-soft);font-size:13px" }, "Deleting your account removes your profile, orders and audit log permanently.")),
      deleteBtn));
}

function humanAction(action) {
  return {
    PROCESSED: "Processed",
    IGNORED_NOT_ALLOWED_SENDER: "Ignored (sender not allowed)",
    IGNORED_NO_TRACKING: "Ignored (no tracking)",
    DUPLICATE: "Duplicate",
    ERROR: "Error",
  }[action] || action;
}

function confirmModal(content) {
  const overlay = document.getElementById("overlay-root");
  const close = () => overlay.replaceChildren();
  const btn = el("button", { class: "btn danger", style: "width:100%" }, "Yes, delete everything");
  btn.addEventListener("click", async () => {
    try {
      await api("/me", { method: "DELETE" });
      toast("Your data has been deleted", "ok");
      logout();
    } catch (e) { toast(e.message, "err"); }
  });
  overlay.replaceChildren(
    el("div", { class: "modal-bg", onclick: (e) => e.target === e.currentTarget && close() },
      el("div", { class: "modal", role: "alertdialog", "aria-label": "Confirm deletion" },
        el("h2", { style: "font-size:17px;margin-bottom:8px" }, "Delete your account?"),
        el("p", { style: "color:var(--text-soft);font-size:13.5px;line-height:1.6" },
          "This permanently removes your profile, all orders and your audit log. This cannot be undone."),
        btn, close)));
}
