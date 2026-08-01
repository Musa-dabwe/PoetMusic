package com.musa.poetmusic.data

/**
 * The library scanner, as the Settings scan card sees it
 * (docs/desktop-app-plan.md §2.1).
 *
 * Android walks SAF document trees; the desktop walks `java.nio` paths. Both
 * report progress into [isScanning] / [progressText], which the card polls
 * every 2 s while a scan is running.
 */
interface ScanPort {

    val isScanning: Boolean
    val progressText: String

    fun autoScanEnabled(): Boolean
    fun setAutoScan(on: Boolean)

    fun intervalHours(): Int
    fun setIntervalHours(hours: Int)

    fun startScan()

    companion object {
        const val KEY_LAST_SCAN_AT = "last_scan_at"
        const val KEY_AUTO = "scan_auto"
        const val KEY_INTERVAL_H = "scan_interval_h"
        const val DEFAULT_INTERVAL_H = 12

        /** Rescan-when-older-than choices offered by the scan card. */
        val INTERVAL_CHOICES = listOf(6, 12, 24)

        /** Extensions treated as audio when a file carries no usable mime type. */
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "mp4")
    }
}
