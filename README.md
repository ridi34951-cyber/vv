# GyroLock Motion Photo — consistent 3:4 motion frame 3.0.5

This build targets Android 11 (API 30) and asks CameraX for the highest available 4:3 still-image resolution. The actual output size depends on camera hardware and the concurrently bound Preview + VideoCapture + ImageCapture stream combination; verify the saved JPEG dimensions on-device. This experiment aims for 12 MP where the camera exposes it.

## Motion Photo changes
- Keeps the result as one `.jpg` file with the JPEG still, embedded MP4 motion segment, and XMP metadata. It does not publish a separate MP4 companion.
- Fixes invalid XMP caused by writing duplicate `MotionPhoto` and `MotionPhotoVersion` attributes through two prefixes bound to the same namespace URI.
- Retains the modern `Camera:MotionPhoto` and `GContainer:Directory` fields plus legacy `GCamera:MicroVideo*` fields for reader compatibility.
- Increments versionCode to 36 and versionName to 3.0.0.

## Important compatibility note
This is a standards-oriented compatibility fix based on the available Infinix/Transsion APK and the GyroLock source. It is not a complete reproduction of proprietary Infinix camera processing. TikTok and WhatsApp may still treat a Motion Photo JPEG as a still image because app import support varies by version and media picker. Build and test the generated JPEG on the target device; inspect its actual dimensions and whether its embedded motion is recognized.

## Build
Open GitHub Actions, run the included workflow, and download the debug APK artifact. The workflow compiles the Vulkan shaders and builds the Android project.


## 3.0.1 — GCam-style metadata experiment

This test build keeps the photo and video in one `*MP.jpg` file. It writes both modern Android Motion Photo Container metadata and legacy `GCamera:MicroVideo*` fields, while omitting a synthetic `Item:Length=0` on the primary JPEG item. The filename ends in `MP.jpg` as required by the Android Motion Photo 1.0 filename pattern. This is an interoperability experiment, not a guarantee that TikTok will accept the file; test both TikTok Live Photo Comments and the post composer. No standalone MP4 is published.


## 3.0.2 — GCam-style capture experiment

The still-image selector now prioritizes the highest available resolution for a 4:3 aspect ratio instead of requesting only 4000x3000. Gyro stabilization classes and the post-recording stabilization path are retained. Motion Photo remains a single JPEG with embedded MP4 bytes and XMP metadata; no standalone MP4 is published by the Motion Photo writer. This is an experiment: higher still resolution may not be available while the rolling video use case is active, and TikTok acceptance is not guaranteed without device testing.


## 3.0.3 — shutter, focus, flash, gallery and HDR test

- Adds `TCamera:LivePhoto=1` to the Motion Photo XMP and writes modern `Container:Item` directory entries as attributes. Legacy `MicroVideo` fields remain for older gallery readers.
- Publishes the completed JPEG to `DCIM/Camera` through MediaStore with pending-state handling so gallery indexing starts after the copy completes.
- Requests center AF/AE metering before taking the still. When flash is enabled, the LED is used briefly as focus assist, then CameraX is asked to fire its still-photo flash at exposure time. Actual flash behavior depends on the OPPO camera HAL and must be tested on-device.
- Adds an HDR toggle only when Camera2 reports the legacy HDR scene mode. This is a request to the camera HAL, not computational multi-frame HDR, and may not activate the proprietary HDR processing in the stock OPPO camera.
- Uses the recorded shutter request and clip stop time to calculate the XMP still-frame presentation marker rather than always writing a fixed 1.5/2.5 second value.
- Prefers an HD rolling-video quality and transcodes the stabilized embedded motion segment to portrait 720x1000 (or landscape 1000x720) with center crop to avoid stretching. The source still image resolution remains device-dependent.

This remains an experimental build. Gallery motion-photo badges, flash burst timing, HDR behavior, focus, and TikTok Live Photo recognition must be verified on the target phone.


## 3.0.4 — fast shutter-time focus refinement

- Remembers the last point tapped by the user for touch-to-focus and AE metering.
- With flash off, shutter press requests a quick AF/AE refinement at that point (or center if none was tapped); capture proceeds when AF finishes or after a short 400 ms timeout. This is not true manual lens focus: the phone camera driver controls AF behavior.
- With flash on, the torch is used briefly as a focus-assist lamp, the same focus point is metered with a bounded wait, then the torch is turned off and CameraX is asked to fire its still-photo flash for exposure.
- Guards against duplicate capture callbacks when AF completion and timeout happen close together.

