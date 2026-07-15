/*
 * Achroma E-Ink content script — tap-to-flip page turning.
 *
 * Ported from eita's "Page Scroll" extension (Shared (Extension)/Resources/
 * content.js). Kept: the quantized viewport-jump pagination CORE —
 * getPageHeight/getDocHeight/getTotalPages/scrollToPage/scrollToPosition, the
 * isAnimating + cooldown lock, and deriving the current page live from scrollY.
 *
 * E-INK ADAPTATIONS:
 *   - window.scrollTo uses behavior:"auto" (INSTANT). eita used "smooth", which
 *     ghosts badly on an EPD panel — every intermediate frame smears. A single
 *     jump is one clean partial refresh.
 *   - Navigation is driven by NATIVE edge strips (EdgeNavView), not a JS tap
 *     handler: the app's paging zones live in real inset margins (or transparent
 *     overlays) and message us {type:"navFlip", dir:"next"|"prev"} over the eink
 *     port. We just scroll and signal the EPD refresh. (Native interception also
 *     means an edge tap over a link never opens the link.)
 *
 * Framework-free vanilla JS.
 */
(function () {
    "use strict";

    // Cooldown after a jump before another flip is accepted (ms). Matches eita's
    // SCROLL_COOLDOWN — long enough for the EPD refresh to settle.
    var SCROLL_COOLDOWN = 100;

    // How much of the viewport a "page" spans (fraction/10). eita exposed this as
    // a setting; we pin it to 7/10 so consecutive pages keep a little overlap for
    // reading continuity.
    var pageFraction = 7;

    var isAnimating = false;

    function getPageHeight() {
        return Math.floor(window.innerHeight * (pageFraction / 10));
    }

    function getDocHeight() {
        return Math.max(
            document.body.scrollHeight,
            document.documentElement.scrollHeight
        );
    }

    function getTotalPages() {
        return Math.max(1, Math.ceil(getDocHeight() / getPageHeight()));
    }

    function scrollToPosition(top) {
        var maxScroll = getDocHeight() - getPageHeight();
        top = Math.max(0, Math.min(top, maxScroll));
        if (isAnimating) return;

        isAnimating = true;

        // INSTANT jump — no smooth scroll (see E-INK ADAPTATIONS above).
        window.scrollTo({
            top: top,
            behavior: "auto"
        });

        // Signal a page flip so native can drive the Supernote EPD
        // full-clear-every-N-turns refresh (Epd + RattaEink). Content scripts
        // CANNOT reach the app directly — native messaging (connectNative/
        // sendNativeMessage) is background-only in GeckoView — so we send to our
        // background.js, which relays to the app via sendNativeMessage("browser").
        // Guarded + rejection-swallowed so page flipping never depends on the
        // refresh path.
        try {
            if (typeof browser !== "undefined" && browser.runtime && browser.runtime.sendMessage) {
                var p = browser.runtime.sendMessage({ type: "flip" });
                if (p && p.catch) p.catch(function () {});
            }
        } catch (e) {
            /* ignore — flipping still works without native refresh */
        }

        // Release the lock after the cooldown so rapid taps can't stack jumps
        // mid-refresh.
        setTimeout(function () {
            isAnimating = false;
        }, SCROLL_COOLDOWN);
    }

    function scrollToPage(page) {
        var total = getTotalPages();
        page = Math.max(0, Math.min(page, total - 1));
        scrollToPosition(page * getPageHeight());
    }

    function nextPage() {
        // Derive the current page live from scrollY, then step. This is eita's
        // trick: it self-corrects if the page reflowed or the user scrolled by
        // some other means between taps.
        var currentPage = Math.round(window.scrollY / getPageHeight());
        scrollToPage(currentPage + 1);
    }

    function prevPage() {
        var currentPage = Math.round(window.scrollY / getPageHeight());
        scrollToPage(currentPage - 1);
    }

    // --- Native paging bridge -----------------------------------------------
    // The native EdgeNav strips send {type:"navFlip", dir} over the eink port
    // (relayed by background.js). We just scroll. Content scripts can't reach the
    // app directly, so runtime.connect() targets background.js.

    function connectNav() {
        try {
            var port = browser.runtime.connect({ name: "eink-nav" });
            port.onMessage.addListener(function (msg) {
                if (!msg || msg.type !== "navFlip") return;
                if (msg.dir === "prev") prevPage(); else nextPage();
            });
        } catch (e) {
            /* no bridge — paging just won't fire; page is otherwise unaffected */
        }
    }

    connectNav();
})();
