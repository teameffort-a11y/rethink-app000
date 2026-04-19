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
    private const val BINARY_NAME = "usque-rs-arm32"
    private var process: Process? = null

    fun isRegistered(ctx: Context): Boolean {
        return File(ctx.filesDir, "warp_reg.json").exists()
    }

    suspend fun registerWithWarp(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val bin = copyBinary(ctx)
            val proc = ProcessBuilder(bin.absolutePath, "register")
                .redirectErrorStream(true)
                .start()
            proc.waitFor(30, TimeUnit.SECONDS) && proc.exitValue() == 0
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "usque register failed: ${e.message}", e)
            false
        }
    }

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
