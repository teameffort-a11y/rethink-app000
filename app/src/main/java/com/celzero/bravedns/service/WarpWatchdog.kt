package com.celzero.bravedns.service

import Logger
import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WarpWatchdog
 *
 * Background health monitor for the WARP / usque SOCKS5 tunnel.
 *
 * Problem this solves:
 *   When the ISP silently kills the usque tunnel, the local usque process is
 *   often still alive and still listening on 127.0.0.1:40000, but no traffic
 *   actually reaches the internet through it. Apps that are configured to
 *   route via SOCKS5 then hang and the user perceives the internet as "cut".
 *
 * What it does:
 *   - Every CHECK_INTERVAL_MS, opens a real TCP connection through the SOCKS5
 *     at 127.0.0.1:40000 to a known reachable host (1.1.1.1:443).
 *   - If the connect fails FAILURE_THRESHOLD times in a row, it tells
 *     UsqueManager to stop and start the SOCKS5 proxy again. The local SOCKS5
 *     listener stays on the same host:port so no app reconfiguration is
 *     needed (the SOCKS5 entry in appConfig is left intact by design).
 *   - Uses exponential backoff on consecutive failed restarts, capped at
 *     RESTART_BACKOFF_MAX_MS, so it never hot-loops on a persistent outage.
 *
 * Lifecycle:
 *   - Call start(ctx) right after UsqueManager.startSocksProxy succeeds.
 *   - Call stop() when the user disables the WARP switch, when the VPN
 *     service is being destroyed, or before any deliberate restart you do
 *     yourself (e.g. SNI change).
 *
 * Thread-safety: start()/stop() are synchronized on the WarpWatchdog object.
 */
object WarpWatchdog {

    private const val TAG = "WARP_WATCHDOG"

    // How often we probe the tunnel.
    private const val CHECK_INTERVAL_MS = 15_000L

    // How long a single SOCKS5 connect attempt is allowed to take.
    private const val PROBE_TIMEOUT_MS = 5_000

    // Consecutive probe failures required before we restart usque.
    private const val FAILURE_THRESHOLD = 2

    // After a restart, how long to wait before resuming probes (lets the
    // tunnel come up cleanly).
    private const val POST_RESTART_GRACE_MS = 4_000L

    // Backoff between consecutive failed restart attempts.
    private const val RESTART_BACKOFF_INITIAL_MS = 5_000L
    private const val RESTART_BACKOFF_MAX_MS = 60_000L

