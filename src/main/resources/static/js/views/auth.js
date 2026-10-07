import { api, setToken, isLoggedIn } from "../api.js";
import { el, toast } from "../ui.js";

export function renderLogin() {
  const app = document.getElementById("app");
  app.replaceChildren(loginScreen());
}

function loginScreen() {
  let step = "login"; // login | signup-phone | signup-otp | forgot-phone | forgot-otp
  let phone = "";
  let resendTimer = null;

  const card = el("div", { class: "auth-card card", role: "main" });

  function render() {
    clearTimeout(resendTimer);
    card.replaceChildren(
      el("div", { class: "brand", style: "padding:0 0 16px" },
        el("span", { class: "logo", "aria-hidden": "true" }, "📦"), "ParcelPilot"),
      ...(step === "login" ? loginStep()
        : step === "signup-phone" ? phoneStep("signup")
        : step === "signup-otp" ? otpStep("signup")
        : step === "forgot-phone" ? phoneStep("forgot")
        : otpStep("forgot")));
  }

  // ---- Log in: phone + password -------------------------------------------
  function loginStep() {
    const input = el("input", {
      class: "input", type: "tel", placeholder: "98765 43210", "aria-label": "Mobile number",
      value: phone, autocomplete: "tel", inputmode: "numeric",
    });
    const pass = el("input", {
      class: "input", type: "password", placeholder: "Your password",
      "aria-label": "Password", autocomplete: "current-password",
    });
    const btn = el("button", { class: "btn", style: "width:100%;margin-top:16px" }, "Log in");

    async function submit() {
      phone = input.value.trim();
      const password = pass.value;
      if (!phone) { toast("Enter your mobile number", "err"); return; }
      if (!password) { toast("Enter your password", "err"); return; }
      btn.disabled = true;
      try {
        const res = await api("/auth/login", { method: "POST", body: { phone, password } });
        setToken(res.token);
        location.hash = res.user.forwardingVerified ? "#/dashboard" : "#/setup";
        routeAfterLogin();
      } catch (e) {
        toast(e.message, "err");
        // First run of an account created before passwords existed: guide them to signup
        if ((e.message || "").includes("No password set")) { step = "signup-phone"; render(); return; }
        btn.disabled = false;
      }
    }
    btn.addEventListener("click", submit);
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") pass.focus(); });
    pass.addEventListener("keydown", (e) => { if (e.key === "Enter") submit(); });

    return [
      el("h1", {}, "All your orders. One dashboard."),
      el("p", { class: "lede" },
        "Track Amazon, Flipkart, Myntra, Nykaa and more — with live status and weather-based delay alerts."),
      el("div", { class: "steps3" },
        el("div", {}, el("span", { class: "n" }, "1"), "Log in with your phone number and password"),
        el("div", {}, el("span", { class: "n" }, "2"), "Forward retailer shipping emails to your personal tracking address"),
        el("div", {}, el("span", { class: "n" }, "3"), "See every parcel and its delay risk live")),
      el("label", { class: "lbl" }, "Mobile number"),
      el("div", { class: "copy-row" },
        el("span", { class: "chip", style: "padding:11px 14px" }, "+91"), input),
      el("label", { class: "lbl", style: "margin-top:12px" }, "Password"),
      pass,
      btn,
      el("p", { style: "text-align:center;font-size:13px;margin-top:14px" },
        el("button", { type: "button", onclick: () => { step = "signup-phone"; render(); } }, "Create account"),
        "  ·  ",
        el("button", { type: "button", onclick: () => { step = "forgot-phone"; render(); } }, "Forgot password?")),
      el("p", { style: "font-size:12px;color:var(--text-soft);margin-top:10px;text-align:center" },
        "We use your number only to log you in. Order data arrives from forwarded emails — retailers don't share orders by phone."),
    ];
  }

  // ---- Phone step, shared by signup and forgot-password --------------------
  function phoneStep(purpose) {
    const input = el("input", {
      class: "input", type: "tel", placeholder: "98765 43210", "aria-label": "Mobile number",
      value: phone, autocomplete: "tel", inputmode: "numeric",
    });
    const btn = el("button", { class: "btn", style: "width:100%;margin-top:16px" },
      purpose === "signup" ? "Send OTP" : "Send reset code");

    btn.addEventListener("click", async () => {
      phone = input.value.trim();
      btn.disabled = true;
      try {
        await api("/auth/request-otp", { method: "POST", body: { phone } });
        step = purpose + "-otp";
        render();
        toast("OTP sent — demo code is 123456", "ok");
      } catch (e) { toast(e.message, "err"); btn.disabled = false; }
    });
    input.addEventListener("keydown", (e) => e.key === "Enter" && btn.click());

    return [
      el("h1", {}, purpose === "signup" ? "Create your account" : "Reset your password"),
      el("p", { class: "lede" }, purpose === "signup"
        ? "Verify your number with a one-time code, then choose a password."
        : "We'll send a one-time code to prove it's your number."),
      el("label", { class: "lbl" }, "Mobile number"),
      el("div", { class: "copy-row" },
        el("span", { class: "chip", style: "padding:11px 14px" }, "+91"), input),
      btn,
      backLink(),
    ];
  }

  // ---- OTP + password step, shared by signup and forgot-password ------------
  function otpStep(purpose) {
    const inputs = otpInputs();
    const pass = el("input", {
      class: "input", type: "password",
      placeholder: purpose === "signup" ? "Choose a password (min 8 characters)" : "New password (min 8 characters)",
      "aria-label": purpose === "signup" ? "Create password" : "New password",
      autocomplete: "new-password",
    });
    const btn = el("button", { class: "btn", style: "width:100%;margin-top:18px" },
      purpose === "signup" ? "Create account" : "Set new password");

    btn.addEventListener("click", async () => {
      const otp = inputs.map((i) => i.value).join("");
      if (otp.length < 6) { toast("Enter the 6-digit code", "err"); return; }
      if (pass.value.length < 8) { toast("Password must be at least 8 characters", "err"); return; }
      btn.disabled = true;
      try {
        if (purpose === "signup") {
          const res = await api("/auth/register", { method: "POST", body: { phone, otp, password: pass.value } });
          setToken(res.token);
          location.hash = res.user.forwardingVerified ? "#/dashboard" : "#/setup";
          routeAfterLogin();
        } else {
          await api("/auth/reset-password", { method: "POST", body: { phone, otp, password: pass.value } });
          toast("Password updated — log in with your new password", "ok");
          step = "login";
          render();
        }
      } catch (e) { toast(e.message, "err"); btn.disabled = false; }
    });
    pass.addEventListener("keydown", (e) => e.key === "Enter" && btn.click());

    const resend = resendControls();
    return [
      el("h1", {}, purpose === "signup" ? "Verify & choose a password" : "Choose a new password"),
      el("p", { class: "lede" }, `We sent a 6-digit code to +91 ${phone}.`),
      el("div", { class: "otp-row" }, inputs),
      el("label", { class: "lbl", style: "margin-top:14px" },
        purpose === "signup" ? "Create password" : "New password"),
      pass,
      el("p", { style: "text-align:center;font-size:12.5px;color:var(--accent-2);font-weight:700" },
        "Demo mode OTP: 123456"),
      btn,
      el("p", { class: "resend" }, "Didn't get it? ", resend.resendBtn, resend.timerLabel),
      backLink(),
    ];
  }

  function otpInputs() {
    const inputs = Array.from({ length: 6 }, () =>
      el("input", { class: "input", maxlength: "1", inputmode: "numeric", "aria-label": "OTP digit" }));
    inputs.forEach((inp, i) => {
      inp.addEventListener("input", () => { if (inp.value && i < 5) inputs[i + 1].focus(); });
      inp.addEventListener("keydown", (e) => { if (e.key === "Backspace" && !inp.value && i > 0) inputs[i - 1].focus(); });
      inp.addEventListener("paste", (e) => {
        const text = (e.clipboardData.getData("text") || "").replace(/\D/g, "").slice(0, 6);
        if (!text) return;
        e.preventDefault();
        text.split("").forEach((c, j) => { if (inputs[j]) inputs[j].value = c; });
        inputs[Math.min(text.length, 5)].focus();
      });
    });
    setTimeout(() => inputs[0].focus(), 40);
    return inputs;
  }

  function resendControls() {
    let seconds = 30;
    const resendBtn = el("button", { type: "button", disabled: true }, "Resend OTP");
    const timerLabel = el("span", {});
    resendBtn.addEventListener("click", async () => {
      try {
        await api("/auth/request-otp", { method: "POST", body: { phone } });
        toast("OTP resent — demo code is 123456", "ok");
        seconds = 30; tick();
      } catch (e) { toast(e.message, "err"); }
    });
    function tick() {
      clearTimeout(resendTimer);
      if (seconds <= 0) { resendBtn.disabled = false; timerLabel.textContent = ""; return; }
      resendBtn.disabled = true;
      timerLabel.textContent = ` (${seconds}s)`;
      seconds--;
      resendTimer = setTimeout(tick, 1000);
    }
    tick();
    return { resendBtn, timerLabel };
  }

  function backLink() {
    return el("p", { class: "resend" },
      el("button", { type: "button", onclick: () => { step = "login"; render(); } }, "← Back to log in"));
  }

  render();
  return el("div", { class: "auth-wrap" },
    el("div", { class: "hero-blob", "aria-hidden": "true" }),
    el("span", { class: "demo-ribbon ribbon" }, "✨ Demo mode — log in with 9999999999 / demo1234"),
    card);
}

async function routeAfterLogin() {
  window.dispatchEvent(new HashChangeEvent("hashchange"));
}
