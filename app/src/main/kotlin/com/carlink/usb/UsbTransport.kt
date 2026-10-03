package com.carlink.usb

/** Transport abstraction shared by the local USB implementation and the remote helper. */
interface UsbTransport {
    val isOpened: Boolean

    fun write(data: ByteArray, timeout: Int = 1000): Int

    fun startReadingLoop(
        callback: ReadingLoopCallback,
        timeout: Int = 30000,
        videoProcessor: VideoDataProcessor? = null,
    )

    fun stopReadingLoop()

    fun close()

    interface VideoDataProcessor {
        fun processVideoDirect(data: ByteArray, dataLength: Int, sourcePtsMs: Int)
    }

    interface ReadingLoopCallback {
        fun onMessage(type: Int, data: ByteArray?, dataLength: Int)
        fun onError(error: String)
    }
}
