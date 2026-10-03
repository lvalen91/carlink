package com.carlink.usbbridge

object UsbBridgeConstants {
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTING = 1
    const val STATE_CONNECTED = 2
    const val STATE_ERROR = 3

    const val MAX_MESSAGE_SIZE = 2 * 1024 * 1024
    const val CHUNK_SIZE = 64 * 1024
    const val BRIDGE_SERVICE_CLASS = "com.carlink.usbhelper.UsbBridgeService"
    const val BRIDGE_PACKAGE = "android.car.usb.handler"
}
