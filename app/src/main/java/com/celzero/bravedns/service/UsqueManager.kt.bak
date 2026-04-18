/*
 * File: app/src/main/java/com/celzero/bravedns/service/UsqueManager.kt
 *
 * Manages the usque binary as a local SOCKS5 proxy (127.0.0.1:1080).
 *
 * WHY SOCKS5 MODE:
 *   usque's nativetun mode requires root + Linux kernel TUN — it explicitly does
 *   NOT support Android without root (see usque Known Issues).
 *   usque socks / http-proxy are cross-platform, require no root, no TUN fd passing.
 *   RethinkDNS already has built-in SOCKS5 proxy support, so we just need to
 *   start usque as a local proxy and point the app at it.
 *
 * USAGE FLOW:
 *   1. registerWithWarp()   — one-time: runs `usque register -c <config>`
 *   2. startSocksProxy()    — starts `usque socks -b 127.0.0.1 -p 1080`
 *   3. Caller configures RethinkDNS SOCKS5 proxy → 127.0.0.1:1080
 *   4. stopSocksProxy()     — kills the process on VPN stop
 */

package com.celzero.bravedns.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object UsqueManager {

    private const val TAG         = "UsqueManager"
    private const val CONFIG_DIR  = "usque"
    private const val CONFIG_FILE = "config.json"

    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 1080

    @Volatile private var socksProcess: Process? = null

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    /**
     * Run `usque register -c <config_path>` once to create credentials.
     * Call from a background coroutine. Returns true on success.
     */
    suspend fun registerWithWarp(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val bin = extractBinary(context) ?: return@withContext false
            val configFile = ensureConfigFile(context)

            val cmd = listOf(bin.absolutePath, "register", "-c", configFile.absolutePath)
            Log.i(TAG, "register: ${cmd.joinToString(" ")}")

            val proc   = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit   = proc.waitFor()

            Log.i(TAG, "register output:\n$output")
            Log.i(TAG, "register exit=$exit configExists=${configFile.exists()} size=${configFile.length()}")

            exit == 0 && configFile.exists() && configFile.length() > 0L
        } catch (e: Exception) {
            Log.e(TAG, "registerWithWarp failed: ${e.message}", e)
            false
        }
    }

    /**
     * Returns true if config.json already exists and is non-empty.
     */
    fun isRegistered(context: Context): Boolean = try {
        val f = File(context.filesDir, "$CONFIG_DIR/$CONFIG_FILE")
        f.exists() && f.length() > 0L
    } catch (_: Exception) { false }

    // ------------------------------------------------------------------
    // SOCKS5 proxy lifecycle
    // ------------------------------------------------------------------

    /**
     * Launch `usque socks -b 127.0.0.1 -p 1080` in the background.
     * Call from a coroutine. Returns true if the process started.
     *
     * After calling this, configure RethinkDNS's SOCKS5 proxy to
     * point at SOCKS_HOST:SOCKS_PORT.
     */
    suspend fun startSocksProxy(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (socksProcess?.isAlive == true) {
            Log.i(TAG, "SOCKS proxy already running")
            return@withContext true
        }

        try {
            val bin        = extractBinary(context) ?: return@withContext false
            val configFile = ensureConfigFile(context)

            if (!configFile.exists() || configFile.length() == 0L) {
                Log.e(TAG, "Not registered — call registerWithWarp() first")
                return@withContext false
            }

            val cmd = listOf(
                bin.absolutePath,
                "socks",
                "-b", SOCKS_HOST,
                "-p", SOCKS_PORT.toString(),
                "-c", configFile.absolutePath
            )
            Log.i(TAG, "startSocksProxy: ${cmd.joinToString(" ")}")

            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            socksProcess = proc

            // Stream usque output to logcat (daemon thread, won't block shutdown)
            Thread {
                proc.inputStream.bufferedReader().forEachLine { Log.d(TAG, "[usque] $it") }
                val exit = runCatching { proc.exitValue() }.getOrDefault(-1)
                Log.w(TAG, "usque socks process ended exit=$exit")
            }.also { it.isDaemon = true }.start()

            // Give it 500 ms to bind the port before we declare success
            Thread.sleep(500)

            if (!proc.isAlive) {
                Log.e(TAG, "usque socks died immediately — check binary / config")
                socksProcess = null
                return@withContext false
            }

            Log.i(TAG, "SOCKS5 proxy running on $SOCKS_HOST:$SOCKS_PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startSocksProxy failed: ${e.message}", e)
            socksProcess = null
            false
        }
    }

    /**
     * Kill the usque socks process.
     */
    fun stopSocksProxy() {
        socksProcess?.let { proc ->
            runCatching { proc.destroy(); proc.waitFor() }
            runCatching { proc.destroyForcibly() }
        }
        socksProcess = null
        Log.i(TAG, "SOCKS proxy stopped")
    }

    /** True while the usque process is alive. */
    fun isRunning(): Boolean = socksProcess?.isAlive == true

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private fun ensureConfigFile(context: Context): File {
        val dir = File(context.filesDir, CONFIG_DIR).also { it.mkdirs() }
        return File(dir, CONFIG_FILE)
    }

    /**
     * Copy the usque binary out of assets on first run and mark executable.
     * Expected asset names: usque-rs-arm64  /  usque-rs-arm32
     */
    private fun extractBinary(context: Context): File? = try {
        val assetName  = "usque-rs-${detectArch()}"
        val outputFile = File(context.filesDir, assetName)

        if (!outputFile.exists() || outputFile.length() == 0L) {
            context.assets.open(assetName).use { inp ->
                outputFile.outputStream().use { out -> inp.copyTo(out) }
            }
            Log.i(TAG, "Extracted $assetName → ${outputFile.absolutePath}")
        }

        outputFile.setExecutable(true, false)
        outputFile.takeIf { it.exists() }
    } catch (e: Exception) {
        Log.e(TAG, "extractBinary failed: ${e.message}", e)
        null
    }

    /**
     * Build.SUPPORTED_ABIS is the reliable way to detect ABI on Android.
     */
    private fun detectArch(): String {
        val abi = runCatching {
            android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        }.getOrDefault("")
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> "arm64"
            abi.startsWith("armeabi")                        -> "arm32"
            abi.contains("x86_64")                          -> "x86_64"
            else                                             -> "arm64"
        }
    }
}
