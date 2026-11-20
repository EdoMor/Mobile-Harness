# Fork integration notes

This branch combines upstream [techjarves/Mobile-Harness](https://github.com/techjarves/Mobile-Harness)
with the best work from its forks. Last refreshed 2026-11-19, when upstream `main` was at `367b207`.

## What is merged

| Source | What it adds | How |
|---|---|---|
| `hunterhn/main` | Display and text scaling, landscape, terminal layout and PTY output fixes, DeepSeek Harness (DSH) auto-retry and reconnect, OpenCode Go provider | Already this fork's `main` |
| `hunterhn/feat/opencode-go-reasoning-effort` | Reasoning-effort selector for OpenCode Go, DeepSeek Harness and Claude Code | Merge |
| `techjarves/main` | Sync with upstream (FUNDING.yml) | Merge |
| `EdouardLauret/main` | `.github/workflows/build-apk.yml` (unit tests and an online debug APK on every push); Claude provider-error detector only matches API error events | Merge |
| techjarves PR #106 | Downloaded runtime bundles survive Android cache eviction | Merge |
| karinefaraujo05 `196e9156c5` | **Security:** the OpenRouter routing gateway on 127.0.0.1 now requires the session key, because any app on the phone can reach a loopback port. Provider calls no longer follow redirects, so the key can't be sent on to another host. | Cherry-pick |
| karinefaraujo05 `e86a89dec9` | **Security:** the cleartext (`http://`) policy rejects packed numeric IPs and local-looking names that resolve to public addresses | Cherry-pick |
| 550ff `fe4666246b`, `340250a451` | Puts `gh` on the agents' PATH and runs `gh auth setup-git`, so agents can push to GitHub after you connect it | Cherry-pick |
| Gstarinfo (by iamaby-0) `d253017971` | Checkpoint diffs: fixes the inverted line order, CRLF handling and identical files | Cherry-pick |

Behaviour change to know about: because of `196e9156c5`, a provider endpoint that answers with an HTTP redirect now fails instead of following it. Use the provider's final URL.

## Added in this fork

- **Claude auto-resume.** When a Claude Code run reaches its turn limit or hits a temporary network or provider error, the app reopens the same conversation with `--resume` and continues. Credential, quota and rate-limit errors still stop the task.
- **Wireless ADB** (Settings → Wireless ADB). The app installs a static ARM64 `adb` 35.0.2 into the Linux environment (from [lzhiyong/android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools), pinned by SHA-256). It then pairs and connects that `adb` to the phone's own Wireless debugging (Android 11+, Wi‑Fi). You type the pairing code into a notification, so Android's pairing dialog stays open. Ports are found over mDNS. Agents get `adb` only after you turn on **Let agents use adb**. They run as the Android shell user, and are told to ask before risky changes.
- **Screen off for long adb runs.** A button beside Stop (and on the running-task notification) powers the display panel off over ADB, while the phone stays awake and unlocked. When the run ends, the screen wakes to black and shows a dot pulse twice, twice more a minute later, then the phone locks. Tapping the screen stops the sequence.

## Considered and left out

The forks were found by scanning all ~450 GitHub forks, not only the ones with pull requests.

- **Worth porting by hand:** karinefaraujo05 `4e5b75823c` and `972b721591`: thread-safe queues, a ping stuck in PINGING, a UTF-8 character split across reads, a lost update in the file list, and retry after Stop. Each conflicts with hunterhn's retry and PTY code in `MainViewModel.kt`.
- **Needs a decision first:** ToL-src `main` adds git status, commit and push on the Changes tab, with tests. It conflicts in `ClaudeRuntimeBridge.kt`, and its tests disagree with EdouardLauret's error-detector fix about whether HTTP 429 and 401 retries are fatal.
- **Optional:**
  - ajaysharma-sahionext `feature/cursor-cli`: a Cursor CLI agent. One small conflict in each of 3 files.
  - 550ff `eac464e3e7`: removes Claude's `--max-turns 25`. This is a policy choice.
  - Gstarinfo `d65042867c`: only accepts Antigravity sign-in URLs on `accounts.google.com`. It's unverified against agy's real output and could break sign-in.
- **Skipped:**
  - Forks that rename the app or applicationId, or repoint updates and signing: hamaz562006, iyuji9041-ops, LeonChenWenJia, SleepinDevil, dcenhance, and 550ff's redesign and updater branches.
  - Large rewrites with follow-up "fix compile" commits: Vempire-X, miscdevstuff, 550ff's redesign.
  - Forks 33 or more commits behind upstream: mahfujxamon, zahidoverflow, TheerasakPing, and others.

## Updating

```sh
git remote add upstream https://github.com/techjarves/Mobile-Harness
git remote add hunterhn https://github.com/hunterhn/Mobile-Harness
git remote add edouard  https://github.com/EdouardLauret/Mobile-Harness
git fetch --all
git merge upstream/main            # then any newer hunterhn or edouard branches
```

Every push runs **Build debug APK** under the Actions tab. The APK is attached to the run as an artifact.
