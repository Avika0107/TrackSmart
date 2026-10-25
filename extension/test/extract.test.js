// Runs content/retailers.js + content/extract.js against order-page text fixtures.
// From the repo root:   node extension/test/extract.test.js
//              or (macOS, no Node needed):   osascript -l JavaScript extension/test/extract.test.js
// The scripts are evaluated with a fake `location` / `document` / `globalThis`, exactly as
// chrome.scripting.executeScript would run them in the tab. Fixture wording is copied from real
// order pages (Flipkart: checked on a live account); order ids are made up.

const read = typeof require === "function"
  ? (p) => require("fs").readFileSync(require("path").join(__dirname, "..", p), "utf8")
  : (p) => $.NSString.stringWithContentsOfFileEncodingError(
      $.NSFileManager.defaultManager.currentDirectoryPath.js + "/extension/" + p, $.NSUTF8StringEncoding, null).js;

const RETAILERS = read("content/retailers.js");
const EXTRACT = read("content/extract.js");

/** Same as clicking Send on a page at `url` whose visible text is `text`. */
function extract(url, text) {
  const [, hostname, pathname] = url.match(/^https?:\/\/([^/?#]+)([^?#]*)/);   // no URL class in osascript
  const fakeGlobal = {};
  const location = { hostname, pathname: pathname || "/" };
  const document = { body: { innerText: text } };
  // Both files run in order with one shared fake globalThis — as the two files of one
  // executeScript call share the tab's isolated world. extract.js is a single IIFE expression, so
  // after its leading comment lines it can be returned directly: that is the value executeScript
  // hands back. (eval is avoided: under osascript it runs globally and can't see the fakes.)
  const params = ["globalThis", "location", "document"];
  new Function(...params, RETAILERS)(fakeGlobal, location, document);
  // trimStart: "return" followed by a newline would be read as "return;" (automatic semicolon).
  const body = "return " + EXTRACT.replace(/^(\s*\/\/[^\n]*\n)+/, "").trimStart();
  return new Function(...params, body)(fakeGlobal, location, document);
}

/** Expected yyyy-mm-dd for a past "Mon DD" with no year, mirroring toIsoDate("past"). */
function pastIso(month, day) {
  const now = new Date();
  let d = new Date(now.getFullYear(), month - 1, day);
  if (d > now) d = new Date(now.getFullYear() - 1, month - 1, day);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

const cases = [
  {
    name: "Flipkart delivered (order id only)",
    url: "https://www.flipkart.com/order_details?order_id=OD100000000000000001&item_id=1",
    text: "Artistry craft charm\nOrder Confirmed, Oct 02\nDelivered, Oct 06\nSee All Updates\nReturn policy ended on Oct 13\nOrder #OD100000000000000001",
    expect: { platform: "FLIPKART", trackingNumber: null, retailerOrderId: "OD100000000000000001", status: "DELIVERED", deliveredOn: pastIso(10, 6) },
  },
  {
    name: "Flipkart returned",
    url: "https://www.flipkart.com/order_details?order_id=OD100000000000000002",
    text: "Total refund - ₹731\nKOTTY Wide Leg Men Green Jeans\nReturn, Jun 02\nRefund, Jun 05\nCancellation & Returns\nOrder #OD100000000000000002",
    expect: { status: "RETURNED", retailerOrderId: "OD100000000000000002", trackingNumber: null },
  },
  {
    name: "Flipkart shipped: upcoming 'Delivered' step without a date is ignored",
    url: "https://www.flipkart.com/order_details?order_id=OD100000000000000003",
    text: "Order Confirmed, Oct 20\nShipped, Wed 22 Oct\nOut For Delivery\nDelivered\nDelivery by Fri, 31st Oct 2030\nOrder #OD100000000000000003",
    expect: { status: "SHIPPED", expectedDelivery: "2030-10-31", deliveredOn: null },
  },
  {
    name: "Flipkart orders list is not an order page",
    url: "https://www.flipkart.com/account/orders",
    text: "Delivered on Oct 06\nDelivered on Sep 20",
    expectError: /details page/,
  },
  {
    name: "Amazon third-party courier with tracking id",
    url: "https://www.amazon.in/progress-tracker/package/ref=ppx_yo",
    text: "Arriving Friday\nShipped with Delhivery\nTracking ID: 1234567890123\nOrder # 402-1111111-2222222",
    expect: { platform: "AMAZON", trackingNumber: "1234567890123", courierHint: "Delhivery", retailerOrderId: "402-1111111-2222222" },
  },
  {
    name: "Ekart FMPP number with hyphens is normalised",
    url: "https://www.myntra.com/my/order/details",
    text: "Courier: Ekart Logistics\nAWB No: FMPP-1234-567890\nOrder ID: 1234567890",
    expect: { trackingNumber: "FMPP1234567890", courierHint: "Ekart" },
  },
  {
    name: "'Tracking ID: Not available yet' falls back to the order id",
    url: "https://www.flipkart.com/order_details?order_id=OD100000000000000004",
    text: "Tracking ID: Not available yet\nOrder Confirmed, Oct 24\nOrder #OD100000000000000004",
    expect: { trackingNumber: null, status: "ORDERED", retailerOrderId: "OD100000000000000004" },
  },
  {
    name: "Cancelled order",
    url: "https://www.flipkart.com/order_details?order_id=OD100000000000000005",
    text: "Order Confirmed, Oct 10\nCancelled, Oct 11\nOrder #OD100000000000000005",
    expect: { status: "CANCELLED" },
  },
  {
    name: "Unsupported store",
    url: "https://www.example.com/orders/1",
    text: "Tracking ID: 1234567890",
    expectError: /supported store/,
  },
  {
    name: "Order page with nothing usable",
    url: "https://www.flipkart.com/order_details?x=1",
    text: "Something went wrong. Please try again.",
    expectError: /tracking number or order id/,
  },
];

let failed = 0;
const out = [];
for (const c of cases) {
  let got, problem = null;
  try {
    got = extract(c.url, c.text);
    if (c.expectError) {
      if (!got || !got.error || !c.expectError.test(got.error)) problem = "expected error " + c.expectError + ", got " + JSON.stringify(got);
    } else if (got.error) {
      problem = "unexpected error: " + got.error;
    } else {
      for (const [k, v] of Object.entries(c.expect)) {
        if (got[k] !== v) { problem = `${k}: expected ${JSON.stringify(v)}, got ${JSON.stringify(got[k])}`; break; }
      }
    }
  } catch (e) {
    problem = "threw " + e;
  }
  if (problem) failed++;
  out.push((problem ? "FAIL " : "PASS ") + c.name + (problem ? "  — " + problem : ""));
}
out.push(`\n${cases.length - failed}/${cases.length} extension extraction tests passed`);

if (typeof require === "function") {
  console.log(out.join("\n"));
  process.exitCode = failed ? 1 : 0;
} else {
  // osascript prints the script's last value; a non-zero exit needs an error.
  if (failed) throw new Error(out.join("\n"));
  out.join("\n");
}

// - Fake globals: extract.js reads location.hostname/pathname and document.body.innerText, and
//   retailers.js writes globalThis.EKRAAH_RETAILERS; passing them as Function parameters
//   reproduces the content-script environment without a browser.
// - extract.js is one IIFE expression: "return <IIFE>" inside the wrapper yields the same value
//   executeScript returns for the file's last statement.
// - pastIso() mirrors toIsoDate's year rule so "Oct 06" without a year stays correct every year.
// - Fixtures that matter most are the real-page ones: Flipkart's date-anchored steps, the
//   returned order, and "Return policy ended on …" which must not read as a return.
