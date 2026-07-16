# Achroma tuning reference

Achroma trades away parts of the modern web on purpose, to stay fast and legible
on a 4 GB e-ink Supernote. Every lever below is a deliberate default you can
change. Two things matter for each: **what it does to page loading** (speed), and
**whether it can break a page** (and the guard we ship so it usually doesn't).

Levers live in **⚙ → More settings…** unless noted. Per-site image behaviour is
the quick **Images** button in the ⚙ panel.

## How the levers affect page loading

| Lever | Default | What it does | Effect on loading / scrolling |
|---|---|---|---|
| **Image / media network block** (per-site policy) | Placeholder + tap-to-load | Blocks `image` / `imageset` / `media` **bytes at the network layer** — nothing downloads until you tap a placeholder | Biggest win on image-heavy / eager-loading sites; near-zero on sites that already lazy-load (e.g. Wikipedia). This is the core e-ink speed strategy |
| **Strict tracking protection** | On | Firefox ETP (strict) — blocks known ad/tracker requests | Large win on commercial sites: fewer third-party scripts, ad slots, and beacons to fetch and lay out |
| **Video autoplay guard** | On (always) | Neuters `HTMLMediaElement.play()` before page scripts run, so JS/MSE players can't autoplay | Avoids the heaviest single cost on news pages — a video decoding + streaming on load |
| **Social-embed gating** | On (always) | Replaces X/Twitter, Instagram, Facebook, TikTok, Reddit embeds with a "Tap to load post" placeholder | Cuts heavy third-party embed bundles that ETP alone doesn't hide |
| **Block web fonts** | On | Blocks `@font-face` downloads → system font fallback | Fewer blocking requests, faster first paint, less layout shift |
| **Animations off** | On | Injects CSS killing animations / transitions / smooth-scroll | Fewer repaints (which ghost badly on e-ink); minor load benefit |
| **JavaScript** | **On** (leave on) | Per-session JS toggle | Turning it off measured ~12% faster on a warm Wikipedia load, but breaks interactive sites — kept **on** to preserve the real-web feel |
| **Element zapper** | Manual, persisted per-site | Hide an arbitrary element (inline JS player, sticky bar) the next tap lands on | Not a network saving — removes visual furniture that has no source to block |
| **Collapse placeholders** | Auto (chips past ~6 media) | Renders hidden media as tiny inline chips vs full boxes | Display only — no load effect; keeps text-heavy pages readable |

> Rough figures from on-device testing: a cold image-heavy load is dominated by
> network + layout, where the media block and tracking protection help most; a
> warm cached load is already ~1 s on Wikipedia, so lever deltas there are small.

## Can a lever break a page? (and the guard)

The reason these are safe-by-default: each lever that *could* break something ships
with a targeted exception.

| Lever | What it could break | Guard we ship |
|---|---|---|
| **Block web fonts** | Icon fonts (FontAwesome, Material Icons, …) render as tofu boxes — buttons/menus become unreadable | Icon-font URLs are always allowed; only **text** web fonts are blocked |
| **Strict tracking protection** | Sign-in / SSO flows that ride on cross-site requests | Auto-relaxes to **Standard** for any host where a password field is seen (remembered per-host) |
| **JavaScript off** | Most interactive sites (menus, search, players, SPAs) | Left **on** by default; only an explicit per-session opt-out |
| **Image / media block** | A site whose primary content *is* the image (comic/manga panel) | Per-site policies incl. **load-primary-content-only** (heuristic) and **load-all**; tap any placeholder to load that one item |
| **Video autoplay guard** | A video you actually want to watch | Tapping the video placeholder marks that element allowed so it plays normally |
| **Social-embed gating** | An embed you want to read | Tap the "Tap to load post" placeholder to swap the real embed back in |
| **Element zapper** | Hiding too much, or a selector going stale after a site redesign | Zaps are per-site and reversible (clear the site's hides); nth-of-type selectors survive class-hash churn, but a restructure may need a re-zap |
| **Animations off** | Rare content that reveals only via transition | Toggle it off per taste; no persistent breakage observed |

## Chrome / navigation (not load-related)

| Setting | Default | Notes |
|---|---|---|
| **Toolbar position** | Auto (bottom on Nomad, top on Manta) | Auto / Top / Bottom; detected from physical screen diagonal (~9″) |
| **Nav zones** | Inset | Inset (narrow the page, strips in the margin) or Overlay (over content) |
| **Nav side** | Both | Both / Left / Right — one-handed friendly |
| **Show tap zones** | On | Faint chevron affordance; zones stay live when hidden |
| **Full-clear cadence** | 6 turns | EPD full panel clear every N page-turns to flush ghosting (0 = off) |
