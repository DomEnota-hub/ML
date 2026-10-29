# VK Login and Diagnostic Inputs Implementation Plan

> For agentic workers: implement these tasks inline, with focused verification after each change.

**Goal:** Complete VK's observed QR handoff and retain full diagnostic inputs across screen visits.

**Architecture:** Keep the VK WebView and its cookie store. Recognize only VK's trusted QR intent, route it to the official VK app when available, and leave a clear browser recovery path otherwise. Store diagnostic controls in private SharedPreferences and initialize them with the user's supplied control values.

- [x] Add pure tests for trusted VK intent classification, including foreign packages and hosts.
- [x] Implement one shared VK navigation handler in the browser and login activity, with safe logging and fallback messaging.
- [x] Store diagnostic links, artist, and song locally on edit; load them when the screen opens.
- [x] Run unit tests, lint, and ARM64 release build; verify version, ABI, and signing identity.
