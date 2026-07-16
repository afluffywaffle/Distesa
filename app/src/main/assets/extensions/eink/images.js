/*
 * Distesa E-Ink — media-policy content script (images + video + embeds).
 *
 * Companion to background.js. The BACKGROUND blocks image/video bytes at the
 * network layer (webRequest); THIS script shows sized tap-to-load placeholders
 * in their place and, on tap (or for heuristic "primary" images), asks the
 * background to allowlist the media URL and then re-triggers the now-permitted
 * fetch. Because the block is at the network layer, nothing downloads until
 * allowed — no "load then hide" race.
 *
 * WHAT IS GATED:
 *   - <img>            — network-blocked; placeholder; tap allowlists + loads.
 *   - <video>/<source>/poster — network-blocked (media); placeholder; tap loads.
 *   - video-embed <iframe> (YouTube/Vimeo/…) — these arrive as sub_frame, which
 *     the network block does NOT cover, so they are gated DOM-side: src stripped,
 *     placeholder shown, tap swaps the real embed back in.
 *
 * FOUR POLICIES (one combined media policy for images + video), cycled by the
 * native "IMG:" button, persisted per-hostname in browser.storage.local:
 *   hide-all             — everything blocked; tapping a placeholder DISMISSES it.
 *   placeholder-tap      — (DEFAULT) everything blocked; tap loads that one item.
 *   primary-content-only — auto-load heuristic "primary" IMAGES (video never auto-
 *                          loads); everything else stays a tap-to-load placeholder.
 *   load-all             — no-op; everything loads.
 *
 * The "primary" heuristic uses ATTRIBUTES/LAYOUT only (never naturalWidth — the
 * image is unloaded, so it has no natural size).
 *
 * MV2, vanilla JS. Robust: every per-element op is guarded — we never break the
 * page; on failure the element is left as-is.
 */
