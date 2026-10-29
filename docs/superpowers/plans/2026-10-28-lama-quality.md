# LaMa quality and Instagram diagnosis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Improve local inpainting input/output and identify account-specific Instagram restrictions.
**Architecture:** Source-space masks, contextual square ROI with reflection, shared preview/export coverage; isolated ONNX runner. Instagram error parsing keeps bounded full body before log truncation.
**Tech Stack:** Kotlin, Android Bitmap, ONNX Runtime, JUnit/Robolectric.
**Spec:** docs/superpowers/specs/2026-10-28-lama-quality-design.md

## Global Constraints
- No release, APK upload or version bump.
- No image uploads, new model downloads or dependencies.
- Preserve original pixels outside edited coverage and normalized stroke geometry.

## Review Focus
- Edge/corner selections and non-square source: reflection and uniform mapping.
- Eraser-only/empty coverage: avoid model call.
- Multiple distant tiny marks: do not disappear during downsampling.
- Feathered edges: avoid applying coverage twice on export.
- Long Instagram error: classify restriction before logging only 600 characters.

### Task 1: Preparation and composition
- [x] Add failing tests for ROI bounds, aspect-independent masks, expansion/edge blending, unchanged pixels and overlay round trip.
- [x] Implement InpaintingPreparation.kt (geometry, coverage, tensor preparation, composition).
- [x] Split LaMaOnnxRunner.kt from engine; wire preparation and shared export mask.
- [x] Run focused tests; inspect synthetic examples. Record real-model limitation.

### Task 2: Instagram diagnostics
- [x] Add regression fixture with valid restriction JSON longer than 600 characters and sensitive-content hint.
- [x] Keep bounded full response for parsing and truncate logging independently.
- [x] Run focused tests and verify no session/cookie disclosure.

### Task 3: Verification
- [x] Run complete unit suite and compileDebugKotlin; review diff and commit.
- [x] Report results and account settings to check. Do not publish a release.

## Execution evidence

- Base: 71b7f39. Feature branch already isolated from main.
- Preparation RED: local ROI + expanded coverage failed; GREEN focused suite.
- Tensor/composition regression RED: zeroed input / unchanged output failed three tests; restored implementation passes.
- Eraser RED: exact undo stroke retained antialias residues and invoked model; opaque expanded CLEAR fixes it.
- Instagram RED: long server response classified TEMPORARY_FAILURE; bounded full parsing now reports UNSUPPORTED with sensitive-content hint.
- Fresh review: one Important finding (reflected mask must mirror selected image pixels).
  Regression reflectedSelectedPixelsAreNotExposedAsKnownContext RED → GREEN.
- Final: :app:testDebugUnitTest :app:compileDebugKotlin --offline — BUILD SUCCESSFUL.
- Real model weights and Android device unavailable; no visual quality benchmark claimed.
- No APK build/upload, version bump or publication. No model/image network requests.
