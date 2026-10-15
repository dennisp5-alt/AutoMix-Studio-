# Architecture and validation plan

## Separation
- `AutoMixEngine`: pure Java DSP, not dependent on Android.
- `MainActivity`: Android Storage Access Framework importer, ZIP decoder, job UI, exports.
- The project includes **no mastering DSP**. Mastering Lab v0.15.7 must remain unchanged.
- All source stems are copied into app-private storage; original files are never overwritten.
- Every output generates both a sample-peak-controlled premaster WAV and a JSON recipe/QC file.

## DSP pipeline v0.1
1. Reject invalid WAV headers, unsupported codecs, mixed sample rates and lengths differing by more than 50 ms. Tiny length differences receive zero-padding, with no time shifting.
2. Streaming scan for peak and active-block RMS. File-name role hints are deterministic.
3. Median-reference track gain, ±4 dB maximum per track, with modest role weights.
4. High-pass at 38 Hz for vocals, 24 Hz for other sources (second-order Butterworth).
5. First streaming mix pass finds post-DSP sample peak. If needed, attenuate all channels equally to a -6 dBFS sample-peak ceiling.
6. Second deterministic streaming pass renders interleaved stereo 24-bit PCM WAV.
7. Store mix measurements and exact per-track gain settings.

## Quality principles
- **Keep a transparent A/B reference**: compare the processed premaster against untouched gain-matched summation before adding extra processing.
- Do not call a sample-peak calculation 'true peak'. No LUFS target or mastering in this version.
- Prioritize musical balance, no clipped output and predictable repeatability.
- Every DSP change requires regression fixtures and audition on genuine full songs.

## Constraints
- Up to 4 WAVs in v0.1.
- Inputs supported: mono/stereo RIFF PCM16/24/32 and IEEE float32, 8–192 kHz.
- Mixed formats are decoded, but every WAV in a project must have the same rate.
- Classic RIFF output <= 4GB. RF64/BWF, multichannel audio and cross-file auto-alignment are future work.
- No background job/service yet; run with app foregrounded.
- Export via SAF gives ownership of a copy to the user. Input and output source work files remain app-private.
- Debug signing is only for testing; persistent production signing will be needed before stable releases.

## Integration checkpoint
Before integrating mastering, reconstruct and regression-test a versioned **copy** of Dennis AI Mastering Lab v0.15.7 in an isolated module. Preserve existing outputs and compare LUFS, peak, transients, tonal shift and runtime. No changes to Mastering Lab's original repository or APK.

## v0.2.0 experimental four-stem branch
1. Detect exactly four unique filename roles: vocals, drums, bass, other (or instruments).
2. Analyze ~85 ms co-active vocal and Other windows at the source sample rate; band-pass at 2.5 kHz, plus wideband concurrent RMS.
3. Require >= 1 second AND >= 3% of the timeline as credible co-active material before corrections.
4. Other is allowed at most -0.9 dB level trim; optional fixed -0.75 dB 2.5 kHz Q=1 peaking EQ only when evidence supports presence masking.
5. Preserve existing two-stem branch; a new STEM_REFERENCE mode renders unity-gain, EQ-free 4-stem reference.
6. Output -6 dBFS sample-peak bounded WAVs and QC JSON. True peak, LUFS and musical superiority are **not** measured or asserted by the Android app.
7. Mastering integration is out of scope, and the source repository for Dennis AI Mastering Lab is not modified.
