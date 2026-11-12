# Robot X — SymbioFoundry R&D Project

**Status:** Active R&D · functional Android proof of concept · not a finished consumer application.

Robot X is an Android/Kotlin edge-AI robotics experiment that integrates local conversation, offline speech, camera-based recognition and deterministic Bluetooth Low Energy (BLE) robot control. We built capabilities in stages, first as independent tools and test apps, then combined them into an Android development workbench.

## What works in the A03s proof of concept

On the **Samsung Galaxy A03s**, the development team has exercised movement control, speech-to-text (STT), text-to-speech (TTS), image capture and recognition, and Bluetooth headset activation. The screen is a usable engineering workbench, rather than a polished end-user interface.

A read-only demonstration on **13 November 2026** established the installed `nz.symbiofoundry.robotx` v0.1.0 APK's SHA-256 match with a WORKBENCH build artifact. In the demonstration:

- The interface parsed “Move left” as `TURN_LEFT` (speed 3, 500 ms), correctly **declining actuation because BLE was disconnected**. This tests command interpretation and the disconnected boundary, **not physical movement**.
- A camera-related question returned “This is a person.” Runtime events corroborated a grounded reply and completion of offline TTS. Exact camera-frame-to-answer provenance was **not** established by this inspection.
- The local chat model reported initialization ready. Previous successful physical movement, STT and headset tests are team-reported; the November inspection did not independently rerun every function.

The installed APK is not asserted to be identical to the later development source or to the privacy-edited source shown here. Historical BANK021 acceptance (5 October 2026) and the observed 13 October installation update are separate milestones.

## Engineering architecture

- Deterministic motor-command handling, BLE connection control, timed movement and STOP behavior.
- A local chat model using LiteRT-LM and SmolLM2 135M; physical command authority is separate from generated conversational text.
- Offline STT/TTS integration through sherpa-onnx, with Moonshine/Piper model assets in the private working build.
- CameraX and image-recognition context for grounded responses.
- Local knowledge retrieval and session/persistent memory components.
- Supporting modular experiments: Chat Probe, Headset POC and Vision POC.

See [Architecture](docs/ARCHITECTURE.md) and [Observed POC Evidence](docs/POC_EVIDENCE.md).

## Current R&D direction

The next stage explores which lightweight model and interaction architecture can make Robot X a more capable conversational companion. Model experiments were paused; **Samsung Galaxy S22** is the intended next development platform, not a claimed tested or accepted deployment.

Some newer visual-verification components in this repository remain **experimental and are not live-wired into the main response path**. This project is not safety-certified.

## Source release limitations

This repository is a **reviewed engineering source showcase**, not a turnkey Android project or an installable APK. The publication copy contains privacy-related diagnostic logging edits **that have not been independently compiled or regression-tested**. Large model/voice/vision assets, the locally supplied sherpa-onnx AAR and the Gradle wrapper JAR are excluded. See [Build and Assets](docs/BUILD_AND_ASSETS.md).

Third-party licences and exact asset attribution remain under review; no model weights or vendor binaries are redistributed. **No project-wide open-source licence is granted by this repository at present.** Its public visibility does not imply permission to reuse or redistribute the source.

## About

SymbioFoundry — human-directed engineering and R&D, Wellington, New Zealand.  
https://symbiofoundry.nz
