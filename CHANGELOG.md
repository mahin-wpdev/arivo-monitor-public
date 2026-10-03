# Changelog

## 1.1.7 - 2026-10-03

- Request Android location and screen-recording consent as soon as a newly installed app opens, even when automatic server enrollment is offline; retry enrollment when the app resumes. Flutter UI and signing identity remain unchanged.

## 1.1.6 - 2026-10-02

- Request screen-recording consent once after reboot and first unlock using BOOT_COMPLETED, instead of relying on a manifest USER_UNLOCKED broadcast that Android does not deliver to manifest receivers.
- Keep a visible startup notification when Android blocks opening the consent activity from the background; tapping it opens Android's real consent prompt directly.
- Close the consent activity after Allow or Cancel, retain heartbeat recovery, and only start recording after explicit Android approval. Mobile UI and signing identity remain unchanged.
- Verified the exact published APK installed through the app updater; a real reboot delivered the startup notification, the user approved recording and the consent screen closed, followed by a fresh build-10 heartbeat with recording active and update status current.

## 1.1.5 - 2026-10-02

- Suppressed duplicate screen-recording consent requests when a capture session is active or another consent dialog is pending, and retained request state across activity recreation.
- Foreground update checks now refresh within one minute and poll for completed background downloads while the app remains visible; update dialogs wait until the app has focus.
- Preserved the mobile UI, automatic enrollment, Android installer approval, signing identity and required consent for each new recording session.

## 1.1.4 - 2026-10-01

- New installations automatically register on first app launch without a dashboard connection link; Android permissions and screen-sharing consent remain unchanged.
- Automatic enrollment issues independent random identities and device-scoped tokens, preserving existing activated devices, encrypted storage and the existing signer.
- Added server-side enrollment rate/capacity limits and an ARIVO_AUTO_ENROLL=false switch to disable public registration later.

## 1.1.3 - 2026-10-01

- Enabled public APK distribution without embedding the production device key, preserving the existing Android signer and mobile UI.
- Added one-time dashboard connection links with 10-minute expiry and device-scoped activation tokens, encrypted on Android with Keystore and excluded from backups.
- Preserved HTTPS background update checks, SHA-256 verification and the Android installation approval flow; activation survives future in-place updates.
- Added enrollment authorization tests and an APK credential scan, plus automated public source/APK publishing to the private release workflow.
- Verified the anonymous public APK checksum against the live updater, updated and activated the connected phone on 1.1.3+7, and confirmed current update status and fresh heartbeats before and after restarting the app.

## 1.1.2 - 2026-09-30

- GitHub-managed update pipeline enabled and verified.

## Unreleased
- Added an all-device overview table with per-device online status, heartbeat, battery, network, permissions, last location and today’s distance, plus selection-based multi-device route comparison on the location map.
- Repaired production Nginx HTTP-01 challenge routing for certificate renewal, verified renewal and the automatic reload hook, and documented the aaPanel/Certbot configuration and recovery checks in docs/HTTPS-RENEWAL.md; confirmed a fresh real-device heartbeat and current update status on the unchanged 1.1.2+6 release.
- Release/update management helpers now enforce normal HTTPS certificate validation instead of accepting invalid or expired TLS certificates, so GitHub workflows cannot report a false-success path that Android devices would reject.
- Fixed Android updater stale-cache prompting so an expired check interval refreshes server metadata before showing a cached APK, clears obsolete cached update metadata when already current, and retries promptly after failed checks.
- Pinned the expected Android release signer SHA-256 in the GitHub release workflow and now aborts if either the local signing key or built APK uses a different signing identity.
- Fixed Windows CRLF handling in the GitHub release workflow's remote server verification script so Bash receives valid `pipefail` syntax, and write update metadata as UTF-8 without BOM for server JSON compatibility.
- Added a current-user watchdog and login startup launcher for the self-hosted GitHub runner so it auto-restarts after runner exits and comes back after Windows login.
- Added a private GitHub-managed Android release pipeline with manual version/build, required-update, rollout percentage, and release-note inputs.
- Added a dedicated `Mahin-Arivo-Release` self-hosted Windows runner so signing keys, device/runtime API credentials, and server admin credentials stay on the authorized PC instead of GitHub Secrets.
- GitHub Actions now runs repository checks/tests, builds with the existing Android signing identity, creates a GitHub Release with APK, publishes the same APK through the authenticated public Arivo admin update API, and verifies version/size/rollout/required flags plus SHA-256 before completion.
- Removed the release pipeline's dependency on private VM SSH/LAN reachability; update publishing now works through the existing HTTPS update API while credentials stay only on the authorized self-hosted runner.
- Added a separate `Manage Arivo Update` GitHub Actions workflow to change Required Update, rollout percentage, and optional release notes for the currently published APK without rebuilding or replacing the APK.
- Added `docs/RELEASING.md` with the GitHub release procedure, runner requirements, and signing-key continuity notes.

