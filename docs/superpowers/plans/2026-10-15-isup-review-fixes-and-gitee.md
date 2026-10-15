# ISUP review fixes and Gitee submission implementation plan

**Goal:** Fix the four confirmed review findings and station overlay concurrency, verify the complete source, and submit the reviewed ISUP project to the authorized Gitee repository.

**Architecture:** Reuse the existing retry policy, session lifecycle, per-channel overlay writes/readback, and MVC advice. Add only the missing failure classification and connection/station boundaries. Store configuration secrets outside version control and use environment placeholders in committed profiles.

**Tech stack:** Java 21, Spring Boot 3, JUnit/Mockito, Maven, FFmpeg, Git.

## Constraints

- Do not modify ERP, production data, or the running server in this task.
- Keep pure-video copy/remux; do not restore audio processing or expensive frame analysis.
- Preserve current dirty source changes and existing packing/outbound APIs, layouts, retry limits and URL compatibility.
- Never stage real credentials, production recordings, build outputs, release backups or temporary diagnostics.
- User has approved the review fixes and final commit/push; no further generic approval gate.

## Work and verification

- [x] Recording: first reproduce that a real short-clip exception is permanently quarantined; distinguish insufficient duration from invalid/oversized recordings; reuse bounded retry and test limits/old recordings.
- [x] Lifecycle: first reproduce old offline callback stopping a new connection; carry old connection identity through callbacks and stop sessions through the expired generation while preserving newer reconnects; fence late session publication with the existing lifecycle monitor and verify reused native handles.
- [x] Station: first reproduce interleaved panorama/detail writes; serialize the entire station update without serializing unrelated stations; preserve both-channel failure reporting and expected-number clear protection. Test concurrent SHOW/SHOW, SHOW/CLEAR and failed writes.
- [x] Media: load global advice in tests, prove 401/404/400 become 500, add explicit ResponseStatusException handling, and verify existing generic errors remain 500.
- [x] Configuration: snapshot real profiles into an ignored private directory, replace secrets with environment placeholders, add secret/artifact guards, and test that a fresh checkout is self-contained except documented external configuration/SDK assets.
- [x] Review every fix independently; run full Maven production-profile verification with actual FFmpeg plus frontend tests and syntax checks.
- [ ] Inspect staged file manifest and reachable Git history for actual secret values and temporary artifacts; verify the Gitee target refs, commit only reviewed project files, push without force, and read back the remote commit.

## Submission target

User-provided project: https://e.gitee.com/henanzzjg/repos/henanzzjg/zzjg-isup/sources

Verified Git endpoint: https://gitee.com/henanzzjg/zzjg-isup.git (no refs returned in the preceding read-only check).
Existing GitHub origin is preserved; use a separate Gitee remote.

## Verified before submission

Final production-profile Maven verification: 802 tests, 0 failures, 0 errors; 2 POSIX-only cases skipped on Windows. The 4 actual FFmpeg remux integration cases passed. Frontend Node tests: 13 passed; both JavaScript syntax checks passed. Independent reviews closed the publication-race and documentation findings. Staged files and reachable history were checked for known real credentials and temporary artifacts. No ERP or production changes were made.
