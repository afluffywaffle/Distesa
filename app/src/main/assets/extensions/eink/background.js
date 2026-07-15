/*
 * Achroma E-Ink — background: network media-block + native relay.
 *
 * TWO JOBS:
 *
 * 1) NETWORK BLOCK (the point of task #11/#12). Images AND video/media are gated
 *    at the NETWORK layer via webRequest.onBeforeRequest([blocking]) so bytes are
 *    NEVER fetched unless allowed — no "load then hide" race, nothing downloads
 *    on e-ink until the user taps (or the primary-content heuristic allows). This
 *    only works from a background page (webRequest is background-only), and only
 *    for a privileged extension with host + webRequestBlocking permissions — the
 *    same capability uBlock Origin uses.
 *
 * 2) NATIVE RELAY (unchanged from before). GeckoView native messaging is
 *    background-only, so this page bridges the content scripts to the app:
 *      content.js flip  --sendMessage--> here --sendNativeMessage("browser")--> app.onMessage
 *      images.js  port  --connect------> here <--connectNative("browser")-----> app.onConnect
 *
 * MV2, vanilla JS. Robust: if webRequest blocking is unavailable we log and fall
 * back to the content script's DOM-strip behaviour, so the app still works.
 */

var NATIVE_APP = "browser";
var DEFAULT_POLICY = "placeholder-tap";
// image/imageset = <img> + srcset/<picture>; media = <video>/<audio> byte
// streams and HLS/DASH segment fetches typed as media.
var BLOCK_TYPES = ["image", "imageset", "media"];

// Policy per page-hostname (cache of storage.local, kept coherent live).
var policyByHost = Object.create(null);

// Per-tab page-load state: the page URL (to detect navigation) and the set of
// media URLs the user/heuristic has allowed for THIS load. Allowlist is cleared
// on navigation; the policy itself persists in storage.
var tabState = Object.create(null); // tabId -> { pageUrl, allow: Set }

var netBlockActive = false;

function log(msg) {
    try { console.log("[eink-bg] " + msg); } catch (e) {}
}

// DIAGNOSTIC: background console.log does NOT reliably reach adb logcat, so send
// diagnostics to the app via native messaging — MainActivity logs them under tag
// AchromaMain as "[eink-diag] ...". Used to prove whether onBeforeRequest fires
// and whether {cancel:true} is honored.
//
// The app's MessageDelegate is registered slightly AFTER the background starts,
// so early diags are queued and flushed once messaging is ready.
var diagReady = false;
var diagQueue = [];
function sendDiag(msg) {
    try {
        var p = browser.runtime.sendNativeMessage(NATIVE_APP, { type: "diag", msg: msg });
        if (p && p.catch) p.catch(function () {});
    } catch (e) {}
}
function diag(msg) {
    if (diagReady) { sendDiag(msg); return; }
    diagQueue.push(msg);
}
setTimeout(function () {
    diagReady = true;
    for (var i = 0; i < diagQueue.length; i++) sendDiag(diagQueue[i]);
    diagQueue = [];
}, 1500);

// Request/cancel counters, flushed to the app periodically.
var seenCount = 0;
var cancelCount = 0;
var lastFlush = 0;
function maybeFlushCounters() {
    var now = Date.now();
    if (seenCount % 25 === 0 || now - lastFlush > 4000) {
        lastFlush = now;
        diag("media requests seen=" + seenCount + " cancelled=" + cancelCount +
            " (netBlock=" + netBlockActive + ")");
    }
}

function hostOf(url) {
    try { return new URL(url).hostname || "_local"; } catch (e) { return "_local"; }
}

function policyFor(host) {
    return policyByHost[host] || DEFAULT_POLICY;
}

function stateFor(tabId, pageUrl) {
    var key = String(tabId);
    var st = tabState[key];
    if (!st || (pageUrl && st.pageUrl !== pageUrl)) {
        st = { pageUrl: pageUrl || (st && st.pageUrl) || "", allow: new Set() };
        tabState[key] = st;
    }
    return st;
}

// --- Network block -------------------------------------------------------

function onBeforeMedia(details) {
    try {
        seenCount++;
        // The page that owns the request (documentUrl for a subresource); fall
        // back to originUrl, then the request URL itself.
        var pageUrl = details.documentUrl || details.originUrl || details.url;
        var host = hostOf(pageUrl);
        var policy = policyFor(host);

        if (policy === "load-all") { maybeFlushCounters(); return {}; }

        var st = stateFor(details.tabId, pageUrl);
        if (st.allow.has(details.url)) { maybeFlushCounters(); return {}; }

        // hide-all / placeholder-tap / primary-content-only: block by default.
        cancelCount++;
        maybeFlushCounters();
        return { cancel: true };
    } catch (e) {
        log("onBeforeMedia failed (allowing): " + e);
        return {}; // never break the page: on error, don't block
    }
}

