# Field Kit

An offline Android measurement and documentation tool for characterising
suspected directed acoustic or electromagnetic emission sources. It measures,
logs and reports. It does not detect RF, identify devices, or protect anyone.

Six modes on a bottom tab bar, all logging into the open **session**:

| Tab | Mode | What it records |
|---|---|---|
| Acoustic | Acoustic Survey | Live spectrogram + spectrum (20 Hz-24 kHz), 18-24 kHz band meter, logged peaks, spectrogram snapshots |
| Log | Phenomenology Log | Timestamped observations behind a required control checklist; timeline |
| Shield | Shielding Test | Guided Faraday-enclosure vs hearing-protection protocol with an interpretation card |
| Range | Range Map | Guided transect: detection, intensity, animal reaction and mic level at each stop; falloff assessment |
| Magnet | Magnetometer Sweep | µT trace against a locally captured baseline, flagged anomalies |
| Report | Session Report | Text + PDF report, saved via the system file picker or shared |

The info icon in the top bar opens **Safety** from every mode. The overflow
menu has **Sessions**, **Hardware limits**, and the light/dark switch.

## Build a debug APK in Android Studio

1. Install Android Studio (Ladybug 2024.2 or newer). Let it install the
   Android SDK Platform 35 when it offers to.
2. **File > Open**, choose this `FieldKit` folder (the one containing
   `settings.gradle.kts`), and trust the project.
3. Wait for the Gradle sync to finish. The first sync downloads Gradle 8.9,
   the Android Gradle Plugin and the AndroidX libraries, so it needs internet
   once. (The app itself never uses the network.)
4. If Studio offers to upgrade the Android Gradle Plugin, choose **Don't
   upgrade** for the first build: the pinned versions are a known-good set.
5. **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
6. When the "APK(s) generated" notice appears, click **locate**. The file is
   `app/build/outputs/apk/debug/app-debug.apk`.
7. To run it directly instead: enable USB debugging on the phone, connect it,
   pick it in the device drop-down and press **Run**.

Command line equivalent, from this folder: `./gradlew assembleDebug`
(`gradlew.bat assembleDebug` on Windows). Unit tests: `./gradlew test`.

If the sync fails with a JDK error, set **Settings > Build, Execution,
Deployment > Build Tools > Gradle > Gradle JDK** to a JDK 17 or 21.

Use a real phone. The emulator has no useful microphone response,
magnetometer or GPS.

### Versions

| | |
|---|---|
| Android Gradle Plugin | 8.7.3 |
| Gradle (wrapper) | 8.9 |
| Kotlin + Compose compiler plugin | 2.0.21 |
| Compose BOM | 2024.12.01 (Material 3 1.3.x) |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |

## Project layout

    settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew*, gradle/wrapper/
    app/build.gradle.kts
    app/src/main/AndroidManifest.xml
    app/src/main/res/                    launcher icon, window theme, FileProvider paths
    app/src/main/java/com/fieldkit/app/
      MainActivity.kt                    single activity, hosts Compose
      AppViewModel.kt                    open session, drafts, saving
      core/                              no Android imports; unit-tested
        Fft.kt                           radix-2 FFT, dB spectrum, band power, peak finder
        Analysis.kt                      shielding logic, range falloff, GPS distance, anomaly detector
        Texts.kt                         every caption, limit and safety statement; number formatting
      data/
        Models.kt                        session data classes
        SessionJson.kt                   org.json read/write
        SessionStore.kt                  files/sessions/<id>/session.json + snapshot PNGs
      audio/AudioEngine.kt               AudioRecord 48 kHz capture thread
      sensors/                           magnetometer and GPS wrappers
      report/                            report text, PDF writer, export/share
      ui/
        AppRoot.kt                       top bar, six tabs, overlays
        Common.kt                        shared widgets, permission explainer
        theme/Theme.kt                   Material 3 colours (dark default)
        screens/                         one file per mode, plus Sessions, Safety, Hardware limits
    app/src/test/java/com/fieldkit/app/  JUnit tests for core/ and the report text

