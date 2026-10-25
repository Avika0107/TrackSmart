// Per-site rules for the extractor. Injected into the active tab right before extract.js,
// only when the user clicks "Send". Pure data + regexes: no DOM access, no network.
// When a retailer renames a label on its order page, this is the only file to update.

globalThis.EKRAAH_RETAILERS = {

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
      orderPages: [/\/account\/order_details/, /\/account\/orders/],
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

  /** The site entry for a hostname, or null if this isn't a supported retailer. */
  siteFor(hostname) {
    const host = hostname.toLowerCase();
    return this.sites.find((s) =>
      s.hosts.some((h) => host === h || host.endsWith("." + h))) || null;
  },
};

// - globalThis.EKRAAH_RETAILERS (not const): if the user clicks Send twice on the same tab,
//   the script is injected again into the same isolated world; a top-level const would throw
//   "Identifier has already been declared". Reassigning a global property is safe.
// - Content scripts run in an "isolated world": the page's own JS can't see or tamper with this.
// - orderPages: extract.js uses these to say "open an order's details page first" instead of
//   guessing on a listing or product page. URL paths are best-effort; verify on real accounts.
// - orderId: lets the backend merge with an EMAIL_PENDING card even when no AWB is shown yet.
// - trackingLabels: label-anchored, so a random 10-digit number (phone, pincode-ish) elsewhere
//   on the page isn't mistaken for an AWB. Bare-digit matches are only accepted after a label.
// - awb: mirrors com.ekraah.util.CourierDetector exactly (incl. Ekart FMPC/FMPP). If you add a
//   format here, add it there too, or the server will reject what the extension sends.
// - siteFor(): "mail.flipkart.com" or "www.flipkart.com" both resolve via the endsWith check,
//   same subdomain rule as GmailMessageProcessor.allowed().
