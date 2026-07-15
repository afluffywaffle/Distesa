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
 *   - Navigation is by TAP ZONE: left third = previous page, right third = next
 *     page, middle third = ignored (reserved for links / future UI). No wheel,
 *     no keyboard, no floating nav pill, no drag handle, no settings popup, no
 *     indicator UI, no browser.storage / messaging — this is a bundled built-in
 *     we author, so it just needs to reliably flip pages.
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

        // Signal the native app that a page flip happened, so it can drive the
        // Supernote EPD full-clear-every-N-turns refresh (Epd + RattaEink). In
        // GeckoView, runtime.sendMessage reaches the app's MessageDelegate that
        // was registered under the special native-app name "browser". Guarded so
        // it silently no-ops off-GeckoView (or if messaging is unavailable) —
        // page flipping must never depend on the refresh path.
        try {
            if (typeof browser !== "undefined" && browser.runtime && browser.runtime.sendMessage) {
                browser.runtime.sendMessage({ type: "flip" });
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

    // --- Tap zones: left third = prev, right third = next, middle = ignore ---

    function handleTap(clientX, target) {
        if (isAnimating) return;

        // Let genuine interactive targets (links, buttons, inputs) win — only
        // bare-page taps flip. Middle third is always reserved.
        if (target && typeof target.closest === "function") {
            if (target.closest("a, button, input, textarea, select, [contenteditable], [role='button']")) {
                return;
            }
        }

        var third = window.innerWidth / 3;
        if (clientX < third) {
            prevPage();
        } else if (clientX > third * 2) {
            nextPage();
        }
        // middle third: ignored
    }

    // Use click (fires after a clean tap; a drag/selection won't synthesize one),
    // capture phase so we see it before page handlers that might stopPropagation.
    document.addEventListener("click", function (e) {
        handleTap(e.clientX, e.target);
    }, true);
})();
