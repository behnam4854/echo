# Orbit Defense — Development Roadmap (English)

An Agile, sprint-based plan for **Orbit Defense** («مدافع سیاره»), a 360°
tower-defense game for kids **ages 7–13**. Built in Kotlin on a custom
`SurfaceView` game loop — **no game engine**, by design.

Sprints are ~2 weeks. Each ends with a build that runs on a real device.
> The Persian version of this plan lives in [ROADMAP.md](ROADMAP.md).

## Vision

A bright, kind, non-violent 360° defense game: spin a planet, build turrets,
survive endless waves of slime-zombies. Fully Persian (RTL), no ads, no network.

## Personas

| Persona | Age | Core need |
| --- | --- | --- |
| Younger player | 7–9 | Simple controls, happy feedback |
| Older player | 10–13 | Challenge, strategy, high-score chasing |
| Parent | Adult | Safety, no ads, screen-time control |

---

## ✅ Done — v0.1 (core prototype)

- 360° game loop on a custom `SurfaceView`.
- Spin-the-planet (drag) input; every turret re-aims together.
- Tap to build a turret on the surface (energy cost + spacing rule).
- Auto-targeting turrets, incoming waves, energy economy, planet health, game over.
- Fully Persian RTL HUD with Persian digits; persistent best score.

## ✅ Done — v0.2 (this update)

- **Two turret types:** Rapid (cheap, fast, weak) and Cannon (costly, slow, strong, **splash damage**), with an on-screen selector showing costs.
- **Enemy variety:** Normal, Fast (small/quick), Tank (big/slow) — mixed by wave.
- **Bosses:** a large, high-HP boss every 5th wave.
- **Shockwave special ability:** charges as you kill; tap to damage every enemy on screen.
- **Visual overhaul:** shaded/lit planet, atmosphere glow, glowing turrets and tracer shots, shaded slimes with shadows, nebula background, vignette.
- **Juice:** screen shake and a red damage flash when the planet is hit.

---

## ⬜ Sprint A — Audio & haptics

**Goal:** the game should *feel* alive.

User stories:
- As a player, I hear a satisfying sound when a turret fires and an enemy pops.
- As a player, I feel a short vibration when the planet takes damage.
- As a parent, I can mute music and SFX.

Deliverables: `SoundPool` SFX, looping music with a mute toggle, vibration on damage/shockwave.
**DoD:** all key events have sound/haptics; everything is mutable.

## ⬜ Sprint B — Turret upgrades & economy depth

**Goal:** reward planning, not just spamming turrets.

User stories:
- As a player, I can upgrade a turret's level (e.g., drag two same turrets together).
- As a player, I can sell/replace a turret.
- As an older player, I unlock a third turret type (e.g., slow/frost).

Deliverables: upgrade mechanic + visual tiers, sell action, one new turret type.
**DoD:** upgrades and selling work; economy stays balanced across ~10 waves.

## ⬜ Sprint C — Enemy patterns & boss fights

**Goal:** more variety and drama.

User stories:
- As a player, I face enemies with new behaviors (zig-zag, armored, splitter).
- As a player, bosses have a telegraphed special attack I can react to.

Deliverables: 2–3 new enemy behaviors, an upgraded boss with a wind-up attack, wave preview.
**DoD:** waves feel varied; bosses are readable and beatable.

## ⬜ Sprint D — Meta-progression & retention

**Goal:** a reason to come back tomorrow.

User stories:
- As a player, stars/coins I earn persist between runs.
- As a player, I complete a daily challenge and earn achievements.

Deliverables: `DataStore` persistence, soft currency + unlock shop, achievements, daily challenge.
**DoD:** progression survives app restart; unlocks are reachable.

## ⬜ Sprint E — Difficulty & content modes

**Goal:** one game that fits 7- and 13-year-olds.

User stories:
- As a young child, I can pick an Easy mode (slower, more forgiving).
- As a player, I can play different planets with distinct looks/music.

Deliverables: Easy/Normal/Hard tuning, planet/level selector, 3 planet themes.
**DoD:** three difficulties and three planets are selectable and distinct.

## ⬜ Sprint F — Accessibility & localization

**Goal:** usable by everyone; ready for more languages.

Deliverables: high-contrast mode, larger-text option, colorblind-friendly enemy shapes, `values-en` scaffold (Persian stays default).
**DoD:** a new language can be added without code changes.

## ⬜ Sprint G — Release preparation

**Goal:** shippable on Google Play / Cafe Bazaar.

Deliverables: store icon/feature graphics, Persian store copy, kids' privacy policy, content compliance, signed **AAB**, CI workflow that builds the APK automatically.
**DoD:** a signed release bundle builds and the store checklist is complete.

## ⬜ Sprint H — QA & performance

**Goal:** long-term quality.

Deliverables: unit tests for game logic (targeting, energy, collisions), frame-rate optimization for low-end devices, memory-leak checks.
**DoD:** smooth on a low-end phone; core logic is test-covered.

---

## Future backlog

- Endless mode with a local leaderboard.
- Seasonal events (e.g., Nowruz-themed slimes).
- Pre-wave turret-placement/editor phase.
- Co-op on one device (two players defend together).

## Agile ceremonies (suggested)

- **Sprint planning** at the start of each two weeks.
- **Stand-ups** — short and regular.
- **Review/demo** on a real device at sprint end.
- **Retrospective** to keep improving.
