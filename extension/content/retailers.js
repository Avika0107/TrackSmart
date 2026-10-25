// Per-site rules for the extractor. Injected into the active tab right before extract.js,
// only when the user clicks "Send". Pure data + regexes: no DOM access, no network.
// When a retailer renames a label on its order page, this is the only file to update.

globalThis.EKRAAH_RETAILERS = (() => {

  // A date as stores print it: "Wed, 15 Oct", "15th Oct 2026", "Oct 24", "October 24, 2026".
  // Used inside the status/date rules below; extract.js turns the captured text into a date.
  const MONTH = String.raw`(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*`;
  const DATE = String.raw`(?:(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*,?\s+)?`
    + String.raw`(?:\d{1,2}(?:st|nd|rd|th)?\s+` + MONTH + String.raw`|` + MONTH + String.raw`\s+\d{1,2}(?:st|nd|rd|th)?)`
    + String.raw`(?:,?\s+\d{4})?`;
  const afterLabel = (label) => new RegExp(label + String.raw`\s*[,:\-]?\s*(?:on\s+)?(` + DATE + ")", "i");

  return {

    // Platform names must match the backend enum com.ekraah.model.Platform.
    sites: [
      {
        platform: "AMAZON",
        hosts: ["amazon.in", "amazon.com"],
        // Order details / "Track package" pages.
        orderPages: [/\/gp\/your-account\/order-details/, /\/progress-tracker\//, /\/your-orders\//],
        orderId: /\b(\d{3}-\d{7}-\d{7})\b/,
      },
      {
        platform: "FLIPKART",
        hosts: ["flipkart.com"],
        // Real address: /order_details?order_id=OD…&item_id=… (checked on a live account).
        // Not /account/orders: that list holds many orders and would yield the first one's id.
        orderPages: [/\/order_details/],
        orderId: /\b(OD\d{15,20})\b/,
      },
      {
        platform: "MYNTRA",
        hosts: ["myntra.com"],
        orderPages: [/\/my\/order/, /\/my\/item\/details/],
        orderId: /order\s*(?:id|no\.?|number)\s*[:#]?\s*([0-9-]{8,25})/i,
      },
      {
        platform: "NYKAA",
        hosts: ["nykaa.com", "nykaafashion.com"],
        orderPages: [/\/sales\/order/, /\/my-orders/, /\/order-details/],
        orderId: /order\s*(?:id|no\.?|number)\s*[:#]?\s*([A-Z0-9-]{6,25})/i,
      },
      {
        platform: "AJIO",
        hosts: ["ajio.com"],
        orderPages: [/\/my-account\/orders/, /\/order\//],
        orderId: /order\s*(?:id|no\.?|number)\s*[:#]?\s*(FN\d{6,15}|\d{8,15})/i,
      },
    ],

    // Labels that sit right before a tracking number on order pages, across retailers.
    // Group 1 is the candidate number; extract.js validates it with the awb patterns below.
    trackingLabels: [
      /tracking\s*(?:id|no\.?|number)\s*[:#-]?\s*([A-Z0-9-]{8,25})/i,
      /\bAWB\s*(?:no\.?|number)?\s*[:#-]?\s*([A-Z0-9]{8,25})/i,
      /(?:shipment|consignment|docket)\s*(?:id|no\.?|number)\s*[:#-]?\s*([A-Z0-9]{8,25})/i,
    ],

    // Courier names as retailers print them -> the name CourierDetector uses on the backend.
    couriers: [
      { match: /\bekart\b/i, name: "Ekart" },
      { match: /\bdelhivery\b/i, name: "Delhivery" },
      { match: /\bblue\s*dart\b/i, name: "Blue Dart" },
      { match: /\bxpress\s*bees\b/i, name: "Xpressbees" },
      { match: /\bshadowfax\b/i, name: "Shadowfax" },
      { match: /\bindia\s*post\b|\bspeed\s*post\b/i, name: "India Post" },
      { match: /\bamazon\s*(?:shipping|logistics|transportation)\b|\bATS\b/i, name: "Amazon" },
      { match: /\becom\s*express\b/i, name: "Ecom Express" },
      { match: /\bdtdc\b/i, name: "DTDC" },
    ],

    // Same formats as CourierDetector / EmailParser on the backend, so the extension never
    // sends something the server would reject. Distinctive prefixes first, bare digits last.
    awb: [
      /^TBA\d{9,15}$/,          // Amazon internal (backend keeps it EMAIL_ONLY)
      /^FMP[CP]?\d{6,12}$/,     // Ekart (Flipkart)
      /^SF\d{9,15}$/,           // Shadowfax
      /^[A-Z]{2}\d{9}IN$/,      // India Post
      /^\d{10,18}$/,            // Blue Dart / Delhivery / Xpressbees (digit-length heuristics)
    ],

    // ---- Order-id-only pages (Flipkart shows no tracking number) ----

    // Current status, read from steps that carry a date (a completed step) — the order page also
    // lists future steps like "Delivered" without a date. First match wins: terminal states first.
    // Group 1, when present, is the step's date.
    pageStatus: [
      { status: "CANCELLED", match: /\bcancel+ed\s*(?:on\b|,)|order\s+(?:was|has\s+been|is)\s+cancel+ed/i },
      // "Return, Jun 02" / "Refund, Jun 05" (checked on a live Flipkart return). Needs a date right
      // after the word, so "Return policy ended on Oct 13" on a delivered order doesn't match.
      { status: "RETURNED", match: afterLabel(String.raw`\b(?:return(?:ed)?|refund(?:ed)?)`) },
      { status: "DELIVERED", match: afterLabel(String.raw`\bdelivered`) },
      { status: "OUT_FOR_DELIVERY", match: /\bout\s+for\s+delivery\s*(?:today|[,:-]\s*(?:today|\w))/i },
      { status: "SHIPPED", match: afterLabel(String.raw`\bshipped`) },
      { status: "ORDERED", match: afterLabel(String.raw`\border\s+(?:confirmed|placed)`) },
    ],

    // "Delivery by Fri, 24 Oct", "Delivery expected by Oct 24", "Arriving by 24 Oct".
    expectedDelivery: afterLabel(String.raw`(?:delivery\s+(?:expected\s+)?by|expected\s+(?:delivery\s+)?by|arriving\s+(?:by|on))`),

    /** The site entry for a hostname, or null if this isn't a supported retailer. */
    siteFor(hostname) {
      const host = hostname.toLowerCase();
      return this.sites.find((s) =>
        s.hosts.some((h) => host === h || host.endsWith("." + h))) || null;
    },
  };
})();

// - globalThis.EKRAAH_RETAILERS (not a top-level const): clicking Send twice on the same tab injects
//   this file again into the same isolated world; a top-level const would throw "Identifier has
//   already been declared". The IIFE keeps DATE / afterLabel private, and reassigning is safe.
// - Content scripts run in an "isolated world": the page's own JS can't see or tamper with this.
// - orderPages: extract.js uses these to say "open an order's details page first" instead of
//   guessing on a listing or product page. URL paths are best-effort; verify on real accounts.
// - orderId: the key for order-id-only imports (Flipkart) and for later merging a tracking number
//   into that card.
// - trackingLabels: label-anchored, so a random 10-digit number (phone, pincode-ish) elsewhere
//   on the page isn't mistaken for an AWB. Bare-digit matches are only accepted after a label.
// - awb: mirrors com.ekraah.util.CourierDetector exactly (incl. Ekart FMPC/FMPP). If you add a
//   format here, add it there too, or the server will reject what the extension sends.
// - pageStatus: "Delivered" alone isn't enough (it's also an upcoming step) — the rule requires
//   a date after it, e.g. "Delivered on Oct 12" / "Delivered, Sun 12 Oct". Best-effort phrasing:
//   verify against a real Flipkart order page and adjust here.
// - siteFor(): "www.flipkart.com" resolves via the endsWith check, same subdomain rule as
//   GmailMessageProcessor.allowed().
