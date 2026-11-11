# PinBot

Human-paced auto-player for the "land N pins" coin game. Reads the screen, tracks the
coin's rotation, and only taps when the landing spot will be clear.

## Build
1. Push this folder to a GitHub repo (branch `main`).
2. Actions tab -> "Build APK" -> download artifact `PinBot-apk` -> unzip -> install `app-debug.apk`.

## Use
1. "Step 1": turn on the accessibility service "PinBot taps" (once).
   If greyed out: Settings -> Apps -> PinBot -> ⋮ -> Allow restricted settings.
2. Flip the **Bot ON/OFF** switch -> choose "Entire screen" -> Start now.
   While the switch is OFF nothing runs: no capture, no floating button, no taps.
3. Open the game's play screen, tap the floating green ▶ to run (red ■ to pause, drag to move).
4. Switch OFF in the app (or "Stop" in the notification) when done.

## How it stays accurate
- Ring pixels are compared against a clean background ring; thin noise (confetti) is filtered out.
- Rotation speed is measured from ~0.2 s frame pairs; the bot refuses to fire unless the speed is stable.
- It fires only if the nearest pin will be at least `margin` degrees (plus a speed-based buffer) from the landing spot.
- After each shot it watches the pin land and learns the real tap-to-landing latency.
- Human pacing: `minDelay` + random `jitter` between shots, tap position/duration randomised.
- 3 shots in a row not seen landing -> auto-pause.

## Tuning
Save a debug screenshot during play (Pictures/PinBot):
green circle on the coin edge, yellow dots only on pins. Adjust centre/radius/threshold if not.
