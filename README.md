# Orbit Defense — «مدافع سیاره» 🪐

A 360° tower-defense game for Android, made for kids **ages 7–13**. The in-game
language is **Persian (Farsi), fully right-to-left**, including Persian digits.

Defend a tiny planet at the centre of the screen. Slime-zombies drift in from
**every direction**. **Spin the planet** by dragging it to re-aim all of your
turrets at once, and **tap** empty space to build a new turret on the surface
(it costs energy). Keep the planet's health above zero.

Think *Plants vs. Zombies* meets *Super Mario Galaxy*'s round world — instead of
flat lanes, the whole battlefield is a globe you rotate.

## Screenshots

| Start | Gameplay | Game over |
| --- | --- | --- |
| ![start](docs/screenshots/01-start.png) | ![gameplay](docs/screenshots/02-gameplay.png) | ![game over](docs/screenshots/03-gameover.png) |

> These are faithful design renders of the game's drawing code (the project
> can't compile an APK in this environment, which has no Android SDK). They
> mirror the exact colors, geometry, and Persian/RTL text the app draws.

## How it plays

- **Drag the planet** → spins it, moving every turret around the globe.
- **Tap empty space** → builds a turret on the surface facing that way (costs energy).
- **Turrets auto-fire** at the nearest slime-zombie in range.
- **Kills give energy**; energy builds more turrets.
- A slime that reaches the planet damages it. **Planet health hits zero → game over.**
- Waves get faster and tougher the longer you survive.

## Why this design fits ages 7–13

- One-thumb controls (spin + tap) — a 7-year-old learns it in seconds.
- Difficulty self-scales with waves — still tense for a 13-year-old.
- Cute, non-violent slime enemies; no text-heavy menus, no ads, no network.

See **[DESIGN.md](DESIGN.md)** for the full game design and **[ROADMAP.md](ROADMAP.md)**
for the Agile, sprint-based development plan.

## Tech

- 100% Kotlin, no game engine — a custom `SurfaceView` game loop.
- Only AndroidX `core-ktx` and `appcompat` as dependencies.
- `minSdk 21` (Android 5.0) → `targetSdk 34`.
- All art is drawn at runtime / vector drawables, so there are no binary assets.

### Project layout

```
app/src/main/java/com/kidsgames/orbitdefense/
  MainActivity.kt   # Activity host, keeps the screen on
  GameView.kt       # Game loop, spin/tap input, turrets, waves, HUD, difficulty
  Entities.kt       # Planet, Defender, Enemy, Projectile, Particle + their drawing
```

## Building

Open in Android Studio, or from the command line with the Android SDK installed:

```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

> Note: building requires the Android SDK (`ANDROID_HOME` / `local.properties`).
