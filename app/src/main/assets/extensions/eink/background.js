/*
 * Achroma E-Ink — background relay.
 *
 * WHY THIS EXISTS (GeckoView native-messaging bug fix):
 * GeckoView exposes the native-messaging APIs (runtime.connectNative /
 * runtime.sendNativeMessage) that reach the app's WebExtension.MessageDelegate
 * ONLY to background/extension pages — NOT to content scripts. A content script's
 * runtime.sendMessage/connect() route to the extension's own background context.
 * With no background present, they failed with
 *   "Could not establish connection. Receiving end does not exist."
 * so page flips never reached native and the EPD refresh never fired.
 *
 * This background script is the relay:
 *   content.js  --runtime.sendMessage({flip})-->  here  --sendNativeMessage-->  app.onMessage
 *   images.js   --runtime.connect()------------->  here  <--connectNative--->    app.onConnect
 *
 * The app side (MessageDelegate.onMessage / onConnect + Port) is unchanged: it
 * still sees a "flip" message and an image Port under the native app "browser".
 *
 * MV2, vanilla JS. Robust: every hop is guarded so a failure logs and never
 * throws into the page.
 */

var NATIVE_APP = "browser";

var nativePort = null;
var imgPorts = new Set();

function log(msg) {
    try { console.log("[eink-bg] " + msg); } catch (e) {}
}

// Open the long-lived native channel used for the image-policy port. The app's
// MessageDelegate.onConnect(port) fires for this; app pushes {type:"cyclePolicy"}
// and we fan it out to the content image scripts. Image policy reports travel the
// other way (content port -> here -> nativePort -> app PortDelegate).
function openNativePort() {
    try {
        nativePort = browser.runtime.connectNative(NATIVE_APP);
        nativePort.onMessage.addListener(function (msg) {
            // App -> content (e.g. cyclePolicy). Broadcast to all image ports;
            // the active page acts and reloads.
            imgPorts.forEach(function (p) {
                try { p.postMessage(msg); } catch (e) { log("fanout failed: " + e); }
            });
        });
        nativePort.onDisconnect.addListener(function () {
            log("native port disconnected");
            nativePort = null;
        });
        log("native port opened");
    } catch (e) {
        log("connectNative failed: " + e);
        nativePort = null;
    }
}

openNativePort();

// content.js flip -> native (fire-and-forget one-shot). Reaches app.onMessage.
browser.runtime.onMessage.addListener(function (msg) {
    try {
        if (msg && msg.type === "flip") {
            var p = browser.runtime.sendNativeMessage(NATIVE_APP, { type: "flip" });
            if (p && p.catch) p.catch(function () {});
        }
    } catch (e) {
        log("flip relay failed: " + e);
    }
    // No response — fire-and-forget.
});

// images.js port <-> native. The content port's messages (policy reports) relay
// up to the native port; native pushes (cyclePolicy) relay down via the fanout
// listener registered in openNativePort().
browser.runtime.onConnect.addListener(function (port) {
    imgPorts.add(port);
    log("image content port connected (" + imgPorts.size + " open)");
    port.onMessage.addListener(function (msg) {
        if (nativePort) {
            try { nativePort.postMessage(msg); } catch (e) { log("uplink failed: " + e); }
        }
    });
    port.onDisconnect.addListener(function () {
        imgPorts.delete(port);
    });
});
