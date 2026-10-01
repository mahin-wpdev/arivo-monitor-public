# Arivo Monitor

> Public source snapshot. The production repository and its existing Android APK
> releases remain private. This repository does not include APKs, device API keys,
> signing keys, local runtime configuration, or production release workflows.
> Use your own backend configuration and signing identity for independent builds.

This snapshot was exported from production commit
`6f0e9d63000586d8f16e10e8c2cc11ccc1ff00cf` on 2026-10-01.
See [PUBLIC-SOURCE.md](docs/PUBLIC-SOURCE.md) for the repository's scope.

Self-hosted Android monitoring client and web dashboard.

## Current MVP
- Minimal Android UI: `● App Live`
- Foreground heartbeat and device status sync
- GPS/location history sync
- Android reboot auto-start for monitoring service
- 1-minute screenshots after user-approved screen capture session
- Live screen frames on dashboard after user-approved screen capture session
- Public HTTPS endpoint through the existing VM 107 Nginx vhost
- Premium responsive web dashboard with devices, online/offline status, alerts, map/history, distance, stays, fullscreen screenshot gallery and live view
- Screenshot files stored on the dedicated 1 TB HDD mounted at `/mnt/arivo-monitor-hdd/screenshots`
- Dashboard storage card shows monitoring HDD free/used capacity
- Menu-based dashboard: Overview, Devices, Location, Screenshots, Live Screen, Settings
- Screenshot gallery includes full-screen preview, Download, Delete, manual cleanup, and automatic retention (Never / 1 / 3 / 7 / 30 / 90 days)
- Android app name: `Arivo`; minimal screen text: `Arivo is Active`
- Silent/minimal Android foreground notifications while preserving required platform indicators
- One-time first-run handoff to Arivo's Android notification settings so the parent can manually change notification visibility
- Self-hosted auto-update flow with dashboard APK publish, release notes, staged rollout, version/build metadata, Required Update toggle, upload/download progress, retry state and installer prompt
- Screenshot manager: date filter, per-device/day stats, bulk select/download/delete, per-device retention and integrity reporting
- Live Screen dashboard: stream state, approximate FPS, orientation, frame freshness, fullscreen and session history
- Storage dashboard: SMART health, low-space alert, per-device HDD usage, pressure-based auto cleanup, integrity check and metadata backup
- SMART snapshot timer: `arivo-storage-health.timer` refreshes HDD health every 30 minutes
- Backend managed by `arivo-monitor.service`

Screen capture is never silently re-authorized after reboot; Android's capture consent is required again.
