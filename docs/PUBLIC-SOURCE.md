# Public source repository

This repository shares the Arivo Monitor source code as a clean snapshot.
The production repository, existing 1.1.2+6 APK release, device configuration,
and Android signing identity remain in the private production setup.

The public snapshot includes the Android client, backend, dashboard, and
operational documentation. It excludes the private repository's release
workflows, APK assets, local credentials, signing files, and device data.
The documented production deployment is a reference; configure your own
server address, device authentication, storage paths, and credentials.

The original APK injects a production device API key during the build, so it
is not redistributed here. Independent builds must use their own backend and
credentials and should use per-device enrollment for public distribution.

The production release remains 1.1.2+6. Creating this repository does not
change the installed app or production server. This snapshot is not an
automatic mirror; future production changes require another reviewed export.

Source snapshot: 6f0e9d63000586d8f16e10e8c2cc11ccc1ff00cf, 2026-10-01.
