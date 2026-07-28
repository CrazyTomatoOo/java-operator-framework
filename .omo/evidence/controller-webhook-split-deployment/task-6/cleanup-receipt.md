# Task 6 cleanup receipt

- Both contract runs used the script-owned `mktemp` workspace and its `trap cleanup EXIT`
  path; rendered manifests and generated CAs were removed on exit.
- Manual parsed-render inspection streamed Helm output directly to Python and retained no
  render or certificate file.
- No background process was started. Retained files are limited to this Task 6 evidence
  directory and the appended plan notepad entry.
- The watched-namespace RED reproduction streamed its render directly into Python and
  retained no temporary manifest. The two correction contract runs used the same
  script-owned cleanup trap.
