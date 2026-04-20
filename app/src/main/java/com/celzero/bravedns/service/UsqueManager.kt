package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_PROXY
import android.content.Context
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UsqueManager {
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 40000
    private const val BINARY_NAME = "libusque.so"
    private var process: Process? = null

    private fun getBinary(ctx: Context): File {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val bin = File(nativeDir, BINARY_NAME)
        Log.d("WARP_DEBUG", "getBinary: path=${bin.absolutePath}")
        Log.d("WARP_DEBUG", "getBinary: exists=${bin.exists()}")
        Log.d("WARP_DEBUG", "getBinary: canExec=${bin.canExecute()}")
        Log.d("WARP_DEBUG", "getBinary: size=${bin.length()}")
        return bin
    }

    fun isRegistered(ctx: Context): Boolean {
        val f = File(ctx.filesDir, "config.json")
        Log.d("WARP_DEBUG", "isRegistered: path=${f.absolutePath} exists=${f.exists()} size=${f.length()}")
        return f.exists() && f.length() > 0L
    }


private fun registerWarp() {
    val progressDialog = ProgressDialog(this)
    progressDialog.setTitle("Registering WARP...")
    progressDialog.setMessage("Please wait...")
    progressDialog.setCancelable(false)
    progressDialog.show()
    io {
        val registered = UsqueManager.registerWithWarp(this@ProxySettingsActivity)
        val debugLog = UsqueManager.readDebugLog(this@ProxySettingsActivity)
        uiCtx {
            progressDialog.dismiss()
            // ── always show debug log so you can see it on LambdaTest ──
            MaterialAlertDialogBuilder(this@ProxySettingsActivity, R.style.App_Dialog_NoDim)
                .setTitle(if (registered) "✅ WARP Registered" else "❌ Registration Failed")
                .setMessage(debugLog)
                .setPositiveButton("OK") { d, _ ->
                    d.dismiss()
                    if (registered) persistentState.usqueEnabled = true
                }
                .show()
        }
    }
}

private fun showWarpRegistrationDialog() {
    MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        .setTitle(R.string.warp_register_button)
        .setMessage("Register device with Cloudflare WARP?")
        .setPositiveButton("Register") { dialog, _ -> dialog.dismiss(); registerWarp() }
        .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
        .setCancelable(true)
        .create().show()
}




    

    suspend fun startSocksProxy(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        Log.d("WARP_DEBUG", "startSocksProxy: >>>ENTRY<<<")
        stopSocksProxy()
        try {
            val bin = getBinary(ctx)

            if (!bin.exists() || !bin.canExecute()) {
                Log.d("WARP_DEBUG", "startSocksProxy: binary not ready exists=${bin.exists()} canExec=${bin.canExecute()}")
                return@withContext false
            }

            val cmd = listOf(
                bin.absolutePath, "socks",
                "-b", SOCKS_HOST,
                "-p", SOCKS_PORT.toString()
            )
            Log.d("WARP_DEBUG", "startSocksProxy: cmd=${cmd.joinToString(" ")}")

            process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            Thread.sleep(800)
            val alive = process?.isAlive == true
            Log.d("WARP_DEBUG", "startSocksProxy: alive=$alive")
            alive

        } catch (e: Exception) {
            Log.e("WARP_DEBUG", "startSocksProxy: EXCEPTION ${e.message}", e)
            FirebaseCrashlytics.getInstance().recordException(e)
            false
        }
    }

    fun stopSocksProxy() {
        Log.d("WARP_DEBUG", "stopSocksProxy: called isAlive=${process?.isAlive}")
        process?.destroy()
        process = null
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
