// Runs in the active tab right after retailers.js, only when the user clicks "Send".
// Reads the page's visible text, pulls out the tracking facts, and returns them to
// background.js. The page text itself never leaves this function.

(() => {
  const rules = globalThis.EKRAAH_RETAILERS;
  if (!rules) return { error: "Extension rules failed to load. Reload the extension and try again." };

  // ---------- 1. Is this a supported retailer's order page? ----------

  const site = rules.siteFor(location.hostname);
  if (!site) {
    return { error: "This isn't a supported store. Open an order on Amazon, Flipkart, Myntra, Nykaa or AJIO." };
  }
  if (!site.orderPages.some((re) => re.test(location.pathname))) {
    return { error: "Open the order's details page (the one showing tracking), then click Send again." };
  }

  // ---------- 2. Visible text only ----------

  const MAX_CHARS = 200_000;                       // order pages are small; guards against huge pages
  const text = (document.body?.innerText || "")
    .replace(/ /g, " ")                       // &nbsp; -> normal space so \s matches
    .slice(0, MAX_CHARS);

  // ---------- 3. Tracking number: label-anchored, then format-validated ----------

  const isValidAwb = (candidate) => rules.awb.some((re) => re.test(candidate));

  function findTracking() {
    for (const label of rules.trackingLabels) {
      const global = new RegExp(label.source, label.flags.includes("g") ? label.flags : label.flags + "g");
      for (const m of text.matchAll(global)) {
        const candidate = m[1].toUpperCase().replace(/[^A-Z0-9]/g, "");   // drop stray hyphens
        if (isValidAwb(candidate)) return { trackingNumber: candidate, at: m.index };
      }
    }
    return null;
  }

  const tracking = findTracking();

  // ---------- 4. Courier: look near the tracking label first ----------

  function findCourier(around) {
    const WINDOW = 200;                            // chars on each side of the tracking label
    const nearby = around == null ? "" : text.slice(Math.max(0, around - WINDOW), around + WINDOW);
    const hit = rules.couriers.find((c) => c.match.test(nearby));
    return hit ? hit.name : null;                  // null -> backend's CourierDetector decides
  }

  // ---------- 5. Retailer order id (helps merge with an EMAIL_PENDING card) ----------

  const orderIdMatch = site.orderId ? text.match(site.orderId) : null;
  const retailerOrderId = orderIdMatch ? orderIdMatch[1] : null;

  if (!tracking) {
    return {
      error: "No tracking number on this page yet — it usually appears once the order ships.",
      platform: site.platform,
      retailerOrderId,
    };
  }

  return {
    platform: site.platform,
    trackingNumber: tracking.trackingNumber,
    courierHint: findCourier(tracking.at),
    retailerOrderId,
  };
})();

// - The whole file is one IIFE expression: chrome.scripting.executeScript returns the value of
//   the last statement, so this object becomes results.at(-1).result in background.js.
// - Every exit is either { error } or the four facts — the popup shows `error` verbatim, so the
//   messages are written for the shopper, not the developer.
// - innerText (not textContent): only text the user can actually see, so hidden templates or
//   script data on the page aren't scanned.
// - Label regexes from retailers.js have no "g" flag; matchAll needs one, so a global copy is
//   built. Trying every match (not just the first) skips e.g. "Tracking ID: Not available yet".
// - Hyphens/spaces are stripped before validation because some pages print "1234-5678-90".
// - Courier is searched only within ±200 chars of the tracking label: a page footer listing
//   "We ship with Delhivery, Blue Dart…" would otherwise give a wrong hint.
// - Multi-package orders (common on Amazon) return the first tracking number on the page;
//   handling several shipments is a possible later improvement.
// - No tracking found: we return an error rather than sending only the order id — with no AWB
//   there is nothing to track, and the email pipeline already creates ORDERED placeholders.
