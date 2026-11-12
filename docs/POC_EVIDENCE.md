# Observed A03s POC evidence — 13 November 2026

This is a summary of a **read-only** Android Debug Bridge (ADB) inspection and user-operated demonstration, not a complete hardware regression test. Screenshots and raw logs were intentionally kept out of this public package because they may contain a face, Bluetooth device identifier, and conversation contents.

## Device/build identification

- Handset model: Samsung Galaxy A03s (`SM-A037F`)
- Application ID: `nz.symbiofoundry.robotx`
- Installed app: versionName `0.1.0`, versionCode `1`; lastUpdateTime `2026-10-13 00:29:21` (device-reported)
- Installed APK SHA-256: `b5b5afd8060a3ee341cb43a8d710a9f0934559727176b990eea225788364811d`
- Exact SHA-256 match found on WORKBENCH: `app/build/outputs/apk/debug/app-debug.apk` (private development source)

## Two demonstrations

1. **Movement interpretation** — The UI displayed a parsed `TURN_LEFT` command at speed 3, duration 500 ms, and explicitly reported **not executed: Robot X is not BLE-ready**. This validates command selection and disconnected behavior in the demonstration; physical movement was not attempted.
2. **Vision/response/TTS** — The UI returned “This is a person” after a camera-related question. The application emitted `E34B4A_GROUNDED_INPUT`, `E34B3_GROUNDED_CONTEXT`, `CHAT_MODEL_INIT_READY`, `CHAT_GROUNDED_REPLY`, `E34B3_GROUNDED_REPLY` and `E25_OFFLINE_TTS_COMPLETE` during the demonstration window. Full frame provenance and STT input-stage causality were not independently established.

## Source/acceptance boundaries

- Historical BANK021 records 55 unit tests and device-install/physical runtime acceptance on **5 October 2026** for its then-accepted snapshot.
- The A03s installation has a subsequent **13 October** update.
- The current WORKBENCH source and this curated, privacy-edited publication copy differ from the historical accepted snapshot. Neither the publication copy nor its modified diagnostics have been freshly built or certified in this handoff.
- User-reported earlier success with motor actuation, headset interaction, STT, and visual recognition remains distinguished from this limited ADB observation.
