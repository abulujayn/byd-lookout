package com.overdrive.app.util

import com.overdrive.app.camera.CameraProfiles

/**
 * Shared constants for the BYD Champ application.
 */
object Constants {
    
    // Daemon Ports
    const val TCP_PORT = 19876
    const val HTTP_PORT = 8080
    
    // Directories
    const val STREAM_DIR = "/data/local/tmp/cam_stream"
    const val APP_STREAM_DIR = "/storage/emulated/0/Android/data/com.overdrive.app/files/stream"
    const val DEFAULT_OUTPUT_DIR = "/sdcard/DCIM/BYDCam"
    const val LOG_DIR = "/data/local/tmp"
    
    // Camera Configuration
    val PANO_WIDTH = CameraProfiles.getLegacyDefault().panoWidth
    val PANO_HEIGHT = CameraProfiles.getLegacyDefault().panoHeight
    val VIEW_WIDTH = PANO_WIDTH / 4
    val VIEW_HEIGHT = PANO_HEIGHT
    const val FRAME_RATE = 25
    const val BITRATE = 4_000_000
    const val KEYFRAME_INTERVAL = 2
    const val SEGMENT_DURATION_MS = 2 * 60 * 1000L
    
    // Streaming Configuration
    const val STREAM_WIDTH = 640
    const val STREAM_HEIGHT = 480
    const val STREAM_FPS = 15
    const val STREAM_BITRATE = 500_000
    const val STREAM_JPEG_QUALITY = 40
    const val STREAM_INTERVAL_MS = 100L
    
    // VPS Configuration - removed for open source release
    
    // Timeouts
    const val DAEMON_START_TIMEOUT_MS = 10_000L
    const val DAEMON_STOP_TIMEOUT_MS = 5_000L
    const val CONNECTION_TIMEOUT_MS = 10_000L
    
    // Retry Configuration
    const val DEFAULT_RETRY_ATTEMPTS = 3
    const val DEFAULT_RETRY_DELAY_MS = 5_000L
    const val MAX_RETRY_BACKOFF_MS = 30_000L
}