The camera HAL may ignore or delay AF/flash requests. This source ZIP was updated, but the resulting APK still needs to be built by GitHub Actions and tested on the OPPO A54; TikTok/OPPO Gallery recognition is not guaranteed.


## 3.0.5 — consistent Motion Photo frame ratio

- Fixes the embedded motion video output from 720x1000 (18:25) to 720x960 (3:4) for portrait clips, matching the intended 3:4 still-photo frame.
- Landscape clips use 960x720 (4:3), the reciprocal ratio.
- This changes only the encoded motion-video frame dimensions; it does not change the camera preview, capture zoom, still-photo dimensions, or shutter/focus flow.
- Expected result: the image frame should no longer appear to shrink during the motion segment and then grow again at the still-photo moment. Verify on OPPO Gallery and TikTok on-device.


## GyroLock 3.0.8 — Soft HDR
- Adds a gentle software tone-mapping pass after still capture: subtle shadow lift, highlight roll-off, and a small saturation increase without aggressive contrast.
- HDR processing is independent of Camera2 HDR scene-mode support. It is disabled while flash is enabled and can be toggled with the HDR button.
- Original JPEG is retained unchanged if processing fails or the source image is unusually large, to protect image quality and memory stability.

## 3.0.8 HDR + Dramatic Cool

- Adds a software single-frame HDR tone-mapping pass that works independently of Camera2 hardware HDR scene-mode support.
- HDR gently lifts shadows and rolls off highlights while keeping midtones natural.
- Adds a separate `COOL ON/OFF` control inspired by the cool, dramatic iPhone-style look: restrained cool tint, mild color richness, and no aggressive contrast.
- HDR and Dramatic Cool can be enabled together or independently.
- Software photo processing remains available when flash is enabled; hardware multi-exposure HDR is still not forced when flash is active.
- Processing is applied to the still JPEG before it is packed into the Motion Photo, so the Motion Photo XMP/container structure remains unchanged.

This is intentionally a single-frame HDR effect, not a true multi-exposure HDR merge. It cannot recover highlights that were already clipped by the sensor.

## 3.0.8 live HDR/filter preview
HDR dan Dramatic Cool sekarang diproses pada preview menggunakan CameraX ImageAnalysis; toggle ON langsung mengganti preview dengan frame yang diproses. Foto akhir tetap diproses oleh SoftHdrProcessor.


## 3.0.11 filter/HDR behavior
- Dramatic Cool is a live preview filter and is visible immediately when enabled.
- HDR is intentionally post-capture only; it is applied to the saved JPEG before Motion Photo packaging.
- HDR does not cover or replace the live camera preview.
- The saved Dramatic Cool grade matches the live preview grade.


## 3.0.11 changes
- Reduced stabilization crop to 1.07x steady-state (1.11x settling start) to reduce the zoomed-in look.
- Softened single-frame HDR substantially.
- Removed live Dramatic Cool preview processing to prevent preview lag; COOL remains a post-capture photo filter.


## 3.0.12 Focus Pulse + 9:16
- Post-shutter AF pulse starts immediately after the still-capture request and finishes in about 0.5 s.
- AF trigger/metering is used instead of a digital zoom or blur effect.
- Added 3:4 / 9:16 Motion Photo mode button.
- 3:4 Motion Photo video: 720x960 portrait.
- 9:16 Motion Photo video: 720x1280 portrait.
- Reduced stabilizer safety crop from the previous 1.11x startup to 1.08x -> 1.06x.


## 3.0.16 Instant Shutter + Fast Focus Pulse
- Shutter submits the still capture immediately; it no longer waits for a new AF cycle.
- Post-shutter focus pulse starts about 10 ms after the capture request and returns to subject AF by about 145 ms, with a hard continuous-AF restore at about 295 ms.
- 3:4 / 9:16 can be switched while the rolling buffer is running; the old "Tunggu Motion Photo selesai dulu" block for the always-on buffer is removed.
- 9:16 final Motion Photo remains 720x1280; 3:4 remains 720x960.
- GitHub Actions build workflow refreshed for version 3.0.16.


