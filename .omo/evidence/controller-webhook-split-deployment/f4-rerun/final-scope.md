# Final corrected-allowlist recheck

This post-evidence check combined NUL-safe `git diff --name-only -z "$BASE"`
and `git ls-files --others --exclude-standard -z`, then rechecked the six
endpoint-toggle preserved-unit hashes against Task 1's SHA-256 baseline.

```text
BASE=c7dc8e231074793ec350195716b8606b6bb4ae7d
FINAL_TRACKED_CHANGED=32
FINAL_UNTRACKED=1365
FINAL_COMBINED=1397
FINAL_ALLOWLIST_VIOLATIONS=0
FINAL_PRESERVED_HASH_FAILURES=0
FINAL_SCOPE_RECHECK=PASS
```

The additional untracked paths are existing permitted workflow/evidence state
plus this F4 evidence directory; no product or preserved-unit path falls
outside the corrected allowlist.
