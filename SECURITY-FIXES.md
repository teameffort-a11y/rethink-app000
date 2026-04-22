# Security fixes — pentest round 2

This branch addresses the deep-architecture pentest report (Round 2). It fixes
everything **except** the three issues that require upstream Go / `firestack`
changes (VULN-A, VULN-E, VULN-J). Those are tracked at the bottom of this file.

## Fixed in this PR

### VULN-B — ICMP firewall bypass (data exfil channel)
- New persistent setting `block_icmp` defaulting to **true** in
  `PersistentState.kt`.
- The Go bridge MUST consult this flag and drop ICMP frames at the TUN
  before they reach the gVisor stack. (Kotlin-side scaffolding only — the
  matching `firestack` change is required for the runtime block to take
  effect; see TODO note in `PersistentState.kt`.)

### VULN-C — UID reuse / tombstone rule inheritance
- `FirewallManager.purgeTombstoneIfDifferentPackage(uid, pkg)` deletes any
  tombstoned `AppInfo` whose `|uid|` collides with a freshly-installed UID
  but whose package name differs.
- Wired into `RefreshDatabase.maybeInsertApp()` so it runs on every new app
  install / UID assignment, before the new row is persisted.
- An `AUDIT (VULN-C)` log line is emitted whenever a stale tombstone is
  purged.

### VULN-D — `volatile HashSet` foreground tracking is not thread-safe
- `FirewallManager.GlobalVariable.foregroundUids` is now backed by
  `Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())`. All
  `add` / `remove` / `contains` calls are now safe under concurrent access
  on weakly-ordered ARM devices.

### VULN-F — `BYPASS_DNS_FIREWALL` silently disables logging and rules
- `FirewallManager.updateFirewallStatus()` now writes a high-visibility
  `AUDIT: BYPASS_DNS_FIREWALL granted to uid=… pkgs=[…]` warning every
  time the status is granted or changed, so an operator reviewing logs can
  detect a malicious or social-engineered grant even though the bypassed
  app itself produces no connection-log entries.

### VULN-G — Evil-twin SSID kills the WireGuard tunnel
- `BraveVPNService.pauseSsidEnabledWireGuardOnNoNw()` now requires the
  current Wi-Fi BSSID to match a TOFU-pinned BSSID for the SSID before it
  will pause any SSID-bound WireGuard tunnel.
- Pin map is stored in `SharedPreferences("ssid_bssid_pin")`.
- New persistent setting `require_bssid_match_ssid` (default **true**)
  lets advanced users opt out.
- Fails open and logs when the BSSID is unreadable
  (e.g. `02:00:00:00:00:00` MAC anonymization on Android 9+).

### VULN-H — PCAP file written to world-readable storage
- `AppConfig.setPcap()` now refuses any `EXTERNAL_FILE` path that is not
  inside `context.filesDir` or one of `context.getExternalFilesDirs(null)`.
- Path is canonicalized first so symlinks / `..` cannot defeat the check.
- Rejected paths fall back to `PcapMode.NONE` instead of leaving a
  half-configured "write-to-file" state with an empty path.

### VULN-I — WireGuard private keys at rest
- WireGuard configurations are already written through
  `EncryptedFileManager` (Android Keystore-backed `EncryptedFile` /
  `MasterKey`); only the file path is stored in SQLite, not the key
  material itself. **No code change required** — added a SECURITY note in
  this document so future contributors know not to add a plaintext
  fallback.

### VULN-K — DNS cache TTL / size bounds
- New persistent settings in `PersistentState.kt`:
  - `max_dns_cache_ttl_sec` — default **3600**
  - `dns_cache_max_entries` — default **10000**
- Like VULN-B, the matching enforcement lives in the `firestack` DNS
  cache. Kotlin-side defaults + plumbing are in place; the Go side must
  clamp accepted TTLs and evict on size overflow.

## Not fixed here (need upstream Go work in `firestack`)

- **VULN-A** — Go runtime panic in `gVisor` `dispatchLoop` kills
  `BraveVPNService` and leaks traffic. Requires: wrap goroutines in
  `recover()`, enforce Android `VPN_LOCKDOWN`, packet fuzzing on the
  gVisor input path.
- **VULN-E** — Double-free / UAF at tunnel teardown across the gomobile
  JNI boundary. Requires: audit `tunnel.Tunnel.disconnect` lock order and
  the gomobile reference-count race; safer teardown sequencing.
- **VULN-J** — gVisor IP fragment reassembly bugs when `useMaxMtu` is
  enabled. Requires: rebase pinned `firestack` / gVisor to a release that
  carries the relevant fragment-reassembly fixes.

## Verifying the fixes

Audit log lines added by this PR:

```
AUDIT (VULN-C): purging tombstoned rules for <pkg> (uid=<u>) because UID … is being reused by <pkg2>
AUDIT: BYPASS_DNS_FIREWALL granted to uid=<u> pkgs=[…]
AUDIT (VULN-G): refusing SSID-based wg pause; BSSID for '<ssid>' did not match the trusted (TOFU) BSSID
TOFU: pinned BSSID <bssid> for SSID '<ssid>'
Refusing pcap path outside app-private storage: <path>
```

Each fix is also commented inline with a `SECURITY (VULN-x)` marker so it can
be located via `grep -rn "SECURITY (VULN-"`.
