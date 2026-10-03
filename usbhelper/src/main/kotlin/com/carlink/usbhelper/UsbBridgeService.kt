package com.carlink.usbhelper

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.RemoteException
import com.carlink.usbbridge.IUsbBridge
import com.carlink.usbbridge.IUsbBridgeCallback
import com.carlink.usbbridge.UsbBridgeConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB owner for the GM fixed-handler workaround.
 *
 * The system grants this package's UID permission before launching the fixed-handler
 * activity. This service then proxies complete CPC200 messages to the Play application.
 */
class UsbBridgeService : Service() {
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val callbacks = ConcurrentHashMap.newKeySet<IUsbBridgeCallback>()
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    private var connection: UsbDeviceConnection? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var reader: Thread? = null
    private val pendingWrites = ConcurrentHashMap<Long, ChunkAccumulator>()

    private val binder = object : IUsbBridge.Stub() {
        override fun open(): Boolean = openDevice()

        override fun close() {
            closeDevice("closed by client")
        }

        override fun registerCallback(callback: IUsbBridgeCallback) {
            callbacks += callback
            emitState(UsbBridgeConstants.STATE_CONNECTED, "callback registered")
        }

        override fun unregisterCallback(callback: IUsbBridgeCallback) {
            callbacks -= callback
        }

        override fun sendChunk(
            transferId: Long,
            chunkIndex: Int,
            chunkCount: Int,
            totalLength: Int,
            data: ByteArray,
        ): Boolean {
            if (chunkCount <= 0 || chunkIndex !in 0 until chunkCount ||
                totalLength !in 0..UsbBridgeConstants.MAX_MESSAGE_SIZE ||
                data.size > UsbBridgeConstants.CHUNK_SIZE
            ) return false

            val accumulator = pendingWrites.getOrPut(transferId) {
                ChunkAccumulator(chunkCount, totalLength)
            }
            if (!accumulator.add(chunkIndex, data)) return false
            if (!accumulator.complete()) return true

            pendingWrites.remove(transferId)
            val message = accumulator.join() ?: return false
            val conn = connection ?: return false
            val endpoint = outEndpoint ?: return false
            return synchronized(writeLock) {
                conn.bulkTransfer(endpoint, message, message.size, WRITE_TIMEOUT) == message.size
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        emitState(UsbBridgeConstants.STATE_DISCONNECTED, "waiting for CPC200 adapter")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The fixed GM handler has already granted this UID access to the attached
        // device before starting the activity. Resolve the current adapter from
        // UsbManager so the same path also works when the Play app binds directly.
        openDevice()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        closeDevice("service destroyed")
        super.onDestroy()
    }

    private fun openDevice(requested: UsbDevice? = null): Boolean {
        if (running.get()) return true
        // USB_DEVICE_ATTACHED and the UsbManager device list are not updated
        // atomically on all GM builds. Give enumeration and the fixed-handler
        // permission grant a short window to settle before failing.
        var devices = usbManager.deviceList.values.toList()
        var device = requested ?: devices.firstOrNull(::isKnownDevice)
        for (attempt in 0 until 5) {
            if (device != null) break
            Thread.sleep(200)
            devices = usbManager.deviceList.values.toList()
            device = requested ?: devices.firstOrNull(::isKnownDevice)
        }
        if (device == null || !isKnownDevice(device)) {
            emitState(
                UsbBridgeConstants.STATE_ERROR,
                "CPC200 adapter not found; USB devices=${describeDevices(devices)}",
            )
            return false
        }
        if (!usbManager.hasPermission(device)) {
            emitState(UsbBridgeConstants.STATE_ERROR, "USB permission was not granted")
            return false
        }

        emitState(UsbBridgeConstants.STATE_CONNECTING, device.deviceName)
        val conn = usbManager.openDevice(device) ?: run {
            emitState(UsbBridgeConstants.STATE_ERROR, "UsbManager.openDevice failed")
            return false
        }
        val endpoints = findBulkEndpoints(device)
        if (endpoints == null || !conn.claimInterface(endpoints.first, true)) {
            conn.close()
            emitState(UsbBridgeConstants.STATE_ERROR, "No claimable bulk USB interface")
            return false
        }

        connection = conn
        inEndpoint = endpoints.second
        outEndpoint = endpoints.third
        running.set(true)
        emitState(UsbBridgeConstants.STATE_CONNECTED, "${device.vendorId}:${device.productId}")
        reader = Thread(::readLoop, "CL-USB-Helper").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun closeDevice(reason: String) {
        if (!running.getAndSet(false) && connection == null) return
        reader?.interrupt()
        reader = null
        connection?.close()
        connection = null
        inEndpoint = null
        outEndpoint = null
        pendingWrites.clear()
        emitState(UsbBridgeConstants.STATE_DISCONNECTED, reason)
    }

    private fun readLoop() {
        val header = ByteArray(HEADER_SIZE)
        try {
            while (running.get()) {
                if (!readExact(header, READ_TIMEOUT)) break
                val length = littleEndianInt(header, 4)
                val type = littleEndianInt(header, 8)
                if (length !in 0..UsbBridgeConstants.MAX_MESSAGE_SIZE) {
                    emitState(UsbBridgeConstants.STATE_ERROR, "invalid CPC200 payload length: $length")
                    break
                }
                val payload = ByteArray(length)
                if (length > 0 && !readExact(payload, READ_TIMEOUT)) break
                val pts = if (type == VIDEO_TYPE && payload.size >= 16) littleEndianInt(payload, 12) else 0
                sendInbound(type, pts, payload)
            }
        } catch (e: Exception) {
            if (running.get()) emitState(UsbBridgeConstants.STATE_ERROR, e.message ?: "USB read failed")
        } finally {
            if (running.get()) closeDevice("USB read loop stopped")
        }
    }

    private fun readExact(buffer: ByteArray, timeout: Int): Boolean {
        var offset = 0
        while (offset < buffer.size && running.get()) {
            val n = connection?.bulkTransfer(inEndpoint, buffer, offset, buffer.size - offset, timeout) ?: -1
            if (n <= 0) return false
            offset += n
        }
        return offset == buffer.size
    }

    private fun sendInbound(type: Int, pts: Int, payload: ByteArray) {
        val transferId = nextTransferId++
        val count = maxOf(1, (payload.size + UsbBridgeConstants.CHUNK_SIZE - 1) / UsbBridgeConstants.CHUNK_SIZE)
        payload.asList().chunked(UsbBridgeConstants.CHUNK_SIZE).forEachIndexed { index, bytes ->
            val chunk = bytes.toByteArray()
            callbacks.forEach { callback ->
                try {
                    callback.onInboundChunk(transferId, type, pts, index, count, payload.size, chunk)
                } catch (_: RemoteException) {
                    callbacks -= callback
                }
            }
        }
        if (payload.isEmpty()) {
            callbacks.forEach { callback ->
                try {
                    callback.onInboundChunk(transferId, type, pts, 0, 1, 0, ByteArray(0))
                } catch (_: RemoteException) {
                    callbacks -= callback
                }
            }
        }
    }

    private fun emitState(state: Int, detail: String) {
        callbacks.forEach { callback ->
            try {
                callback.onState(state, detail)
            } catch (_: RemoteException) {
                callbacks -= callback
            }
        }
    }

    private fun findBulkEndpoints(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
            }
            if (input != null && output != null) return Triple(iface, input, output)
        }
        return null
    }

    private fun isKnownDevice(device: UsbDevice): Boolean =
        (device.vendorId == 4884 && device.productId in setOf(5408, 5409)) ||
            (device.vendorId == 2276 && device.productId == 448)

    private fun describeDevices(devices: List<UsbDevice>): String =
        devices.joinToString(prefix = "[", postfix = "]") {
            "${it.deviceName}:0x${it.vendorId.toString(16)}:0x${it.productId.toString(16)}"
        }.ifEmpty { "none" }

    private class ChunkAccumulator(private val count: Int, private val totalLength: Int) {
        private val chunks = arrayOfNulls<ByteArray>(count)
        fun add(index: Int, data: ByteArray): Boolean {
            if (index !in chunks.indices || chunks[index] != null) return false
            chunks[index] = data.copyOf()
            return true
        }
        fun complete(): Boolean = chunks.all { it != null }
        fun join(): ByteArray? {
            if (!complete()) return null
            val result = ByteArray(totalLength)
            var offset = 0
            chunks.forEach { chunk ->
                val bytes = chunk ?: return null
                if (offset + bytes.size > result.size) return null
                bytes.copyInto(result, offset)
                offset += bytes.size
            }
            return result.takeIf { offset == totalLength }
        }
    }

    companion object {
        const val ACTION_USB_ATTACHED = "com.carlink.usbhelper.USB_ATTACHED"
        private const val HEADER_SIZE = 16
        private const val READ_TIMEOUT = 30_000
        private const val WRITE_TIMEOUT = 1_000
        private const val VIDEO_TYPE = 0x06
        private var nextTransferId = 1L

        private fun littleEndianInt(data: ByteArray, offset: Int): Int =
            ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
}
