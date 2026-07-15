/*
 * Achroma E-Ink — image-policy content script.
 *
 * On an e-ink panel, images are expensive (ghosting, slow full refreshes) and
 * often junk (ads, avatars, nav thumbs). This script gates image loading with a
 * per-domain policy, replacing gated <img>s with lightweight tappable text
 * placeholders (no external assets) sized to the image's dimensions to avoid
 * layout shift. Runs at document_start so we can strip src/srcset BEFORE the
 * image begins loading; a MutationObserver catches images added later.
 *
 * FOUR POLICIES (cycled by the native "IMG:" overlay button):
 *   hide-all             — every <img> becomes a placeholder; nothing loads.
 *                          Tapping a placeholder DISMISSES it (collapses to
 *                          nothing) — a pure declutter/text-reading mode.
 *   placeholder-tap      — (DEFAULT) every <img> becomes a placeholder; tapping
 *                          it LOADS that one image (restore its real src).
 *   primary-content-only — auto-load the heuristic "primary" image(s) (large,
 *                          and/or inside <main>/<article>); everything else
 *                          (ads, icons, avatars, thumbs) becomes a tap-to-load
 *                          placeholder.
 *   load-all             — no-op; images load normally.
 *
 * Per-domain policy persists in browser.storage.local keyed by hostname.
 *
 * Robustness: every per-image operation is wrapped so a failure logs and leaves
 * that image as-is — we never break the page.
 *
 * Framework-free vanilla JS. MV2.
 */
