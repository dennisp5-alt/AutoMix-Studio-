# Dennis AI AutoMix Studio

Independent, offline Android four-track automatic WAV mixing prototype for the Samsung Galaxy S23 Ultra.

## v0.1.1: two-stem balance correction
The Old Girl exposed an undesirable +6.23 dB relative vocal shift in v0.1.0.
Two-stem AUTO now preserves both input gains at unity and bypasses the high-pass filters; only a common sample-peak safety trim is permitted. VOCAL_FOCUS is a separate optional +1 dB vocal candidate for A/B comparison. This avoids interpreting vocal rests as a mixing imbalance.
The UI exposes both premaster exports where a filename identifies vocals. They are not mastered and are not guaranteed to sound better than the original.

## v0.1.0: scope
- Load **2–4** mono/stereo WAVs (16/24/32-bit PCM or 32-bit IEEE float, including standard extensible WAV).
- Import individual files or a ZIP of WAV stems through the Android document picker.
- Require a common sample rate (8–192 kHz), aligned starts, and lengths differing by no more than 50 ms.
- Measure track peak and active RMS; identify vocal/drums/bass/other from filename hints.
- Perform **bounded ±4 dB role-sensitive level adjustments**, 24/38 Hz subsonic filtering, 32-bit float-compatible mixing calculations (internally Java doubles), two-pass peak-safety check and stereo 24-bit WAV output.
- Leave at least **6 dBFS sample-peak headroom** for a separate mastering stage; this is not a finished master.
- No network, account, cloud, upload or external storage permissions required.
- Android APK built and host DSP checks run by GitHub Actions.

## Explicit non-features
Not yet a DAW timeline or finished automatic master. No sample-rate conversion, automatic start-time alignment, full parametric EQ, compression, mastering, loudness normalization, genre inference, AI stem generation, RF64 or multichannel WAV. No guarantee of musical superiority to the untouched sum. We will A/B test before expanding DSP.

## Build
Java 17 + Android SDK 35 + Gradle 8.10.2; from repo root: `gradle :app:assembleDebug`.

The compiled APK is a side-by-side independent `com.dennis.automix` Android application; it does **not** modify Dennis AI Mastering Lab. GitHub Actions uploads the APK as a run artifact. Debug APK is test-signed, not a stable production release signature.

## Engine QA
`javac -d host-classes app/src/main/java/com/dennis/automix/audio/AutoMixEngine.java tests/AutoMixEngineTest.java && java -cp host-classes AutoMixEngineTest`

## Safety
File import is streamed into app-private storage with ZIP extraction limits. v0.1.0 works while the application remains foregrounded; interrupted operations must be restarted. Non-matching sample rates and significant alignment problems are rejected rather than guessed.

## Next steps
1. Blind, loudness-matched A/B tests on genuine aligned stems.
2. Improve the mix decisions with masking-aware EQ, controlled dynamics and candidate scoring.
3. Extract a stable, regression-tested **copy** of the established mastering DSP, with no changes to the original mastering app.
4. Add waveform editing, track control, recipe editing and 8–16 track scaling.