function installNetworkBlock() {
    // Capability probe reported to the app (background console.log is invisible in
    // logcat). Tells us definitively what APIs the bundled extension actually got.
    diag("caps: webRequest=" + (typeof browser.webRequest) +
        " onBeforeRequest=" + (browser.webRequest ? typeof browser.webRequest.onBeforeRequest : "n/a") +
        " declarativeNetRequest=" + (typeof browser.declarativeNetRequest));
    try {
        if (!browser.webRequest || !browser.webRequest.onBeforeRequest) {
            diag("webRequest UNAVAILABLE — DOM-strip fallback only");
            log("webRequest unavailable — falling back to DOM-strip only");
            return;
        }
        browser.webRequest.onBeforeRequest.addListener(
            onBeforeMedia,
            { urls: ["<all_urls>"], types: BLOCK_TYPES },
            ["blocking"]
        );
        netBlockActive = true;
        diag("onBeforeRequest registered with [blocking] for " + BLOCK_TYPES.join(","));
        log("network media-block active for types: " + BLOCK_TYPES.join(","));
    } catch (e) {
        netBlockActive = false;
        diag("addListener THREW: " + e + " — is [blocking] rejected? DOM-strip fallback");
        log("installNetworkBlock failed — DOM-strip fallback: " + e);
    }
}

// --- Policy cache --------------------------------------------------------

function loadPolicies() {
    try {
        browser.storage.local.get(null).then(function (all) {
            if (all) policyByHost = all;
            log("policy cache loaded (" + Object.keys(policyByHost).length + " hosts)");
        }, function (e) { log("policy load failed: " + e); });
    } catch (e) {
        log("policy load threw: " + e);
    }
}

try {
    browser.storage.onChanged.addListener(function (changes, area) {
        if (area !== "local") return;
        for (var host in changes) policyByHost[host] = changes[host].newValue;
    });
} catch (e) { log("storage.onChanged unavailable: " + e); }

// --- Native relay --------------------------------------------------------

var nativePort = null;
var imgPorts = new Set();

function openNativePort() {
    try {
        nativePort = browser.runtime.connectNative(NATIVE_APP);
        nativePort.onMessage.addListener(function (msg) {
            // App -> content (e.g. cyclePolicy). Broadcast to all media ports.
            imgPorts.forEach(function (p) {
                try { p.postMessage(msg); } catch (e) { log("fanout failed: " + e); }
            });
        });
        nativePort.onDisconnect.addListener(function () { nativePort = null; });
        log("native port opened");
    } catch (e) {
        log("connectNative failed: " + e);
        nativePort = null;
    }
}

// content.js flip -> native (fire-and-forget). Reaches app.onMessage.
browser.runtime.onMessage.addListener(function (msg) {
    try {
        if (msg && msg.type === "flip") {
            var p = browser.runtime.sendNativeMessage(NATIVE_APP, { type: "flip" });
            if (p && p.catch) p.catch(function () {});
        }
    } catch (e) {
        log("flip relay failed: " + e);
    }
});

// images.js port <-> native + allowlist control.
browser.runtime.onConnect.addListener(function (port) {
    imgPorts.add(port);
    var tabId = (port.sender && port.sender.tab) ? port.sender.tab.id : -1;
    var pageUrl = (port.sender && port.sender.url) ? port.sender.url : "";
    log("media content port connected (tab " + tabId + ")");

    port.onMessage.addListener(function (msg) {
        try {
            if (msg && msg.type === "allow" && msg.urls) {
                // Add URLs to this tab's allowlist, THEN tell the content script
                // it's safe to (re)trigger the fetch. Replying after updating the
                // set guarantees the block won't cancel the re-request.
                var st = stateFor(tabId, pageUrl);
                for (var i = 0; i < msg.urls.length; i++) st.allow.add(msg.urls[i]);
                port.postMessage({ type: "allowed", urls: msg.urls });
                return;
            }
            if (msg && msg.type === "capabilities") {
                port.postMessage({ type: "capabilities", netBlock: netBlockActive });
                return;
            }
            // Everything else (policy reports, etc.) relays up to the app.
            if (nativePort) nativePort.postMessage(msg);
        } catch (e) {
            log("port message failed: " + e);
        }
    });
    port.onDisconnect.addListener(function () { imgPorts.delete(port); });
});

// --- Startup -------------------------------------------------------------

loadPolicies();
installNetworkBlock();
openNativePort();
