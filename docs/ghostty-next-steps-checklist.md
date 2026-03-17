# Ghostty Backend Next Steps Checklist

## 1. Phase 5 Cleanup and Invariants
- [x] Make `RenderFrameCache` the only UI render source for Ghostty.
- [x] Treat partial `ScreenSnapshot` payloads as transport/debug data only, not directly renderable frames.
- [x] Remove or debug-gate remaining legacy snapshot fallback paths.
- [x] Document the core Ghostty render invariant: worker publishes deltas, UI applies deltas to cache, renderer draws cache only.
- [x] Document ownership/threading invariants for worker, UI, and native render state.

## 2. Regression Coverage
- [ ] Add an OSC 52 clipboard decode regression test.
- [ ] Add an empty/blank row rendering regression test.
- [ ] Add a zero-column / zero-char row safety regression test.
- [ ] Add a sparse-metadata partial-frame regression test.
- [ ] Add a viewport row-shift application regression test.
- [ ] Add a cursor blink + cached render state regression test.
- [ ] Add a resize + first-frame publication regression test.

## 3. Benchmark and Validation Pass
- [ ] Run the tmux scroll benchmark and save logs/results.
- [ ] Run the large output flood benchmark and save logs/results.
- [ ] Run the SSH resize storm benchmark and save logs/results.
- [ ] Re-test alternate-screen apps (`vim`, `less`, `htop`).
- [ ] Re-test selection, clipboard, mouse scroll, and fling scroll.
- [ ] Record baseline metrics for dirty rows, full vs partial frames, worker build time, frame apply time, and `onDraw()` time.

## 4. Rollout Controls and Safety
- [ ] Add a user-visible runtime toggle for Ghostty backend enable/disable.
- [ ] Keep the Java backend fallback path available during rollout.
- [ ] Add an obvious kill-switch path for disabling Ghostty if device-specific issues appear.
- [ ] Keep debug logging available while preserving optimized native builds for debug APKs.

## 5. Optional Native Row Identity Optimization
- [ ] Add a native row identity / generation token to `FrameDelta`.
- [ ] Prefer native row identity over Java-side content hashing for renderer/cache reuse.
- [ ] Validate tmux scrolling again after row identity lands.

## 6. Renderer Backend Follow-Up
- [ ] Re-evaluate whether Canvas is still the bottleneck after cleanup and validation.
- [ ] Only if needed, draft a `SurfaceView` / GPU-backed renderer follow-up plan.
- [ ] Reuse the same mailbox + persistent render-state model regardless of renderer backend.

## Recommended Execution Order
- [x] Finish Phase 5 cleanup and invariants.
- [ ] Add regression coverage for recent Ghostty edge cases.
- [ ] Run the benchmark/validation matrix and save baseline numbers.
- [ ] Add rollout toggles and fallback controls.
- [ ] Optionally add native row identity tokens.
- [ ] Revisit renderer backend only if validated data still shows Canvas as the bottleneck.