## 1.0.0 - 2026-09-30
- Created Arivo Monitor Flutter Android client.
- Added minimal App Live screen.
- Added foreground monitoring service for heartbeat, battery, charging, network and GPS state.
- Added GPS location upload and location history backend.
- Added BOOT_COMPLETED recovery and update/unlock recovery hooks.
- Added consent-based MediaProjection screen monitoring.
- Added 1-minute screenshot upload without writing screenshots to device storage.
- Added live-screen frame streaming and dashboard start/stop command.
- Added Node.js backend on VM 107 with systemd autostart.
- Added public HTTPS reverse proxy at /arivo-monitor/.
- Added authenticated web dashboard and end-to-end real-device verification.
- Moved Android API endpoint/key injection to local build configuration instead of source constants.
- Rebuilt and reinstalled the APK; verified fresh heartbeat, GPS, foreground monitoring, public dashboard, and backend autostart.
- Migrated all screenshot storage to the dedicated 1 TB HDD and configured persistent mount on VM 107.
- Added dashboard HDD capacity reporting and verified backend write access through the HDD-backed screenshot path.
- Redesigned the web dashboard with a premium responsive UI, improved status cards, selected-device state, live-screen stage, and fullscreen screenshot preview.
- Reorganized dashboard features into Overview, Devices, Location, Screenshots, Live Screen, and Settings menus to avoid an overly long page.
- Added per-screenshot Download and Delete actions with index/device metadata updates after deletion.
- Added configurable screenshot auto-delete retention: Never, 1, 3, 7, 30, or 90 days, plus manual cleanup.
- Added hourly server-side screenshot retention cleanup; existing screenshots remain untouched while retention is set to Never.
- Renamed the Android app display name to `Arivo` and changed the client screen to `Arivo is Active`.
- Minimized Android foreground notifications: silent, no vibration, no badge, secret lock-screen visibility, and compact `Arivo / Active` text while retaining Android-required foreground indicators.
- Added self-hosted app auto-update support: dashboard APK publishing, version/build metadata, optional/required update flag, background update checks/downloads, SHA-256 verification, and Android installer prompt.
- Bumped Android client to `1.0.1+2` and verified it on the connected test phone.
- Added a one-time first-run handoff to Arivo's Android notification settings after the screen-sharing flow, so the parent can manually change notification visibility. The app does not automatically toggle Android notification settings.
- Bumped Android client to `1.0.2+3`.

## 1.1.0 - 2026-09-30
- Upgraded Screenshots with date filtering, per-device statistics, bulk selection, multi-download, bulk delete, and per-device retention overrides.
- Added screenshot integrity statistics and per-device/day screenshot usage reporting.
- Upgraded Live Screen with stream health, approximate FPS, orientation detection, frame freshness, fullscreen mode, and live session start/stop history.
- Added dedicated Storage dashboard with HDD usage by device, low-space warnings, configurable automatic cleanup thresholds, integrity checks, and metadata backups.
- Added hourly storage-pressure cleanup that removes oldest screenshots when the configured used-space threshold is reached.
- Installed SMART monitoring on VM 107 and added a 30-minute systemd health timer for the screenshot HDD.
- Upgraded App Updates with release notes, staged rollout percentage, publish history, dashboard upload progress, and per-device installed/latest/update progress status.
- Android updater now reports download state/progress in heartbeat, retries failed downloads, verifies SHA-256, deletes mismatched cached APKs, and shows release notes in the install prompt.
- Bumped Arivo Android client to 1.1.0+4 and verified build/install on the connected Android test device.

## 1.1.1 - 2026-09-30

### Low Resource Profile
- Capped screenshot capture width at 720px and JPEG quality at 75%; screenshots are skipped while the screen is off.
- Capped Live Screen output at 540px wide, JPEG quality at 58% (50% under low-memory pressure), and approximately 1 FPS.
- Limited ImageReader buffering to 2 images, use acquireLatestImage(), recycle temporary bitmaps immediately, and clear queued live frames under low-memory pressure.
- Made location upload adaptive: about 45 seconds while moving and 3 minutes while stationary, while keeping 30-second location sampling.
- Set heartbeat interval to 60 seconds.
- Replaced overlapping screen uploads with a single upload worker and a capped 4-frame slow-network queue.
- Added a persistent capped offline location queue of 300 points with automatic batched sync when internet returns; offline screenshots are skipped.
- Uses short partial wake locks only during location/heartbeat/network sync work, with a 15-second safety timeout and immediate release when work finishes; no permanent wake lock remains.
- Kept the Flutter UI unchanged; monitoring work remains in native Android services.

### Reboot Screen Permission Flow
- After reboot, Arivo continues starting its background monitoring service as before.
- On the first user-unlock event, Arivo opens a transparent native permission activity instead of the full Flutter UI.
- The transparent activity immediately opens Android's official MediaProjection consent dialog; the user still presses Start now.
- After consent, ScreenCaptureService starts and the temporary activity closes.
- No separate notification action was added for this flow.
