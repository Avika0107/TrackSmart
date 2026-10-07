import { api, downloadFile } from "../api.js";
import { el, toast, fmtDateTime } from "../ui.js";
import { layout, state } from "../app.js";

export function renderSetup() {
  let step = 0;
  let info = null;
  let pollTimer = null;
  const wrap = el("div", { class: "setup-card card" });

  async function refreshInfo() {
    info = await api("/setup/info");
    state.demoMode = info.demoMode;
    if (info.forwardingVerified && step < 3) step = 3;
    draw();
  }

  function draw() {
    const bars = el("div", { class: "stepper" },
      ...[0, 1, 2, 3].map((i) => el("div", { class: "bar" + (i <= step ? " done" : ""), "aria-hidden": "true" })));

    const steps = [
      { title: "Copy your tracking address", body: addressStep },
      { title: "Set up Gmail forwarding", body: gmailStep },
      { title: "Verify with Google's code", body: codeStep },
      { title: "Waiting for your first email…", body: waitStep },
    ];

    wrap.replaceChildren(
      ...[
      bars,
      el("h2", { style: "font-size:19px;margin-bottom:4px" }, `Step ${step + 1} of 4 — ${steps[step].title}`),
      el("div", { style: "color:var(--text-soft);font-size:13.5px;margin-bottom:16px" },
        "Forwarding retailer emails is how ParcelPilot sees your orders — retailers don't share order data with third parties."),
      steps[step].body(),
      step < 3 ? el("div", { style: "display:flex;justify-content:space-between;margin-top:22px" },
        el("button", { class: "btn ghost small", onclick: () => { step = Math.max(0, step - 1); draw(); } }, "← Back"),
        el("div", { style: "display:flex;gap:8px" },
          el("button", {
            class: "btn ghost small",
            onclick: () => { clearInterval(pollTimer); location.hash = "#/dashboard"; toast("You can finish setup anytime from the Setup tab", "ok"); },
          }, "Skip — I'll add numbers manually"),
          el("button", { class: "btn small", onclick: () => { step = Math.min(3, step + 1); draw(); } }, "Next →"))) : null,
      el("div", { class: "privacy-box" },
        el("b", {}, "Privacy promise. "), "We store ONLY tracking facts: tracking number, courier, status, city and dates. ",
        "We never store email text, item names, prices or addresses — see the Privacy tab for the live audit log."),
      ].filter(Boolean));
  }

  function addressStep() {
    if (!info) return el("p", { class: "empty" }, "Loading…");
    const input = el("input", { class: "input", readonly: "", value: info.forwardingAddress, "aria-label": "Your tracking address" });
    const btn = el("button", { class: "btn small" }, "📋 Copy");
    btn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(info.forwardingAddress);
        btn.textContent = "✅ Copied!";
        toast("Tracking address copied", "ok");
        setTimeout(() => (btn.textContent = "📋 Copy"), 1800);
      } catch { toast("Copy failed — select and copy manually", "err"); }
    });
    return el("div", {},
      el("p", { style: "font-size:14px;margin-bottom:10px" },
        "This is your personal ParcelPilot address. Emails forwarded here become live order cards."),
      el("div", { class: "copy-row" }, input, btn),
      el("p", { style: "font-size:12.5px;color:var(--text-soft)" },
        `Your alias: ${info.alias} — keep it private, anyone who knows it can add orders to your dashboard.`));
  }

  function gmailStep() {
    const dl = el("button", { class: "btn small" }, "⬇️ Download Gmail filter file");
    dl.addEventListener("click", async () => {
      try {
        await downloadFile("/setup/gmail-filter.xml", "parcelpilot-gmail-filter.xml");
        toast("Filter file downloaded", "ok");
      } catch (e) { toast(e.message, "err"); }
    });
    return el("div", {},
      dl,
      el("ol", { class: "howto" },
        el("li", {}, "Open ", el("b", {}, "Gmail → Settings (gear) → See all settings → Forwarding and POP/IMAP")),
        el("li", {}, "Click ", el("b", {}, "Add a forwarding address"), " and enter your ParcelPilot address"),
        el("li", {}, "Gmail sends a verification email — that's step 3"),
        el("li", {}, "Then import the downloaded filter: ", el("b", {}, "Settings → Filters → Import filters"),
          " — it auto-forwards only retailer shipping emails")));
  }

  function codeStep() {
    const input = el("input", { class: "input", placeholder: "Paste the 9-digit confirmation code", inputmode: "numeric" });
    const status = el("p", { style: "font-size:13px;margin-top:10px" });
    async function check() {
      info = await api("/setup/info").catch(() => info);
      if (info?.confirmationCode) {
        status.replaceChildren(el("span", { class: "chip low" }, "✅ Code detected automatically: " + info.confirmationCode));
      } else {
        status.replaceChildren(el("span", { style: "color:var(--text-soft)" },
          "No code detected yet — paste it here if Gmail asked you to enter one manually (this demo inbox is shared, so we show it to every user)."));
      }
    }
    const btn = el("button", { class: "btn small", style: "margin-top:10px" }, "Check for code");
    btn.addEventListener("click", async () => { await check(); toast(info?.confirmationCode ? "Code found" : "No code yet", "ok"); });
    check();
    return el("div", {}, input, btn, status);
  }

  function waitStep() {
    const statusLine = el("p", { style: "font-size:14px;display:flex;align-items:center;gap:10px" },
      el("span", { class: "waiting-dot" }), "Listening for your first forwarded email…");
    clearInterval(pollTimer);
    pollTimer = setInterval(async () => {
      info = await api("/setup/info").catch(() => info);
      if (info?.forwardingVerified) {
        clearInterval(pollTimer);
        statusLine.replaceChildren(el("span", { class: "waiting-dot ok" }),
          el("b", {}, " First order email received — you're all set! 🎉"));
        draw();
      }
    }, 5000);
    return el("div", {},
      statusLine,
      el("p", { style: "font-size:12.5px;color:var(--text-soft);margin-top:8px" },
        "Tip: use the Demo Control Panel (bottom-left) to simulate an order email instead of waiting."),
      info?.confirmationCode ? el("p", { style: "font-size:13px;margin-top:8px" },
        el("span", { class: "chip medium" }, "Gmail code: " + info.confirmationCode)) : null);
  }

  layout(wrap, "#/setup");
  refreshInfo().then(draw);
  return wrap;
}