    // End-to-end probe target. IP literal on purpose: avoids DNS dependency
    // so we are really testing the tunnel, not the device's resolver.
    private const val PROBE_HOST = "1.1.1.1"
    private const val PROBE_PORT = 443

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) {
            dlog(context, "start: already running, ignoring")
            return
        }
        val appCtx = context.applicationContext
        dlog(appCtx, "start: launching watchdog")
        job = scope.launch { run(appCtx) }
    }

    @Synchronized
    fun stop() {
        Log.d(TAG, "stop: cancelling watchdog (active=${job?.isActive})")
        job?.cancel()
        job = null
    }

    fun isRunning(): Boolean = job?.isActive == true

    // ── core loop ─────────────────────────────────────────────────────────

    private suspend fun run(ctx: Context) {
        var consecutiveProbeFailures = 0
        var restartBackoffMs = RESTART_BACKOFF_INITIAL_MS

        while (scope.isActive && job?.isActive == true) {
            try {
                delay(CHECK_INTERVAL_MS)

                // Quick early check: if usque process died entirely, restart
                // immediately without waiting for the failure threshold.
                val processAlive = UsqueManager.isRunning()
                if (!processAlive) {
                    dlog(ctx, "probe: usque process not alive, restarting immediately")
                    val ok = restartWarp(ctx)
                    if (ok) {
                        consecutiveProbeFailures = 0
                        restartBackoffMs = RESTART_BACKOFF_INITIAL_MS
                        delay(POST_RESTART_GRACE_MS)
                    } else {
                        dlog(ctx, "restart failed, backoff=${restartBackoffMs}ms")
                        delay(restartBackoffMs)
                        restartBackoffMs =
                            (restartBackoffMs * 2).coerceAtMost(RESTART_BACKOFF_MAX_MS)
                    }
                    continue
                }

                // End-to-end probe through the SOCKS5 tunnel.
                val healthy = probeSocks5(ctx)
                if (healthy) {
                    if (consecutiveProbeFailures > 0) {
                        dlog(ctx, "probe: recovered after $consecutiveProbeFailures failure(s)")
                    }
                    consecutiveProbeFailures = 0
                    restartBackoffMs = RESTART_BACKOFF_INITIAL_MS
                    continue
                }

                consecutiveProbeFailures += 1
                dlog(ctx, "probe: failure #$consecutiveProbeFailures")

                if (consecutiveProbeFailures >= FAILURE_THRESHOLD) {
                    dlog(ctx, "probe: threshold reached, restarting warp")
                    val ok = restartWarp(ctx)
                    if (ok) {
                        consecutiveProbeFailures = 0
                        restartBackoffMs = RESTART_BACKOFF_INITIAL_MS
                        delay(POST_RESTART_GRACE_MS)
                    } else {
                        dlog(ctx, "restart failed, backoff=${restartBackoffMs}ms")
                        delay(restartBackoffMs)
                        restartBackoffMs =
                            (restartBackoffMs * 2).coerceAtMost(RESTART_BACKOFF_MAX_MS)
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                dlog(ctx, "loop: cancelled")
                throw ce
            } catch (t: Throwable) {
                // Never let an unexpected exception kill the watchdog.
                dlog(ctx, "loop: unexpected error: ${t.javaClass.simpleName}: ${t.message}")
                try { Logger.w(Logger.LOG_TAG_PROXY, "WarpWatchdog loop error: ${t.message}") } catch (_: Throwable) {}
            }
        }
        dlog(ctx, "loop: exiting")
    }

    // ── probe ─────────────────────────────────────────────────────────────

    /**
     * Opens a TCP connection to PROBE_HOST:PROBE_PORT through the local
     * SOCKS5 at UsqueManager.SOCKS_HOST:UsqueManager.SOCKS_PORT.
     * Returns true iff the connect succeeds within PROBE_TIMEOUT_MS.
     *
     * This validates that the tunnel is actually carrying traffic, not just
     * that the local SOCKS5 listener is up.
     */
    private suspend fun probeSocks5(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress(UsqueManager.SOCKS_HOST, UsqueManager.SOCKS_PORT)
        )
        var socket: Socket? = null
        try {
            socket = Socket(proxy)
            // Use createUnresolved so the SOCKS server resolves the name
            // (irrelevant here since we use a literal IP, but it's the
            // standard correct pattern).
            val target = InetSocketAddress.createUnresolved(PROBE_HOST, PROBE_PORT)
            socket.connect(target, PROBE_TIMEOUT_MS)
            true
        } catch (t: Throwable) {
            dlog(ctx, "probe: connect failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            try { socket?.close() } catch (_: Throwable) {}
        }
    }

    // ── restart ───────────────────────────────────────────────────────────

    /**
     * Stops the current usque SOCKS5 process and starts a fresh one. The
     * SOCKS5 listener comes back on the same host:port, so the SOCKS5 entry
     * in appConfig does NOT need to be touched and apps keep using it
     * without reconfiguration.
     */
    private suspend fun restartWarp(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            UsqueManager.stopSocksProxy()
            // Brief pause so the OS releases port 40000 before we rebind.
            delay(500)
            val started = UsqueManager.startSocksProxy(ctx)
            dlog(ctx, "restart: started=$started")
            started
        } catch (t: Throwable) {
            dlog(ctx, "restart: error: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    // ── debug log (mirrors UsqueManager's pattern) ────────────────────────

    private fun dlog(ctx: Context, msg: String) {
        Log.d(TAG, msg)
        try {
            File(ctx.filesDir, "warp_debug.txt")
                .appendText("${System.currentTimeMillis()} [watchdog] $msg\n")
        } catch (_: Exception) {}
    }
}
