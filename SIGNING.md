# Android release signing

This repository uses a dedicated test signing identity.

- Repository: `DomEnota-maker/InstagramTest`
- Key alias: `instagram_test_release`
- SHA-256 certificate fingerprint: `D8:B2:85:03:5B:25:DD:AA:A9:B4:A9:4C:78:36:11:CC:60:22:8C:72:9D:97:AA:55:C5:A1:F8:73:82:5C:3E:88`
- Private keystore: **not stored in GitHub**

Every APK/AAB intended to update an already installed test build must be signed with this exact key. Changing the signing key will make Android treat the build as incompatible with the installed app.

The test application ID should also remain unchanged once the first installable build is distributed.