(function () {
    "use strict";

    var HOST = location.hostname || "_local";
    var DEFAULT_POLICY = "placeholder-tap";
    var CYCLE = ["hide-all", "placeholder-tap", "primary-content-only", "load-all"];

    var MIN_PRIMARY_AREA = 200 * 200;
    var LARGEST_FRACTION = 0.6;

    // iframes we treat as video embeds (gated DOM-side).
    var VIDEO_EMBED_RE = /(youtube(-nocookie)?\.com|youtu\.be|player\.vimeo\.com|dailymotion\.com|streamable\.com|twitch\.tv|players\.brightcove|wistia)/i;

    // Social embeds (tweets/X posts, IG, FB, TikTok, Reddit) arrive as sub_frames
    // too. Tracking protection blocks tracker *requests* by category but doesn't
    // hide an embed's own iframe, so we gate these behind a "Tap to load post"
    // placeholder — the element-hiding uBlock would do, without bundling uBlock.
    var SOCIAL_EMBED_RE = /(platform\.(twitter|x)\.com|syndication\.twitter\.com|\/\/(twitter|x)\.com\/|instagram\.com\/(p|reel|tv|embed)|facebook\.com\/plugins|tiktok\.com\/embed|embed\.reddit|redditmedia)/i;

    var policy = DEFAULT_POLICY;
    var observer = null;
    var port = null;

    // url -> [fn]: work waiting for the background to confirm an allow.
    var pending = Object.create(null);

    function log(msg) {
        try { console.log("[eink-images] " + msg); } catch (e) {}
    }

    // --- Autoplay guard (document_start) ------------------------------------
    // The network block only sees requests typed `media`; JS players (MSE/blob,
    // e.g. news-site video) feed a <video> from blob URLs via XHR and call
    // .play() directly, bypassing both the network block AND the stripped
    // `autoplay` attribute. So we neuter playback at the source: override
    // HTMLMediaElement.prototype.play to refuse unless the element is explicitly
    // allowed, plus a capturing 'play' listener that pauses anything that slips
    // through. Installed synchronously before page scripts run. Tapping a video
    // placeholder marks its element allowed (dataset.einkAllow) so it can play.
    var mediaAllowAll = false; // set true only under the load-all policy
    function mediaAllowed(el) {
        try { return mediaAllowAll || (el && el.dataset && el.dataset.einkAllow === "1"); }
        catch (e) { return false; }
    }
    function installPlayGuard() {
        try {
            var proto = window.HTMLMediaElement && HTMLMediaElement.prototype;
            if (!proto || proto.__einkGuarded) return;
            var realPlay = proto.play;
            proto.play = function () {
                if (!mediaAllowed(this)) {
                    try { this.pause && this.pause(); } catch (e) {}
                    try { this.autoplay = false; } catch (e) {}
                    return Promise.reject(new DOMException("blocked by Distesa", "NotAllowedError"));
                }
                return realPlay.apply(this, arguments);
            };
            proto.__einkGuarded = true;
            document.addEventListener("play", function (ev) {
                var el = ev.target;
                if (el && (el.tagName === "VIDEO" || el.tagName === "AUDIO") && !mediaAllowed(el)) {
                    try { el.pause(); } catch (e) {}
                }
            }, true);
        } catch (e) { log("installPlayGuard failed: " + e); }
    }
    installPlayGuard();

    // --- Animations-off lever (settings) ------------------------------------
    // Injects a stylesheet that kills animations/transitions/smooth-scroll — all
    // of which ghost badly on e-ink. Driven by the native settings panel; mirrored
    // into storage so it applies at document_start on the next load (no flash).
    var ANIM_STYLE_ID = "eink-anim-off";
    function applyAnimOff(on) {
        try {
            var existing = document.getElementById(ANIM_STYLE_ID);
            if (on) {
                if (existing) return;
                var st = document.createElement("style");
                st.id = ANIM_STYLE_ID;
                st.textContent =
                    "*,*::before,*::after{animation:none!important;" +
                    "transition:none!important;scroll-behavior:auto!important}";
                (document.head || document.documentElement).appendChild(st);
            } else if (existing) {
                existing.remove();
            }
        } catch (e) { log("applyAnimOff failed: " + e); }
    }

    function absUrl(u) {
        try { return new URL(u, location.href).href; } catch (e) { return u; }
    }

    function srcsetUrls(ss) {
        var out = [];
        if (!ss) return out;
        var parts = ss.split(",");
        for (var i = 0; i < parts.length; i++) {
            var tok = parts[i].trim().split(/\s+/)[0];
            if (tok) out.push(absUrl(tok));
        }
        return out;
    }

    // --- Allowlist round-trip -----------------------------------------------

    function requestAllow(urls, onAllowed) {
        var abs = [];
        for (var i = 0; i < urls.length; i++) if (urls[i]) abs.push(absUrl(urls[i]));
        if (!abs.length) { onAllowed(); return; }
        // Key the callback on the first url; background echoes the list back.
        (pending[abs[0]] || (pending[abs[0]] = [])).push(onAllowed);
        if (port) {
            try { port.postMessage({ type: "allow", urls: abs }); return; } catch (e) { log("allow post failed: " + e); }
        }
        // No port / network block inactive — just run it (DOM-strip fallback).
        onAllowed();
    }

    function onAllowed(urls) {
        for (var i = 0; i < urls.length; i++) {
            var fns = pending[urls[i]];
            if (!fns) continue;
            delete pending[urls[i]];
            for (var j = 0; j < fns.length; j++) {
                try { fns[j](); } catch (e) { log("allowed cb failed: " + e); }
            }
        }
    }

    // --- Dimensions & primary heuristic (attributes/layout only) ------------

    function attrArea(el) {
        var rect = el.getBoundingClientRect();
        var w = rect.width || parseInt(el.getAttribute("width"), 10) || 0;
        var h = rect.height || parseInt(el.getAttribute("height"), 10) || 0;
        return { w: Math.round(w), h: Math.round(h), area: w * h };
    }

    function pageMaxArea(imgs) {
        var max = 0;
        for (var i = 0; i < imgs.length; i++) {
            var a = attrArea(imgs[i]).area;
            if (a > max) max = a;
        }
        return max;
    }

    function isPrimaryImage(img, maxArea) {
        var area = attrArea(img).area;
        var inMain = img.closest && img.closest("main, article, [role='main']");
        if (inMain) return area === 0 || area >= MIN_PRIMARY_AREA; // content image
        if (area < MIN_PRIMARY_AREA) return false;
        return maxArea > 0 && area >= LARGEST_FRACTION * maxArea;
    }

    // --- Placeholder --------------------------------------------------------

    // Page-level display mode. COLLAPSED (default) renders each hidden item as a
    // tiny inline chip so the page reads like clean text — the fix for image-heavy
    // sites (news) where a full-size box per image is just clutter. BOXED keeps a
    // sized dashed slot (better for comics/reference where position/size matter and
    // you'll load most of them). Toggled per-page from the native chrome; persisted.
    var collapsed = true;

    // Auto-collapse. collapseMode drives the page's INITIAL display; in "auto" we
    // start expanded (boxes) and flip to collapsed (chips) once more than
    // collapseThreshold media elements have been gated on the page — image-heavy
    // pages self-tidy, sparse pages stay boxed. A manual ⊞/⊟ toggle sets
    // collapseManual and OVERRIDES auto for the rest of this page session.
    var collapseMode = "auto";        // "always" | "never" | "auto"
    var collapseThreshold = 6;
    var collapseManual = false;       // user toggled -> auto stops flipping
    var gatedCount = 0;               // running count of gated media placeholders

    function maybeAutoCollapse() {
        if (collapseMode !== "auto" || collapseManual || collapsed) return;
        if (gatedCount > collapseThreshold) {
            collapsed = true;
            restyleAllPlaceholders();
            reportCollapsed();
        }
    }

    // (Re)apply the current display mode to a placeholder. Dims + glyph + label are
    // stashed on the element so the same node can flip between chip and box live.
    function stylePlaceholder(ph) {
        var glyph = ph.dataset.einkGlyph || "🖼";
        var full = ph.dataset.einkFull || "";
        var w = parseInt(ph.dataset.einkW, 10) || 0;
        var h = parseInt(ph.dataset.einkH, 10) || 0;
        if (collapsed) {
            // Tiny inline chip — reclaims the space; tap still loads/dismisses.
            ph.style.cssText =
                "display:inline-flex;align-items:center;justify-content:center;" +
                "box-sizing:border-box;border:1px dashed #9a9a9a;border-radius:4px;" +
                "background:#f2f2f2;color:#333;cursor:pointer;" +
                "font:600 12px/1 -apple-system,system-ui,sans-serif;" +
                "vertical-align:middle;padding:1px 5px;margin:0 2px;" +
                "width:auto;height:auto;min-width:0;min-height:0;";
            ph.textContent = glyph;
            ph.title = full;
        } else {
            // Full sized box — obviously a tappable placeholder, not a broken image;
            // sized to the element so there's no layout shift.
            ph.style.cssText =
                "display:inline-flex;align-items:center;justify-content:center;" +
                "box-sizing:border-box;border:2px dashed #8a8a8a;border-radius:6px;" +
                "background:#fafafa;color:#333;" +
                "font:600 13px/1.35 -apple-system,system-ui,sans-serif;" +
                "text-align:center;vertical-align:middle;overflow:hidden;cursor:pointer;" +
                "padding:4px;" +
                (w ? "width:" + w + "px;" : "min-width:96px;") +
                (h ? "height:" + h + "px;" : "min-height:48px;");
            ph.textContent = full ? (glyph + " " + full) : glyph;
            ph.title = "";
        }
    }

    function makePlaceholder(el, glyph, full) {
        var d = attrArea(el);
        var ph = document.createElement("span");
        ph.className = "eink-media-ph";
        ph.dataset.einkGlyph = glyph;
        ph.dataset.einkFull = full;
        ph.dataset.einkW = d.w;
        ph.dataset.einkH = d.h;
        stylePlaceholder(ph);
        return ph;
    }

    // Flip every placeholder on the page between chip and box, without touching the
    // site's saved image policy (a transient per-page view toggle).
    function restyleAllPlaceholders() {
        try {
            var phs = document.querySelectorAll(".eink-media-ph");
            for (var i = 0; i < phs.length; i++) stylePlaceholder(phs[i]);
        } catch (e) { log("restyle failed: " + e); }
    }

    // Manual per-page toggle. Overrides auto for the rest of this page session; NOT
    // persisted (mode drives the initial state on the next load, by design).
    function setCollapsed(val) {
        collapsed = !!val;
        collapseManual = true;
        restyleAllPlaceholders();
        reportCollapsed();
    }

    function reportCollapsed() {
        try { if (port) port.postMessage({ type: "collapsed", value: collapsed }); } catch (e) {}
    }

    function insertPlaceholder(el, ph) {
        el.style.display = "none";
        if (el.parentNode) el.parentNode.insertBefore(ph, el);
        gatedCount++;
        maybeAutoCollapse();
    }

    function removePlaceholder(el) {
        var ph = el.previousElementSibling;
        if (ph && ph.classList && ph.classList.contains("eink-media-ph")) ph.remove();
        el.style.display = "";
    }

    // --- <img> --------------------------------------------------------------

    function stashImg(img) {
        if (img.getAttribute("src")) img.dataset.einkSrc = img.getAttribute("src");
        if (img.getAttribute("srcset")) img.dataset.einkSrcset = img.getAttribute("srcset");
        if (img.getAttribute("data-src")) img.dataset.einkDataSrc = img.getAttribute("data-src");
        if (img.getAttribute("data-srcset")) img.dataset.einkDataSrcset = img.getAttribute("data-srcset");
        img.removeAttribute("src");
        img.removeAttribute("srcset");
        img.removeAttribute("data-src");
        img.removeAttribute("data-srcset");
    }

    function imgUrls(img) {
        var src = img.dataset.einkSrc || img.dataset.einkDataSrc || "";
        var set = img.dataset.einkSrcset || img.dataset.einkDataSrcset || "";
        var urls = [];
        if (src) urls.push(absUrl(src));
        urls = urls.concat(srcsetUrls(set));
        return { src: src, set: set, urls: urls };
    }

    function loadImg(img) {
        var u = imgUrls(img);
        if (!u.urls.length) return;
        // Allowlist every candidate (src + all srcset URLs) so whichever the
        // browser picks is permitted, THEN restore the attributes to fetch.
        requestAllow(u.urls, function () {
            try {
                if (u.set) img.setAttribute("srcset", u.set);
                if (u.src) img.setAttribute("src", u.src);
                removePlaceholder(img);
            } catch (e) { log("loadImg restore failed: " + e); }
        });
    }

    function gateImg(img, tapLoads) {
        if (img.dataset.einkDone) return;
        img.dataset.einkDone = "1";
        stashImg(img);
        var ph = makePlaceholder(img, "🖼", tapLoads ? "Tap to load" : "Image hidden");
        ph.addEventListener("click", function (e) {
            e.preventDefault(); e.stopPropagation();
            if (tapLoads) loadImg(img); else ph.remove();
        }, true);
        insertPlaceholder(img, ph);
    }

    // --- <video> ------------------------------------------------------------

    function stashVideo(v) {
        if (v.getAttribute("src")) v.dataset.einkSrc = v.getAttribute("src");
        if (v.getAttribute("poster")) v.dataset.einkPoster = v.getAttribute("poster");
        v.removeAttribute("src");
        v.removeAttribute("poster");
        v.removeAttribute("autoplay");
        var sources = v.querySelectorAll("source");
        var list = [];
        for (var i = 0; i < sources.length; i++) {
            var s = sources[i].getAttribute("src");
            if (s) { list.push(s); sources[i].dataset.einkSrc = s; sources[i].removeAttribute("src"); }
        }
        v.dataset.einkSources = JSON.stringify(list);
    }

    function videoUrls(v) {
        var urls = [];
        if (v.dataset.einkSrc) urls.push(absUrl(v.dataset.einkSrc));
        if (v.dataset.einkPoster) urls.push(absUrl(v.dataset.einkPoster));
        try {
            var list = JSON.parse(v.dataset.einkSources || "[]");
            for (var i = 0; i < list.length; i++) urls.push(absUrl(list[i]));
        } catch (e) {}
        return urls;
    }

    function loadVideo(v) {
        var urls = videoUrls(v);
        requestAllow(urls, function () {
            try {
                v.dataset.einkAllow = "1"; // this element may now play (user tapped)
                if (v.dataset.einkSrc) v.setAttribute("src", v.dataset.einkSrc);
                if (v.dataset.einkPoster) v.setAttribute("poster", v.dataset.einkPoster);
                var sources = v.querySelectorAll("source");
                for (var i = 0; i < sources.length; i++) {
                    if (sources[i].dataset.einkSrc) sources[i].setAttribute("src", sources[i].dataset.einkSrc);
                }
                removePlaceholder(v);
                try { v.load(); } catch (e) {}
            } catch (e) { log("loadVideo restore failed: " + e); }
        });
    }

    function gateVideo(v, tapLoads) {
        if (v.dataset.einkDone) return;
        v.dataset.einkDone = "1";
        stashVideo(v);
        var ph = makePlaceholder(v, "▶", tapLoads ? "Tap to load video" : "Video hidden");
        ph.addEventListener("click", function (e) {
            e.preventDefault(); e.stopPropagation();
            if (tapLoads) loadVideo(v); else ph.remove();
        }, true);
        insertPlaceholder(v, ph);
    }

    // --- video-embed <iframe> (DOM-side; not network-typed as media) --------

    function gateIframe(frame, tapLoads) {
        if (frame.dataset.einkDone) return;
        var src = frame.getAttribute("src") || frame.getAttribute("data-src") || "";
        var isVideo = !!src && VIDEO_EMBED_RE.test(src);
        var isSocial = !!src && SOCIAL_EMBED_RE.test(src);
        if (!isVideo && !isSocial) return; // only known video / social embeds
        frame.dataset.einkDone = "1";
        frame.dataset.einkSrc = src;
        frame.removeAttribute("src"); // stops the embed fetching / autoplaying
        var glyph = isVideo ? "▶" : "💬";
        var full = isVideo
            ? (tapLoads ? "Tap to load video" : "Video hidden")
            : (tapLoads ? "Tap to load post" : "Post hidden");
        var ph = makePlaceholder(frame, glyph, full);
        ph.addEventListener("click", function (e) {
            e.preventDefault(); e.stopPropagation();
            if (tapLoads) {
                try { frame.setAttribute("src", frame.dataset.einkSrc); removePlaceholder(frame); }
                catch (err) { log("iframe restore failed: " + err); }
            } else { ph.remove(); }
        }, true);
        insertPlaceholder(frame, ph);
    }

    // --- Apply policy -------------------------------------------------------

    function gateElement(el, maxAreaForPrimary) {
        try {
            if (el.dataset.einkDone) return;
            if (policy === "load-all") return;
            var tapLoads = policy !== "hide-all";
            var tag = el.tagName;
            if (tag === "IMG") {
                if (policy === "primary-content-only" && isPrimaryImage(el, maxAreaForPrimary)) {
                    // Auto-load: still route through the allowlist so the network
                    // block permits it, but don't show a placeholder.
                    if (!el.dataset.einkDone) { el.dataset.einkDone = "1"; stashImg(el); loadImg(el); }
                    return;
                }
                gateImg(el, tapLoads);
            } else if (tag === "VIDEO") {
                gateVideo(el, tapLoads); // video never auto-primary
            } else if (tag === "IFRAME") {
                gateIframe(el, tapLoads);
            }
        } catch (e) {
            log("gateElement failed: " + e);
        }
    }

    function gateAll(root) {
        if (policy === "load-all") return;
        var scope = root || document;
        var imgs = scope.querySelectorAll("img");
        var maxArea = policy === "primary-content-only" ? pageMaxArea(imgs) : 0;
        for (var i = 0; i < imgs.length; i++) gateElement(imgs[i], maxArea);
        var vids = scope.querySelectorAll("video");
        for (var v = 0; v < vids.length; v++) gateElement(vids[v], 0);
        var frames = scope.querySelectorAll("iframe");
        for (var f = 0; f < frames.length; f++) gateElement(frames[f], 0);
    }

    var hideScheduled = false;
    function scheduleHides() {
        if (hideScheduled || !hideSelectors.length) return;
        hideScheduled = true;
        setTimeout(function () { hideScheduled = false; applyHides(); }, 200);
    }

    function startObserver() {
        // Run even under load-all if the user has zapped elements here, so their
        // hides survive late DOM injection (JS players mount after load).
        if (observer) return;
        if (policy === "load-all" && !hideSelectors.length) return;
        observer = new MutationObserver(function (mutations) {
            for (var m = 0; m < mutations.length; m++) {
                var added = mutations[m].addedNodes;
                for (var n = 0; n < added.length; n++) {
                    var node = added[n];
                    if (node.nodeType !== 1) continue;
                    try {
                        var tag = node.tagName;
                        if (policy !== "load-all" && (tag === "IMG" || tag === "VIDEO" || tag === "IFRAME")) {
                            gateElement(node, 0);
                        } else if (policy !== "load-all" && node.querySelectorAll) {
                            gateAll(node);
                        }
                    } catch (e) {
                        log("observer node failed: " + e);
                    }
                }
            }
            scheduleHides();
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
    }

    function applyPolicy() {
        try {
            gateAll(document);
            startObserver();
            // Re-run once layout/attributes are available so the "primary"
            // heuristic and dimensions are meaningful (nothing is loaded, so this
            // only reads attributes/getBoundingClientRect).
            if (document.readyState === "loading") {
                document.addEventListener("DOMContentLoaded", function () { gateAll(document); });
            }
        } catch (e) {
            log("applyPolicy failed: " + e);
        }
    }

    // --- Native bridge (via background relay) --------------------------------
    //
    // The native "IMG:" button cycles this domain's media policy. Native can't
    // write storage, so: native pushes {type:"cyclePolicy"} over the port; we
    // compute the next policy, PERSIST it, then reload. We also report the current
    // policy up so the button can label itself, and use the port to allowlist
    // tapped/primary media URLs. Content scripts can't reach the app directly, so
    // runtime.connect() targets background.js, which bridges to the app + owns the
    // webRequest allowlist.

    function connectNative() {
        try {
            port = browser.runtime.connect({ name: "eink-images" });
            port.onMessage.addListener(function (msg) {
                if (!msg) return;
                if (msg.type === "cyclePolicy") cyclePolicy();
                else if (msg.type === "collapseToggle") setCollapsed(!collapsed);
                else if (msg.type === "setCollapsed" && typeof msg.value === "boolean") setCollapsed(msg.value);
                else if (msg.type === "armZap") armZap();
                else if (msg.type === "undoZap") undoZap();
                else if (msg.type === "resetZaps") resetZaps();
                else if (msg.type === "allowed" && msg.urls) onAllowed(msg.urls);
                else if (msg.type === "settings") {
                    if (typeof msg.animOff === "boolean") {
                        applyAnimOff(msg.animOff);
                        try { browser.storage.local.set({ _animOff: msg.animOff }); } catch (e) {}
                    }
                    if (typeof msg.collapseThreshold === "number") {
                        collapseThreshold = msg.collapseThreshold;
                        try { browser.storage.local.set({ _collapseThreshold: collapseThreshold }); } catch (e) {}
                    }
                    if (typeof msg.collapseMode === "string" && msg.collapseMode !== collapseMode) {
                        collapseMode = msg.collapseMode;
                        try { browser.storage.local.set({ _collapseMode: collapseMode }); } catch (e) {}
                        // A mode change is not a manual toggle — re-arm auto + reapply.
                        collapseManual = false;
                        if (collapseMode === "always") collapsed = true;
                        else if (collapseMode === "never") collapsed = false;
                        else collapsed = false; // auto: start expanded, let counter decide
                        restyleAllPlaceholders();
                        reportCollapsed();
                        maybeAutoCollapse();
                    } else if (typeof msg.collapseThreshold === "number") {
                        maybeAutoCollapse();
                    }
                }
            });
            port.onDisconnect.addListener(function () { port = null; });
            reportPolicy();
            reportCollapsed(); // let the native chrome label its collapse toggle
            checkLogin(); // port is now up — flush any pending login report
        } catch (e) {
            // No native bridge — media gating still works via DOM strip; only the
            // cycle button and network allowlist are unavailable.
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

    // --- Login detection ----------------------------------------------------
    // If the page has a password field, tell native so it can relax tracking
    // protection to Standard for this host (Strict can break sign-in). Fires at
    // DOMContentLoaded and on DOM mutations; reports once, when the port is up.
    var loginReported = false;
    var loginObserver = null;
    function stopLoginWatch() {
        // Once we've reported (or decided there's nothing to report), tear the
        // whole-document subtree observer down — otherwise its callback fires on
        // every mutation for the life of the page (SPA/infinite-scroll = constant
        // wakeups) with nothing left to do. Pure battery drain on e-ink.
        if (loginObserver) { try { loginObserver.disconnect(); } catch (e) {} loginObserver = null; }
    }
    function checkLogin() {
        if (loginReported) { stopLoginWatch(); return; }
        if (!port) return;
        try {
            if (document.querySelector('input[type="password"]')) {
                port.postMessage({ type: "loginHost", host: HOST });
                loginReported = true;
                log("login host reported: " + HOST);
                stopLoginWatch();
            }
        } catch (e) { log("checkLogin failed: " + e); }
    }
    function startLoginWatch() {
        checkLogin();
        if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", checkLogin);
        }
        try {
            loginObserver = new MutationObserver(function () { checkLogin(); });
            loginObserver.observe(document.documentElement, { childList: true, subtree: true });
        } catch (e) { log("login observer failed: " + e); }
        // Stop watching once the page has settled — a password field present after
        // load has been seen; one appearing later is rare and not worth a lifelong
        // observer. Belt-and-suspenders with the report-time disconnect above.
        if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", function () {
                setTimeout(stopLoginWatch, 3000);
            });
        } else {
            setTimeout(stopLoginWatch, 3000);
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

    // --- Element zapper (arm-then-tap, persisted per host) ------------------
    // The native "Zap" action posts {type:"armZap"}; the NEXT page tap picks the
    // element under it, hides it, and remembers a structural selector per host so
    // it stays hidden on return. This is the only way to kill inline JS players /
    // arbitrary furniture (e.g. Newsweek's <div> VideoContentHub) that has no
    // src for a network/embed rule to match. Selectors are nth-of-type paths so
    // they survive build-hashed class-name churn (a site restructure needs a
    // re-zap, as the user was told).
    var HIDE_KEY = "_hide::" + HOST;
    var hideSelectors = [];
    var zapArmed = false;

    function selectorFor(el) {
        if (el.id) { try { return "#" + CSS.escape(el.id); } catch (e) { return "#" + el.id; } }
        var parts = [];
        var node = el;
        while (node && node.nodeType === 1 && node !== document.body && parts.length < 6) {
            var part = node.tagName.toLowerCase();
            var parent = node.parentElement;
            if (parent) {
                var idx = 1;
                for (var s = node.previousElementSibling; s; s = s.previousElementSibling) {
                    if (s.tagName === node.tagName) idx++;
                }
                part += ":nth-of-type(" + idx + ")";
            }
            parts.unshift(part);
            if (node.id) { try { parts[0] = "#" + CSS.escape(node.id); } catch (e) {} break; }
            node = node.parentElement;
        }
        return parts.join(" > ");
    }

    function applyHides() {
        for (var i = 0; i < hideSelectors.length; i++) {
            try {
                var els = document.querySelectorAll(hideSelectors[i]);
                for (var j = 0; j < els.length; j++) els[j].style.setProperty("display", "none", "important");
            } catch (e) { /* selector no longer valid after a site change — ignore */ }
        }
    }

    function doZap(el) {
        if (!el || el === document.body || el === document.documentElement) return;
        var sel = selectorFor(el);
        try { el.style.setProperty("display", "none", "important"); } catch (e) {}
        if (hideSelectors.indexOf(sel) === -1) {
            hideSelectors.push(sel);
            var o = {}; o[HIDE_KEY] = hideSelectors;
            try { browser.storage.local.set(o); } catch (e) {}
        }
        log("zapped " + sel);
    }

    // Remove the most recent zap for this host. Un-hiding is a reload: applyHides
    // only ever sets display:none, so re-rendering with the shortened list restores
    // whatever the last selector had hidden without tracking original styles.
    function undoZap() {
        if (!hideSelectors.length) { log("undoZap: nothing to undo"); return; }
        var removed = hideSelectors.pop();
        var o = {}; o[HIDE_KEY] = hideSelectors;
        try { browser.storage.local.set(o); } catch (e) {}
        log("un-zapped " + removed + " (" + hideSelectors.length + " left)");
        location.reload();
    }

    // Clear every zap for this host.
    function resetZaps() {
        if (!hideSelectors.length) { log("resetZaps: none for this host"); return; }
        hideSelectors = [];
        try { browser.storage.local.remove(HIDE_KEY); } catch (e) {}
        log("reset all zaps for " + HOST);
        location.reload();
    }

    function armZap() {
        if (zapArmed) return;
        zapArmed = true;
        // Slight dim + banner so it's clear the next tap picks a target. The dim
        // overlay is pointer-events:none so taps pass THROUGH to the real element;
        // only the Cancel chip is tappable.
        var overlay = document.createElement("div");
        overlay.id = "eink-zap-overlay";
        overlay.style.cssText =
            "position:fixed;inset:0;z-index:2147483646;background:rgba(0,0,0,0.06);" +
            "pointer-events:none;";
        var banner = document.createElement("div");
        banner.style.cssText =
            "position:fixed;top:8px;left:50%;transform:translateX(-50%);z-index:2147483647;" +
            "background:#fafafa;color:#222;border:2px solid #555;border-radius:8px;" +
            "font:600 13px/1.3 -apple-system,system-ui,sans-serif;padding:6px 10px;" +
            "pointer-events:none;";
        banner.textContent = "Tap the element to hide";
        var cancel = document.createElement("div");
        cancel.id = "eink-zap-cancel";
        cancel.style.cssText =
            "position:fixed;top:8px;right:8px;z-index:2147483647;background:#fafafa;color:#222;" +
            "border:2px solid #555;border-radius:8px;font:600 13px/1.3 -apple-system,system-ui,sans-serif;" +
            "padding:6px 10px;pointer-events:auto;cursor:pointer;";
        cancel.textContent = "Cancel";
        (document.body || document.documentElement).appendChild(overlay);
        (document.body || document.documentElement).appendChild(banner);
        (document.body || document.documentElement).appendChild(cancel);
        try { document.documentElement.style.cursor = "crosshair"; } catch (e) {}

        function teardown() {
            zapArmed = false;
            try { document.documentElement.style.cursor = ""; } catch (e) {}
            [overlay, banner, cancel].forEach(function (n) { if (n && n.parentNode) n.parentNode.removeChild(n); });
            document.removeEventListener("pointerdown", onDown, true);
        }
        function swallowClick(e) {
            e.preventDefault(); e.stopPropagation();
            document.removeEventListener("click", swallowClick, true);
        }
        function onDown(e) {
            e.preventDefault(); e.stopPropagation();
            document.addEventListener("click", swallowClick, true); // eat the tap that follows
            if (cancel.contains(e.target)) { teardown(); return; }
            // Resolve the real element under the point (overlay is pointer-events:none).
            var t = document.elementFromPoint(e.clientX, e.clientY) || e.target;
            teardown();
            doZap(t);
        }
        document.addEventListener("pointerdown", onDown, true);
    }

    function start() {
        try {
            // One read for policy + the mirrored view settings, so animations-off,
            // the collapse mode, and the per-host policy are all resolved BEFORE the
            // first placeholders are built (no flash, no wrong-mode first paint).
            browser.storage.local.get([HOST, "_collapsed", "_animOff", "_collapseMode", "_collapseThreshold", HIDE_KEY]).then(function (res) {
                res = res || {};
                applyAnimOff(res._animOff !== false);            // default ON
                if (Array.isArray(res[HIDE_KEY])) hideSelectors = res[HIDE_KEY];
                if (typeof res._collapseMode === "string") collapseMode = res._collapseMode; // default "auto"
                if (typeof res._collapseThreshold === "number") collapseThreshold = res._collapseThreshold; // default 6
                // Mode drives the INITIAL display each load (manual toggles aren't persisted).
                if (collapseMode === "always") collapsed = true;
                else if (collapseMode === "never") collapsed = false;
                else collapsed = false; // auto: start expanded; gatedCount flips it past threshold
                if (res[HOST]) policy = res[HOST];
                if (policy === "load-all") mediaAllowAll = true; // stop gating playback
                log("media policy for " + HOST + " = " + policy + " collapsed=" + collapsed);
                connectNative();
                applyPolicy();
                applyHides();
                if (document.readyState === "loading") {
                    document.addEventListener("DOMContentLoaded", applyHides);
                }
                startLoginWatch();
            }, function (e) {
                log("storage.get failed, using defaults: " + e);
                applyAnimOff(true);
                connectNative();
                applyPolicy();
                startLoginWatch();
            });
        } catch (e) {
            log("start failed: " + e);
            applyAnimOff(true);
            applyPolicy();
        }
    }

    start();
})();
