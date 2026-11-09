# Isolated OpenCode2 worker

The Windows Agent invokes one fixed root-owned WSL entry point. The CLI itself
runs as a separate numeric host UID per scope, inside bubblewrap user, mount,
PID, IPC, UTS and network namespaces with no effective capabilities. This is
filesystem and kernel isolation, rather than a different HOME alone.

Current installation: WSL distribution `Ubuntu` (Ubuntu 24.04), official Linux
OpenCode v2.0.32 (`@opencode/cli-linux-x64@2.0.32`). The official installer at
<https://opencode.ai/v2/install> resolves this npm package. `install.py` pins its
SHA512 integrity and the extracted binary's SHA256. No mirror or Windows shim
is used, and no shell profile or system proxy is modified.

Install `bubblewrap` and `socat` through Ubuntu apt, then run as WSL root:

```powershell
wsl -d Ubuntu -u root -- python3 /mnt/d/AI/VibeLink/.vibelink-worktrees/history-clean/vibelink/install/opencode2-worker/install.py
```

Only `/opt/vibelink-worker` contains executable worker code. Persistent user
state lives in `/var/lib/vibelink-worker/scopes/<sha256-of-scope>/home` and
`workspace`. Scope UID assignments are root-owned and persistent. Job receipts
are root-only under `/run/vibelink-worker/jobs`. They contain process identity
and completion state, never prompts or grants.

## Agent protocol

The production argv is constant:

```text
wsl.exe -d Ubuntu -u root -- /opt/vibelink-worker/driver.py
```

Send one UTF-8 JSON object through stdin, then close stdin. Do not put grants,
URLs or user input in the WSL argv. Production Rust derives scope from the
authenticated binding, not a phone-supplied path or tenant name.

```json
{
  "version": 1,
  "scope": "beta_ACTOR_MODEL_SCOPE",
  "jobId": "7d241d18-a224-498b-8314-a07de371e2db",
  "model": "auto/deepseek-v4.1-flash",
  "gateway": "http://127.0.0.1:18998/api/llm/v1",
  "token": "REDACTED_MODEL_ONLY_CAPABILITY",
  "session": null,
  "prompt": "Reply only OK.",
  "readOnly": true,
  "files": []
}
```

The scope is 1–128 ASCII letters, digits, `_` or `-`. `jobId` is a fresh UUID
per turn and is persisted by the Agent before launching. Resume supplies the
official `ses_*` ID with the same scope and engine. Run stdout is the CLI's
unmodified NDJSON; the Agent owns native-session binding. No shared OpenCode
service is used: the command is `run --standalone --format json --model ...`.

The sandbox cwd is always `/workspace`. No arbitrary host cwd, Windows drive,
DrvFS path or attachment path is accepted. Files must be inline objects with
exactly `filename`, `mime` and `base64`: at most eight inputs, 12 MiB decoded
in total. Names cannot contain path separators, traversal or control characters;
duplicate names are rejected. The driver writes only these decoded bytes to
the current job's inputs directory, mounts it read-only as `/inputs`, and
appends the quoted paths to the CLI prompt. It never opens a caller-supplied
host path. Inputs are removed after the job. Read-only roles also mount their
workspace read-only; CLI state remains writable so sessions can be persisted.

## One metered model route

The host proxy accepts only the two fixed Windows Hub origins on ports 18995
and 18998 with `/api/llm/v1`. The WSL NAT gateway comes from `/proc/net/route`;
callers cannot select another host or URL. A temporary root-private Unix
socket is mounted as `/run/model.sock`. Inside the private network namespace,
a loopback listener on 11434 forwards to that socket. There is no external
network interface or route.

Only GET `/v1/models`, POST `/v1/responses` and POST `/v1/chat/completions` are
forwarded, and POST model must be exactly `auto/deepseek-v4.1-flash`. Redirects,
other models and admin paths are refused. Caller headers are discarded. The
real model-only capability stays in the outer root proxy's memory, which
replaces upstream Authorization. The CLI gets a nonsecret placeholder key.
No host OpenAI, Claude or other API auth is copied or mounted.
The root proxy bounds concurrent connections at 16, limits model bodies to
8 MiB and applies socket timeouts. The managed V2 permissions use ordered
rules: read-only roles deny edit, shell, subagent and execute while retaining
read tools. Provider policy denies all providers except `vibelink`; both the
main and small model use the metered route. Project configs and Claude import
are disabled in the clean environment.

## Cancellation and recovery

Use the same fixed entry point and send:

```json
{"version":1,"operation":"status","scope":"beta_ACTOR_MODEL_SCOPE","jobId":"7d241d18-a224-498b-8314-a07de371e2db"}
```

The response is `{known,running,quiescent}`. Missing records return
`known:false,quiescent:false`. A vanished PID without a durable completion
receipt remains uncertain. A matching PID/start-time is running. Only a
driver-written finished receipt after waiting for bubblewrap proves
quiescence. Do not release a Work lease merely because Windows `wsl.exe`
exited, or because the Agent restarted and its child map is empty.

`operation:"cancel"` records a scope-bound cancellation marker, including
before spawn. It rechecks the registered process identity and signals only
the recorded job process group. The driver waits for the namespaced process
to exit before recording completion. Poll status afterwards. Reusing a job ID
is rejected, and no automatic model retry is performed.

## Verification

```powershell
wsl -d Ubuntu -u root -- python3 -m unittest discover -s /mnt/d/AI/VibeLink/.vibelink-worktrees/history-clean/vibelink/install/opencode2-worker -p test_worker.py -v
```

Tests cover gateway/model allowlists, header replacement, admin-route denial,
host-path/inline-input bounds, missing-job uncertainty, required namespace flags and
actual kernel containment. The installed worker probe runs the official CLI's
version command inside the sandbox, verifies workspace rw/ro behavior, hidden
host/auth paths, zero capabilities, inaccessible direct internet and the sole
Hub route, read-only input mounts and process cancellation. An actual CLI run
against a mock model verifies its read-only tool catalog. A fake grant produces
401 without model inference. Real
create/resume and per-user token accounting are verified separately through
the Agent with a disposable model-only grant.

For that live smoke only, a root-owned temporary driver copy may append one
exact temporary Hub port. The test-only Rust entry override is not compiled
into production. Never add a request flag that bypasses the production
allowlist. Remove the exact temporary test directory after the smoke, after
checking that its resolved path remains beneath `/opt/vibelink-worker/tests`.