(function () {
    "use strict";

    var HOST = location.hostname || "_local";
    var DEFAULT_POLICY = "placeholder-tap";
    var CYCLE = ["hide-all", "placeholder-tap", "primary-content-only", "load-all"];

    // "Primary" heuristic thresholds.
    var MIN_PRIMARY_AREA = 200 * 200; // px^2 — below this an image is never primary
    var LARGEST_FRACTION = 0.6;       // >= this share of the page's biggest image => primary

    var policy = DEFAULT_POLICY;
    var observer = null;
    var port = null;

    function log(msg) {
        try { console.log("[eink-images] " + msg); } catch (e) {}
    }

    // --- Dimensions & primary heuristic -------------------------------------

    function imgArea(img) {
        var w = img.getBoundingClientRect().width ||
            parseInt(img.getAttribute("width"), 10) || img.naturalWidth || 0;
        var h = img.getBoundingClientRect().height ||
            parseInt(img.getAttribute("height"), 10) || img.naturalHeight || 0;
        return w * h;
    }

    function pageMaxArea(imgs) {
        var max = 0;
        for (var i = 0; i < imgs.length; i++) {
            var a = imgArea(imgs[i]);
            if (a > max) max = a;
        }
        return max;
    }

    function isPrimary(img, maxArea) {
        var area = imgArea(img);
        if (area < MIN_PRIMARY_AREA) return false;
        if (img.closest && img.closest("main, article, [role='main']")) return true;
        return maxArea > 0 && area >= LARGEST_FRACTION * maxArea;
    }

    // --- Placeholder <-> image ----------------------------------------------

    function stashSources(img) {
        // Preserve everything needed to restore the image later, covering plain
        // src, responsive srcset, and common lazy-load (data-src/data-srcset).
        if (img.getAttribute("src")) img.dataset.einkSrc = img.getAttribute("src");
        if (img.getAttribute("srcset")) img.dataset.einkSrcset = img.getAttribute("srcset");
        if (img.getAttribute("data-src")) img.dataset.einkDataSrc = img.getAttribute("data-src");
        if (img.getAttribute("data-srcset")) img.dataset.einkDataSrcset = img.getAttribute("data-srcset");
        // Strip so the browser does not fetch/paint the image.
        img.removeAttribute("src");
        img.removeAttribute("srcset");
        img.removeAttribute("data-src");
        img.removeAttribute("data-srcset");
    }

    function restoreImage(img) {
        try {
            if (img.dataset.einkSrcset) img.setAttribute("srcset", img.dataset.einkSrcset);
            else if (img.dataset.einkDataSrcset) img.setAttribute("srcset", img.dataset.einkDataSrcset);
            if (img.dataset.einkSrc) img.setAttribute("src", img.dataset.einkSrc);
            else if (img.dataset.einkDataSrc) img.setAttribute("src", img.dataset.einkDataSrc);
            img.style.display = "";
            var ph = img.previousElementSibling;
            if (ph && ph.classList && ph.classList.contains("eink-img-ph")) ph.remove();
        } catch (e) {
            log("restore failed: " + e);
        }
    }

    function makePlaceholder(img, tapLoads) {
        var rect = img.getBoundingClientRect();
        var w = Math.round(rect.width || parseInt(img.getAttribute("width"), 10) || 0);
        var h = Math.round(rect.height || parseInt(img.getAttribute("height"), 10) || 0);

        var ph = document.createElement("span");
        ph.className = "eink-img-ph";
        // Inline styles only (no external stylesheet). Greyscale, high-contrast
        // box with a centered text label — reads fine on an EPD panel.
        ph.style.cssText =
            "display:inline-flex;align-items:center;justify-content:center;" +
            "box-sizing:border-box;border:1px solid #999;background:#f2f2f2;" +
            "color:#555;font:12px/1.3 sans-serif;text-align:center;" +
            "vertical-align:middle;overflow:hidden;cursor:pointer;" +
            (w ? "width:" + w + "px;" : "min-width:80px;") +
            (h ? "height:" + h + "px;" : "min-height:40px;");
        ph.textContent = tapLoads ? "🖼 tap to load" : "🖼 image hidden";

        ph.addEventListener("click", function (e) {
            e.preventDefault();
            e.stopPropagation();
            if (tapLoads) restoreImage(img);
            else ph.remove(); // hide-all: tapping just dismisses the box
        }, true);

        return ph;
    }

    function placeholderify(img, tapLoads) {
        if (img.dataset.einkDone) return;
        img.dataset.einkDone = "1";
        stashSources(img);
        var ph = makePlaceholder(img, tapLoads);
        img.style.display = "none";
        if (img.parentNode) img.parentNode.insertBefore(ph, img);
    }

    // --- Apply policy to a set of images ------------------------------------

    function applyToImg(img, maxArea) {
        try {
            if (img.dataset.einkDone) return;
            switch (policy) {
                case "load-all":
                    break; // leave everything alone
                case "hide-all":
                    placeholderify(img, false);
                    break;
                case "placeholder-tap":
                    placeholderify(img, true);
                    break;
                case "primary-content-only":
                    if (isPrimary(img, maxArea)) return; // auto-load primary
                    placeholderify(img, true);
                    break;
            }
        } catch (e) {
            log("applyToImg failed: " + e);
        }
    }

    function applyToAll(root) {
        if (policy === "load-all") return;
        var imgs = (root || document).querySelectorAll("img");
        if (!imgs.length) return;
        var maxArea = policy === "primary-content-only" ? pageMaxArea(imgs) : 0;
        for (var i = 0; i < imgs.length; i++) applyToImg(imgs[i], maxArea);
    }

    function startObserver() {
        if (observer || policy === "load-all") return;
        observer = new MutationObserver(function (mutations) {
            for (var m = 0; m < mutations.length; m++) {
                var added = mutations[m].addedNodes;
                for (var n = 0; n < added.length; n++) {
                    var node = added[n];
                    if (node.nodeType !== 1) continue;
                    try {
                        if (node.tagName === "IMG") {
                            applyToImg(node, 0);
                        } else if (node.querySelectorAll) {
                            var imgs = node.querySelectorAll("img");
                            for (var i = 0; i < imgs.length; i++) applyToImg(imgs[i], 0);
                        }
                    } catch (e) {
                        log("observer node failed: " + e);
                    }
                }
            }
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
    }

    function applyPolicy() {
        try {
            applyToAll(document);
            startObserver();
            // Some images already exist by the time the DOM is interactive; run
            // once more when the body is ready to catch anything missed at
            // document_start.
            if (document.readyState === "loading") {
                document.addEventListener("DOMContentLoaded", function () { applyToAll(document); });
            }
        } catch (e) {
            log("applyPolicy failed: " + e);
        }
    }

    // --- Native bridge (app <-> extension) ----------------------------------
    //
    // The native "IMG:" button cycles this domain's policy. Native cannot write
    // browser.storage.local, so the flow is: native pushes {type:"cyclePolicy"}
    // over the port; we compute the next policy, PERSIST it, then reload — the
    // fresh page load re-reads storage and re-applies. We also push our current
    // policy up so it can label the button.
    //
    // Content scripts can't reach the app directly, so runtime.connect() targets
    // our background.js, which bridges this port to the app's native Port
    // (connectNative "browser" ⇄ onConnect). background relays both directions.

    function connectNative() {
        try {
            port = browser.runtime.connect({ name: "eink-images" });
            port.onMessage.addListener(function (msg) {
                if (msg && msg.type === "cyclePolicy") cyclePolicy();
            });
            port.onDisconnect.addListener(function () { port = null; });
            reportPolicy();
        } catch (e) {
            // No native bridge (e.g. off-GeckoView) — image gating still works,
            // only the cycle button is unavailable.
            log("connect failed: " + e);
        }
    }

    function reportPolicy() {
        try {
            if (port) port.postMessage({ type: "policy", policy: policy, host: HOST });
        } catch (e) {
            log("reportPolicy failed: " + e);
        }
    }

    function cyclePolicy() {
        try {
            var idx = CYCLE.indexOf(policy);
            var next = CYCLE[(idx + 1) % CYCLE.length];
            var obj = {};
            obj[HOST] = next;
            browser.storage.local.set(obj).then(function () {
                location.reload();
            }, function (e) {
                log("persist failed: " + e);
                location.reload();
            });
        } catch (e) {
            log("cyclePolicy failed: " + e);
        }
    }

    // --- Startup ------------------------------------------------------------

    function start() {
        try {
            browser.storage.local.get(HOST).then(function (res) {
                if (res && res[HOST]) policy = res[HOST];
                log("policy for " + HOST + " = " + policy);
                applyPolicy();
                connectNative();
            }, function (e) {
                log("storage.get failed, using default: " + e);
                applyPolicy();
                connectNative();
            });
        } catch (e) {
            // browser.* unavailable — apply the default so the page still gates.
            log("start failed: " + e);
            applyPolicy();
        }
    }

    start();
})();
