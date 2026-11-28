# M05 visual foundation

`LuminaSurface` and `Modifier.luminaSurface` own materials. The modifier always
uses the opaque fallback; it cannot allocate blur. `luminaScrim` owns the single
static image/video mask, and `luminaStatus` composites state colors onto an opaque
surface. Screens must not implement their own background, blur, or reflection.

| Level | Intended content | Current material / fallback |
| --- | --- | --- |
| Canvas | Page background, poster or video | Solid dark base; media may have one static mask. No blur. |
| Content | Posters, details, settings groups, login | Tint composited onto an opaque surface; neutral boundary where needed. No blur/reflection. |
| Floating | Rail, filters, controls | Opaque by default. Explicit FrostPanel may request the one floating effect slot. |
| Overlay | Track/source pickers, confirmation | Static dimming plus an opaque, high-contrast panel. |

`resolveEffectTier` is the only capability resolver. Existing `frosted` requests
FULL, `clear` requests REDUCED, and `off` requests OFF. FULL requires API 31 or
newer and a confirmed non-low-RAM device. Unknown device capability fails closed.
The global lease budget allows at most **one** blurred Floating panel. Other
mounted panels use the opaque fallback; disposal transfers the slot without
changing dimensions. FrostPanel retains its own API/low-RAM guard as protection.

OFF uses opaque fills and no blur, reflection, decorative highlight, or idle
animation. REDUCED uses a static Canvas and at most one tint per Floating panel,
with no reflection, idle animation, or blur. A static settings comparison draws
three examples without constructing any blur effect or taking a blur lease.

Motion reads the same tier and observes the Android animator, transition, and
window animation scales. A zero system scale, REDUCED, or OFF disables decorative
motion, carousel displacement and focus scaling. Focus borders remain immediate.
The focus contract is 3dp / 1.04, rendered without resizing neighbours or adding
an expanding focus gutter. Nominal focus / press / page times are 160 / 120 /
220ms; the existing M04 picker remains 200ms, all reduced to zero when required.

TV safe insets clamp 4% of available width to 24–48dp. Login retains its existing
wide/narrow hierarchy, but admits the wide arrangement only when rail, brand and
form widths and available height fit at the current font scale. Content / group /
modal radius tokens are 14 / 20 / 24dp. Existing settings rows keep title,
two-line description and right-aligned value columns. Palette assertions target
4.5:1 for body text and 3:1 for control boundaries, including a bright image under
the REDUCED panel. These are palette checks, not a claim of a complete device
accessibility audit. Motion timings still need device calibration.

Base theme resolution maps `ink` to `oled`. Unsupported presets fall back to
`oled` and log `fallback=true`; alias resolution logs `mapped=true`. Both retain
the stored preference. Settings uses the resolved key, so the selected preset
exists and matches the applied color. Only an explicit user selection writes a
new preference.

Migration was performed in this order:

| Screen | Levels | Blur | Fallback |
| --- | --- | --- | --- |
| Home | Canvas, Content, Floating, Overlay | None | Opaque cards/navigation/menus, static poster mask |
| Search | Canvas, Content | None | Opaque page/controls/posters |
| Detail | Canvas, Content, Overlay | None, including hero image | Opaque information/pickers, static poster/video mask |
| Favorites | Canvas, Content | None | Opaque posters, single static image mask |
| Settings | Canvas, Content, Floating, Overlay via dialogs | FULL rail only, global maximum 1 | Static tint in REDUCED; opaque in OFF or denied blur slot |
| Login | Canvas, Content, Floating, Overlay | None | Opaque form/input/rail/QR panel, static image mask |

Player UI surfaces also use Canvas/Floating/Overlay so OFF cannot leave old
translucent panels behind. Playback engines, sessions, diagnostic collection,
preferences storage, versions, dependencies, and workflows are outside this
change. Cross-file edits connect existing containers to the shared policy and
tokens, remove fixed focus delays, and add the explicitly requested sample suite;
they do not reorganize page hierarchies. Splash uses the same motion contract for
its decorative loading sweep.
