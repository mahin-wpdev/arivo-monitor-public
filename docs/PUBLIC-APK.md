# Public APK and automatic updates

The public APK is distributed in `mahin-wpdev/arivo-monitor-public`.
It contains the HTTPS server address, but no shared device key or admin password.
The mobile screen remains unchanged.

## Connect a new phone

1. Download and install the latest public APK.
2. Open the authenticated Arivo dashboard on that phone.
3. In Devices, select **Create connection link**, then **Connect this phone**.
4. Complete the existing Android permission and screen-sharing prompts if desired.
5. Confirm that the phone appears online in the dashboard.

The connection link targets the Arivo Android package. It expires in 10 minutes
and can be used once. The server issues a random token restricted to that phone's
device identity and stores only its hash. Reconnecting a phone rotates its token.
The app encrypts its token with Android Keystore and stores it outside Android
backup. No token or connection code is committed to Git or put in an APK.

Existing 1.1.2+6 devices can receive the new APK through the existing updater.
After this first migration they need one connection link. Later updates retain
activation and use the same Android signing identity.

## Automatic updates

Activated phones check the existing HTTPS update endpoint in the background.
The app verifies downloaded APK SHA-256 and shows the Android installer prompt.
Android still requires the user's installation approval and may require allowing
updates from Arivo. The UI and screen-sharing consent flow remain unchanged.

The private Release Arivo workflow continues to build, verify the pinned signer,
scan the APK for production credentials, and publish the exact APK to the update
server. It also refreshes the reviewed public source snapshot and attaches that
same APK to a public GitHub Release. The self-hosted runner uses its existing
local GitHub login for that second repository, without putting tokens into source.

The public source export omits production workflow files and local configuration.
Its history contains only reviewed snapshots; the private production history
is not exported. Older APKs that contain a shared production key stay private.

## Verified release: 1.1.3+7 (2026-10-01)

- Flutter's original UI test passed, and the mobile UI source is unchanged from
  the previous production version.
- Enrollment tests passed for admin approval, expiry, one-time redemption,
  token rotation, persisted hashes and rejection of another device's requests.
- The public APK passed the production credential scan and the pinned signer
  verification. The scan also correctly rejected the old embedded-key APK.
- Anonymous GitHub download, the locally built APK and server update metadata
  matched SHA-256 `36c92b55daffb26fc798aeb99d9f99a3aedd9cbc434e238e58062219f14dfddf`.
- The connected phone was updated in place to 1.1.3+7, connected using a one-time
  dashboard link and reported update_status=current with a fresh heartbeat.
- After restarting the app, encrypted activation remained valid and another
  fresh heartbeat arrived at 2026-10-01T12:39:12.673Z (18:39:12 Dhaka).
- Server policy remains required=false and rollout=100%; Android's installation
  approval and screen-sharing consent remain in place.
