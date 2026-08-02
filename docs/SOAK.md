# The 30-minute soak — operator protocol

**Goal:** find out whether the app survives a realistic session. Not a demo, not a
benchmark — half an hour of the thing actually running, watching for the three ways it could
fail a user: it crashes, it runs out of memory, or it cooks the phone.

A finding here is a *result*, not a failure. "The phone got hot" written down is worth more
than "the phone was fine" assumed.

## Before you start

1. **Charge to 100%** and unplug. Battery drain is one of the things being measured, and it
   cannot be measured on a charger.
2. **Airplane mode ON.** It is how the app is meant to run, and it removes radio activity
   from the battery figure.
3. Screen brightness to **50%** and leave it there. Write down what you set.
4. Phone on its stand, 40–70 cm, as for a normal session. Something to actually read.
5. Close other apps.
6. Install the current build and confirm the model is imported.
7. **Note the starting battery percentage and the time.**

## The run

1. Open **Start focus session**. Note the time.
2. **Work normally for 30 minutes.** Read, take notes, do real work. Do not perform for the
   camera — a soak of someone acting is a soak of acting.
3. Let the coach interrupt whenever it wants. Do not trigger it deliberately.
4. **Every 10 minutes, glance at the line under the score** and write down the three figures:

   ```
   session 12:34  ·  8.3 fps  ·  512 MB
   ```

   | at | elapsed | fps | RSS | phone feels |
   |---|---|---|---|---|
   | 10 min | | | | cool / warm / hot |
   | 20 min | | | | |
   | 30 min | | | | |

   "Phone feels" is a real measurement here. **Warm is expected and fine. Hot is a finding**
   — write it down, and say where it is hot (back of the camera? bottom edge?).
5. At 30 minutes, note the **battery percentage** and the time.
6. Leave the screen — that forces a final save. The session file is written continuously, so
   nothing is lost even if the app dies.

## Stop early if

- **The app crashes.** Note the elapsed time and what was on screen. Then run
  `adb logcat -d > crash.txt` if you can, or tell me and I will pull it.
- **RSS goes above 700 MB.** That is the hard budget in CLAUDE.md §2. Note the value and stop.
- **The phone becomes too hot to hold comfortably.** Stop, note the elapsed time, and let it
  cool before doing anything else. Nothing in this project is worth damaging the device.

## Afterwards

Tell me it is done. I pull the session file over adb — you do not need to export anything.

What I will check, and what would count as a failure:

| | pass | finding |
|---|---|---|
| crashes | none | any |
| RSS | stays under 700 MB | any excursion above |
| effective fps | stays at or above 5 | sustained time below |
| frame rate drift | steady | a downward trend = thermal throttling |
| coach latency | steady across the session | later messages slower than early ones |
| governor | few decisions, or none | thrashing, or repeated failures to comply |
| battery | recorded | (there is no target yet — this run is what sets one) |

**The battery figure is the point of the whole exercise.** `contract.json` currently has its
drain limit set to `null` because nothing has ever been measured. This soak is what turns
that into a number, and it will be set from your reading rather than from taste.
