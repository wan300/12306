package com.railhelper.ticket

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object PythonBridge {
    private var module: PyObject? = null

    @Synchronized
    fun initialize(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }

        copyStationAsset(context.applicationContext)
        if (module == null) {
            module = Python.getInstance().getModule("mobile_bridge")
        }
        module!!.callAttr("initialize", context.filesDir.absolutePath)
    }

    suspend fun call(name: String, vararg args: Any): JSONObject = withContext(Dispatchers.IO) {
        val target = module ?: error("PythonBridge is not initialized")
        val result = target.callAttr(name, *args).toString()
        JSONObject(result)
    }

    private fun copyStationAsset(context: Context) {
        val targetDir = File(context.filesDir, "assets")
        val target = File(targetDir, "station_name.js")
        if (target.exists() && target.length() > 0) return

        targetDir.mkdirs()
        context.assets.open("station_name.js").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }
}

fun JSONObject.success(): Boolean = optBoolean("success", false)

fun JSONObject.message(): String = optString("message", "")

fun JSONObject.dataObject(): JSONObject? = optJSONObject("data")
