package com.newash.videocast

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                STREAMING_CHANNEL_ID,
                getString(R.string.streaming_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        // Personal app debugged remotely: persist crashes so the next launch can
        // show what happened instead of a bare "the app closed".
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { crashFile(this).writeText(e.stackTraceToString()) }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        const val STREAMING_CHANNEL_ID = "streaming"

        private fun crashFile(context: Context) = File(context.filesDir, "last-crash.txt")

        /** Returns and clears the crash saved by a previous run, if any. */
        fun consumeLastCrash(context: Context): String? = crashFile(context)
            .takeIf(File::exists)
            ?.let { file -> file.readText().also { file.delete() } }
            ?.takeIf(String::isNotBlank)
    }
}
