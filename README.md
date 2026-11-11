# PinBot

Auto-player for the "land N pins" coin game. Reads the screen, finds stuck pins,
taps when the 6 o'clock landing slot will be clear.

## Build
1. Push this folder to a GitHub repo (branch `main`).
2. Actions tab -> "Build APK" -> download artifact `PinBot-apk` -> unzip -> install `app-debug.apk`.

## Use
1. Open PinBot -> "1. Enable accessibility service" -> turn on "PinBot taps".
   (If greyed out: Settings -> Apps -> PinBot -> ⋮ -> Allow restricted settings.)
2. "2. Start screen capture" -> choose "Entire screen" -> Start now.
3. Open the game on the play screen. Tap the floating green ▶ button (drag to move) to run; red ■ to pause.

## Tuning
Press "Save debug screenshot" during play. Open Pictures/PinBot:
- green circle must sit on the gold coin edge, yellow dots must sit on pins only
- if not, adjust centre X/Y and radius, or the threshold
Then tune latency (ms): bot hits pins coming from one side -> raise/lower it.
