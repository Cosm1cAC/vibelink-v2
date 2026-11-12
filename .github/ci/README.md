# GitHub CI

The workflow runs on pushes to `main`, pull requests, and manual dispatch. It
tests the Rust Hub, Agent and protocol on Windows; tests both Android editions;
and prepares the desktop overlay against the pinned Cindy commit before running
the desktop bridge and integration tests. It does not deploy services or create
release APKs. No private Clash configuration, signing keys, or account tokens
are used.

The Android runtime archive (`app/src/main/assets/runtime/rootfs.tar.xz`) is not
tracked in the repository. The lock on the current main branch refers to older
Alpine packages that are no longer all available from its configured mirror.
Until that archive is provided or its sources are refreshed, CI reports this
gap and excludes only the two tests that read and install the missing archive.
The remaining Android tests still run. If the archive is present, those tests
and the app's runtime verification task remain enabled. An APK built without
the archive would be incomplete, so this workflow deliberately does not
publish one.

The desktop job uses the public upstream commit pinned in
`vibelink/desktop/manifest.json`. When that pin changes, update the `ref` in
`.github/workflows/ci.yml` to the same commit. GitHub-hosted runners require
network access to the package repositories used by Gradle and pnpm.