## Design choices

- **Jetpack Compose, single activity, no navigation library.** Six tabs and
  three overlays are a `when` over two enums in `AppRoot.kt`.
- **FFT: self-contained radix-2 implementation** (`core/Fft.kt`), not
  JTransforms. One 4096-point real transform 23 times a second is trivial
  work, and it keeps the app free of third-party code. Tests check it against
  a direct DFT and known sine inputs.
- **Storage: plain JSON files**, not Room. A session is one small document
  always read and written whole; writes go to a temp file and are renamed.
- **Offline by construction.** The manifest has no `INTERNET` permission, so
  the app cannot open a connection. Dependencies are AndroidX only.
- **Audio is never stored.** Samples are analysed in memory and discarded.
- **Coordinates are never stored.** GPS is used only to compute the distance
  from the transect start point; the report holds distances.

## Permissions

| Permission | Asked | Why |
|---|---|---|
| `RECORD_AUDIO` | First time you open Acoustic Survey | Live spectrum. Also lets Range Map sample a 1-second level per stop |
| `ACCESS_FINE_LOCATION` | When you switch on GPS in Range Map | Relative distance between transect stops |
| `ACCESS_COARSE_LOCATION` | With the above | Android 12+ requires it to be declared and requested alongside fine location |

Each is explained in-app before the system prompt appears. Everything else
works if you decline: Range Map falls back to paced distances.

## Where the app goes beyond the written spec

- **Shielding Test** adds a baseline step (the test cannot run unless the
  effect is present) and a washout step between conditions (did the effect
  return?). Results can be "reduced", which gives "ambiguous" readings rather
  than forcing one of the four outcomes. Every interpretation carries the
  caveat that an unblinded, self-reported test cannot prove a mechanism.
- **Range Map** records perceived intensity (0-5) and, if the microphone is
  allowed, a 1-second mic level at each stop. Yes/no detection alone cannot
  show inverse-square falloff; the mic level against distance can (-20 dB per
  tenfold distance), so that is what the inverse-square flag is based on.
- **Phenomenology Log**: checklist item (a) has a "no animals present"
  option, and an entry can be marked as the baseline itself, so the first
  entry of a session is loggable.

## What has and has not been verified

This project was written in an environment with no Android SDK and no access
to Google's Maven repository, so **the Gradle build has not been run**. What
was checked instead:

- `core/`, `data/Models.kt` and `report/ReportText.kt` were compiled with the
  Kotlin 2.0.21 compiler and their 18 unit tests pass.
- Every Kotlin file was type-checked with the same compiler against
  hand-written declarations of the Android and Compose APIs it uses. That
  catches syntax errors, type errors, missing imports and wrong names in this
  code. It does not prove the declarations match the real libraries.
- All XML parses.

Not verified: the real Gradle sync and build, the Compose compiler plugin's
own checks, and all on-device behaviour (audio capture, sensors, GPS, PDF
output, file picker, share sheet). Expect that a first build may need a small
fix; the compiler output will point at the line.

## First test session (10 steps)

1. Open the app, read the Safety screen, tap **Understood**.
2. Menu > **Sessions** > name the session after the place and date > **Start new session**.
3. **Log** tab: with the suspected source absent or off, tick "This entry IS the baseline", fill in the checklist and log one entry.
4. **Acoustic** tab: allow the microphone, tap **Start survey**, hold the phone still for 30 seconds.
5. Tap **Log 18-24 kHz peak**, then **Save snapshot**. Stop the survey.
6. **Magnet** tab: **Capture baseline** away from electronics, then **Start sweep**, walk the area slowly, **Stop and save**.
7. **Log** tab: with the source present, log what each observer reports (checklist first).
8. **Shield** tab: if the effect is present now, run one full run through both conditions and save it.
9. **Range** tab: set a start point, log a stop every 5 m until two stops past where the effect ends, **Finish and save**.
10. **Report** tab: add session notes, **Save PDF...**, and read the "What this report can and cannot show" section first.
