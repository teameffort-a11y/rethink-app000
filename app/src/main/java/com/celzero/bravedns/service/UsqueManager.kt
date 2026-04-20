package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_PROXY
import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UsqueManager {
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 40000
    private const val BINARY_NAME = "usque-rs-arm64"
    private var process: Process? = null

    fun isRegistered(ctx: Context): Boolean {
        val f = File(ctx.filesDir, "config.json")
        Logger.i(LOG_TAG_PROXY, "isRegistered: ${f.absolutePath} exists=${f.exists()}")
        return f.exists()
    }

    suspend fun registerWithWarp(context: Context): Boolean = withContext(Dispatchers.IO) {
        Logger.i(LOG_TAG_PROXY, "registerWithWarp: CALLED")
        try {
            val bin = copyBinary(context)
            
android.util.Log.d("WARP_DEBUG", "bin=${bin.absolutePath}")
android.util.Log.d("WARP_DEBUG", "exists=${bin.exists()}")
android.util.Log.d("WARP_DEBUG", "canExec=${bin.canExecute()}")
android.util.Log.d("WARP_DEBUG", "size=${bin.length()}")

try {
    val test = Runtime.getRuntime().exec(bin.absolutePath)
    val exit = test.waitFor()
    val err = test.errorStream.bufferedReader().readText()
    android.util.Log.d("WARP_DEBUG", "test exit=$exit err=$err")
} catch (e: Exception) {
    android.util.Log.d("WARP_DEBUG", "EXEC EXCEPTION: ${e.message}")
}



            android.util.Log.d("WARP_DEBUG", "bin exists=${bin.exists()} canExec=${bin.canExecute()} size=${bin.length()}")
            
            
            
            val configFile = File(context.filesDir, "config.json")
            val cmd = listOf(bin.absolutePath, "register", "-c", configFile.absolutePath)
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            proc.outputStream.bufferedWriter().let { w -> w.write("y\n"); w.flush(); w.close() }
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            Logger.i(LOG_TAG_PROXY, "registerWithWarp: exit=$exit output=$output")
            val ok = exit == 0 && configFile.exists() && configFile.length() > 0L
            ok
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "registerWithWarp: EXCEPTION ${e.message}", e)
            FirebaseCrashlytics.getInstance().recordException(e)
            false
        }
    }

    suspend fun startSocksProxy(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        stopSocksProxy()
        try {
            val bin = copyBinary(ctx)
            process = ProcessBuilder(
                bin.absolutePath, "socks",
                "-b", SOCKS_HOST,
                "-p", SOCKS_PORT.toString()
            ).redirectErrorStream(true).start()
            Thread.sleep(800)
            process?.isAlive == true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "startSocksProxy: EXCEPTION ${e.message}", e)
            FirebaseCrashlytics.getInstance().recordException(e)
            false
        }
    }

    fun stopSocksProxy() {
        process?.destroy()
        process = null
    }

    fun isRunning(): Boolean = process?.isAlive == true

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
