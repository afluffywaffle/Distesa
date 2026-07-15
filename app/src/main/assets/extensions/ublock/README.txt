uBlock Origin — built-in WebExtension slot
==========================================

MainActivity installs a built-in WebExtension from this exact folder at startup:

    resource://android/assets/extensions/ublock/

This folder currently contains ONLY this README. No .xpi is fabricated or
committed. Until you drop a real extension here, the install call logs a warning
("uBlock install failed ... extension missing") and the app runs WITHOUT ad
blocking — it does NOT crash. That is expected for the Phase 0 spike.

To enable ad blocking, place the UNPACKED contents of uBlock Origin here so that
this directory contains a top-level manifest.json (plus the extension's js/,
assets/, _locales/ etc.). Two ways to get those files:

  1. Download the uBlock Origin .xpi from
     https://addons.mozilla.org/firefox/addon/ublock-origin/
     (an .xpi is just a ZIP). Unzip it into this folder so manifest.json sits
     directly at assets/extensions/ublock/manifest.json.

  2. Or produce a web-ext build of the extension and copy its contents here.

After adding the files, rebuild the APK. GeckoView's installBuiltIn() requires
the manifest at the root of this resource path.
