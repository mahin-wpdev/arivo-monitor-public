# Releasing Arivo from GitHub

Arivo updates are managed from the private GitHub repository using the dedicated **Mahin-Arivo-Release** self-hosted Windows runner.

## Release from GitHub

1. Open the private `mahin-wpdev/arivo-monitor` repository.
2. Go to **Actions**.
3. Open **Release Arivo**.
4. Click **Run workflow**.
5. Enter:
   - Version name, for example `1.1.3`
   - Build number, for example `7`
   - Required update: on/off
   - Rollout percentage: `1-100`
   - Release notes
6. Run the workflow.

The workflow automatically:

- uses the existing local Android runtime configuration without uploading it to GitHub;
- uses the same existing Android signing identity as current installs;
- updates `pubspec.yaml` and `CHANGELOG.md`;
- runs repository checks and Flutter tests;
- builds the release APK using the version/build entered in GitHub Actions;
- creates a GitHub tag and GitHub Release;
- attaches the APK to the GitHub Release;
- publishes that exact APK to the Arivo update server through the authenticated public admin update API;
- verifies the published version, size, rollout/required flags, and SHA-256 before the workflow completes.
- rejects an APK that contains known production credentials;
- refreshes the public source snapshot and publishes the same credential-free APK to `mahin-wpdev/arivo-monitor-public` using the runner's existing GitHub CLI login.

See [Public APK and automatic updates](PUBLIC-APK.md) for the one-time phone connection flow. The mobile UI remains unchanged. The first update from the old embedded-key build needs a dashboard connection link; subsequent updates preserve activation.

## Manage an existing update from GitHub

To change the live update policy without building a new APK:

1. Open **Actions** → **Manage Arivo Update**.
2. Click **Run workflow**.
3. Set **Required update** and **Rollout percentage**.
4. Optionally enter replacement release notes; leave them blank to keep the current notes.
5. Run the workflow.

This workflow changes only update policy metadata through the authenticated Arivo admin API. It verifies that the published APK version and SHA-256 stay unchanged.

## Runner availability

The Windows PC must be online. A current-user watchdog starts the **Mahin-Arivo-Release** runner automatically after Windows login and restarts it if the runner process exits. If the PC is offline, GitHub keeps the release job queued until the runner comes online.

The runner is configured only for this private repository and has the label `arivo-release`. If a release run fails after its GitHub tag/Release was already created, rerun the same GitHub Actions run; the workflow reuses the existing tag/Release, refreshes the APK asset, avoids duplicate server history entries, and commits `pubspec.yaml`/`CHANGELOG.md` only after publishing succeeds.

## Security

No Android signing key, device API key, or server admin password is stored in the repository or GitHub repository secrets. Those remain on the authorized Windows PC and are read by the self-hosted runner only when needed.

Do not replace the existing Android signing identity unless you intentionally plan a signing-key migration. Existing Android installs can update in place only when the new APK is signed with the same key.
