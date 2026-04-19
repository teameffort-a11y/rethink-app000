package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_PROXY
import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UsqueManager {
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 40000
    private const val BINARY_NAME = "usque-rs-arm64"
    private var process: Process? = null

    fun isRegistered(ctx: Context): Boolean {
        return File(ctx.filesDir, "config.json").exists()
    }

suspend fun registerWithWarp(context: Context): Boolean = try {
    Log.d(TAG, "Starting WARP registration")
    
    val bin = extractBinary(context)
    Log.d(TAG, "Binary extracted: ${bin?.absolutePath}")
    if (bin == null) {
        Log.e(TAG, "Binary extraction failed")
        return false
    }
    
    val configDir = File(context.filesDir, CONFIG_DIR).also { it.mkdirs() }
    val configFile = File(configDir, CONFIG_FILE)
    Log.d(TAG, "Config file path: ${configFile.absolutePath}")

    val cmd = listOf(bin.absolutePath, "register", "--accept-tos", "-c", configFile.absolutePath)
    Log.i(TAG, "register command: ${cmd.joinToString(" ")}")

    val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val exit = proc.waitFor()
    Log.i(TAG, "register exit code: $exit")
    Log.i(TAG, "config file exists: ${configFile.exists()}")
    if (configFile.exists()) {
        Log.i(TAG, "config file length: ${configFile.length()}")
    }

    val result = exit == 0 && configFile.exists() && configFile.length() > 0L
    Log.i(TAG, "Registration result: $result")
    result
} catch (e: Exception) {
    Log.e(TAG, "registerWithWarp error: ${e.message}", e)
    false
}

/** suspend fun registerWithWarp(context: Context): Boolean = withContext(Dispatchers.IO) {
    Logger.i(LOG_TAG_PROXY, "registerWithWarp CALLED")  // add this as first line
    try {
        val bin = copyBinary(context)
        Logger.i(LOG_TAG_PROXY, "usque register: path=${bin.absolutePath} canExec=${bin.canExecute()}")

        val configFile = File(context.filesDir, "config.json")
        val cmd = listOf(bin.absolutePath, "register", "-c", configFile.absolutePath)
        Logger.i(LOG_TAG_PROXY, "usque register cmd: ${cmd.joinToString(" ")}")

        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        // Answer "y" to the Terms of Service prompt
        proc.outputStream.bufferedWriter().use { it.write("y\n"); it.flush() }

        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()

        Logger.i(LOG_TAG_PROXY, "usque register exit=$exit output=$output")
        Logger.i(LOG_TAG_PROXY, "config exists=${configFile.exists()} size=${configFile.length()}")

        exit == 0 && configFile.exists() && configFile.length() > 0L
    } catch (e: Exception) {
        Logger.e(LOG_TAG_PROXY, "usque register failed: ${e.message}", e)
        false
    }
}. **/

    

    suspend fun startSocksProxy(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        stopSocksProxy()
        return@withContext try {
            val bin = copyBinary(ctx)
            process = ProcessBuilder(
                bin.absolutePath, "socks5",
                "--host", SOCKS_HOST,
                "--port", SOCKS_PORT.toString()
            ).redirectErrorStream(true).start()
            Thread.sleep(800)
            process?.isAlive == true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "usque start failed: ${e.message}", e)
            false
        }
    }

    fun stopSocksProxy() {
        process?.destroy()
        process = null
    }

    private fun copyBinary(ctx: Context): File {
        val out = File(ctx.filesDir, BINARY_NAME)
        if (!out.exists()) {
            ctx.assets.open(BINARY_NAME).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out.setExecutable(true)
        }
        return out
    }
}
