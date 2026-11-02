package com.estudioeq.app

import android.content.Context
import java.io.File

object NativeStore {
    private const val FILE_NAME = "estudio_master_hires_data.json"
    private const val TMP_NAME = "estudio_master_hires_data.json.tmp"
    private val lock = Any()

    fun save(context: Context, json: String): Boolean {
        synchronized(lock) {
            return try {
                val dir = context.filesDir
                val tmp = File(dir, TMP_NAME)
                val dst = File(dir, FILE_NAME)
                tmp.writeText(json, Charsets.UTF_8)
                if (!tmp.renameTo(dst)) {
                    dst.writeText(json, Charsets.UTF_8)
                    tmp.delete()
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun load(context: Context): String {
        synchronized(lock) {
            return try {
                val dst = File(context.filesDir, FILE_NAME)
                if (dst.exists() && dst.isFile) {
                    dst.readText(Charsets.UTF_8)
                } else {
                    ""
                }
            } catch (_: Exception) {
                ""
            }
        }
    }
}
