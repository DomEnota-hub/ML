# Music, Player and YouTube Improvements

> **For agentic workers:** Execute in the existing MediaLoader worktree, preserving the established signed APK and source archive release workflow.

## Scope

1. Restore accent text for successful Shazam recognition; keep error text in the error color.
2. Let the user switch between HitMoz and Muzmo with the current artist/title query, regardless of whether the first search returned results.
3. Stream a chosen result before download. HitMoz already exposes an MP3 URL; Muzmo resolves its info page to a trusted MP3 URL. Never enqueue during preview.
4. From the attached 1.7.31 log, avoid waiting for a weaker saved-session YouTube result after a usable public result. Preserve the higher-quality choice where it arrives promptly.
5. Replace fast seek buttons in the local video player with double taps at the left/right edges; add audio playback speed choices.
6. Keep the common filename field live and validate that the final download uses its numbered names.

## Verification

Add focused policy tests for YouTube quality and common filename generation, run the complete unit suite and arm64 release build, verify the signed APK, and archive source plus Git history. Check playback and source switching on a device when available.
