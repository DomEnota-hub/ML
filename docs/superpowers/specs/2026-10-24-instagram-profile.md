# Instagram profile media search

## Intent

Search public Instagram profiles for photos and videos, choose files, and download them with the same interaction model as VK community images. The already saved Instagram session is used; no new credentials or API key are requested.

## User flow

- Enter a public profile URL or username. Choose an optional date interval with the VK calendar.
- Without dates, scan the latest 300 publications and offer Continue for older ones. With dates, move from newest to oldest and stop at the start date.
- Always scan the main profile feed. A switch additionally scans the separate Reels tab. Reels found in the main feed stay in results when the switch is off. Merge duplicate posts from both tabs.
- Show photo and video previews, grouped by publication; allow selection of a whole publication or individual files, then download selected visuals. Do not include music assets, Stories, Highlights, or ad handling.
- Keep favorites with profile name/avatar and an optional start date, a light search journal with repeat and delete, and a check for new favorite posts. A rejected result does not reappear on later favorite checks.
- Detect previously downloaded files by stable media identity and actual file presence. If a selected file was deleted, offer downloading it again.

## Constraints and risks

- Existing per-post extraction and Android session store can be reused. The VK result database stores only static images, so Instagram needs its own job/item state.
- Instagram profile pagination has no stable public endpoint for arbitrary profiles. Stop on access/rate errors, preserve cursors, and show actionable messages. Never report a partial scan as complete.
- CDN media URLs expire: resolve selected posts again when downloading.
- The available development environment has no user's saved Instagram session or Android device. A signed APK and diagnostics are required for real profile validation.