## 3.0.21 — centered Motion Photo / delayed post-processing
- Rolling capture now continues for about 1.5 seconds after the still frame is received. With at least 1.5 seconds of buffered video before the shutter, the still should be near the middle of the embedded ~3-second clip.
- The still is first saved as captured. Software HDR/Cool processing runs only after the post-shutter video segment has completed, so processing time does not shorten the clip.
- COOL is applied to both the still JPEG and the embedded video by the Vulkan output shader. HDR remains a post-capture still-photo tone pass, not a true multi-frame HDR merge.
- The rolling camera buffer now prefers SD input to give the still capture more bandwidth; the embedded clip is re-encoded to 720x960 (3:4) or 720x1280 (9:16). CameraX and the camera HAL still control the actual still JPEG resolution; the app displays the captured dimensions so sub-8MP results are visible during testing.
- The flash flow no longer toggles the torch off immediately before capture. CameraX ImageCapture is set to FLASH_MODE_ON directly before taking the still, leaving the actual flash pulse to the still-capture pipeline.
- This source update has not been compiled or tested on a physical phone. Check the GitHub Actions build, flash behavior, actual still dimensions, center-frame timing, and Cool video grade before relying on it.


### Revision 3.0.22 notes
This source revision adjusts the Motion Photo shutter timestamp to prefer the camera image timestamp, adds AF/AE torch assist before the still flash pulse, adds a small end-pulse to DIGITAL 1x/0.5x zoom transitions, and makes stationary detection less sensitive to orientation-dependent gyro outliers. Build and test on the target device before relying on these changes.


## 3.0.23 — high-resolution still and optical ultrawide
The still capture selector prefers supported 4:3 JPEG sizes at or above 8 MP, with a safe unrestricted fallback if the device cannot bind that stream with preview and video. OPTICAL 0.5x can select a separately exposed ultrawide camera when the logical rear camera does not expose sub-1x zoom. Switching separate physical cameras is blocked during active video to avoid cutting the clip. Device-specific verification is required.


## v3.0.30 Concurrent Camera experiment
This experiment attempts a CameraX concurrent rear main + independent ultrawide bind (Preview + VideoCapture + ImageCapture on each). If the device rejects the concurrent stream combination, the existing single-camera fallback is used. The concurrent preview switching path is experimental; continuous dual-buffer recording and seamless cross-camera Motion Photo stitching are not yet verified.


## v3.0.31 — dual rolling buffers + lens timeline experiment
- Keeps separate main and ultrawide CameraX VideoCapture recordings active together when the concurrent session binds successfully.
- The ultrawide preview remains attached with alpha 0 when not selected, instead of being set GONE, to reduce surface teardown during switching.
- Allows lens selection changes during a Motion Photo when concurrent cameras are active and records switch timestamps.
- Attempts to build the shutter-centred Motion Photo by copying intervals from the main/ultrawide buffers according to the recorded selection timeline. The direct mux path requires compatible encoded video formats and falls back to a single selected-lens buffer if it fails.
- Still not compiled or device-tested in this environment; dual-camera support and sample-level mux compatibility are hardware-dependent.


## v3.0.33 experiment
- Keep the secondary ultrawide PreviewView visible with alpha 0 instead of INVISIBLE so its SurfaceProvider can stay active before a lens switch. This targets the black/loading gap caused by creating the secondary preview surface only after selection.
- This does not guarantee concurrent rear-camera support; device CameraX concurrent capability and stream combinations must still be accepted at runtime.


## v3.0.34 concurrent camera pair retry experiment

This experiment tries each exposed rear camera ID as a candidate main camera, prioritizing pairs advertised by Android concurrent-camera capability metadata. It logs each pair attempt and only reports concurrent mode active after CameraX returns two camera objects. The build and device behavior still require verification in GitHub Actions and on the target Samsung device.


### v3.0.35 concurrent diagnostics
If concurrent rear binding fails, the app now displays an on-screen Toast and status summary with the reason/candidate count, so testing does not require Logcat. This is diagnostic only; it does not claim concurrent support unless CameraX binds two cameras.


### v3.0.36 logical/physical camera probe
If the Android concurrent camera set does not advertise the rear main + ultrawide pair, this version also probes `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA` and `LOGICAL_MULTI_CAMERA_PHYSICAL_IDS`. The result is included in the on-screen diagnostic and Logcat under the camera diagnostic tag. Finding physical IDs is evidence for a possible custom Camera2 physical-output implementation, not proof that the device can stream both lenses concurrently.


### v3.0.37 compile fix
- Replaces the nonexistent `LOGICAL_MULTI_CAMERA_PHYSICAL_IDS` key with the `CameraCharacteristics.getPhysicalCameraIds()` API (guarded by the existing Android 9+ SDK check).
- Bumps versionCode to 63 and versionName to 3.0.37.
