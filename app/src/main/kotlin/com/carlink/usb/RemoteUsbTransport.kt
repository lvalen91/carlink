package com.carlink.usb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.carlink.ipc.NaviVideoSingleton
import com.carlink.usbbridge.IUsbBridge
import com.carlink.usbbridge.IUsbBridgeCallback
import com.carlink.usbbridge.UsbBridgeConstants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Play-app transport backed by the sideloaded android.car.usb.handler helper. */
class RemoteUsbTransport(
    context: Context,
    private val logCallback: (String) -> Unit,
) : UsbTransport {
    private val appContext = context.applicationContext
    private val connected = AtomicBoolean(false)
    private val transferIds = AtomicLong(1)
    private val inbound = ConcurrentHashMap<Long, ChunkAccumulator>()
    private var bridge: IUsbBridge? = null
    private var connection: ServiceConnection? = null
    private var ready = CountDownLatch(1)
    private val bindingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    var failureDetail: String = ""
        private set
    private var readCallback: UsbTransport.ReadingLoopCallback? = null
    private var videoProcessor: UsbTransport.VideoDataProcessor? = null

    override val isOpened: Boolean get() = connected.get()

    private val callback = object : IUsbBridgeCallback.Stub() {
        override fun onState(state: Int, detail: String?) {
            log("[BRIDGE] state=$state detail=$detail")
            if (state == UsbBridgeConstants.STATE_ERROR) {
                failureDetail = detail ?: "USB helper error"
                readCallback?.onError(failureDetail)
            }
            if (state == UsbBridgeConstants.STATE_DISCONNECTED) {
                connected.set(false)
                readCallback?.onError(detail ?: "USB helper disconnected")
            }
        }

        override fun onInboundChunk(
            transferId: Long,
            messageType: Int,
            sourcePtsMs: Int,
            chunkIndex: Int,
            chunkCount: Int,
            totalLength: Int,
            data: ByteArray,
        ) {
            val accumulator = inbound.getOrPut(transferId) {
                ChunkAccumulator(messageType, sourcePtsMs, chunkCount, totalLength)
            }
            if (!accumulator.add(chunkIndex, data)) return
            if (!accumulator.complete()) return
            inbound.remove(transferId)
            val payload = accumulator.join() ?: return
            val processor = videoProcessor
            if (messageType == VIDEO_TYPE && processor != null && payload.isNotEmpty()) {
                processor.processVideoDirect(payload, payload.size, sourcePtsMs)
                readCallback?.onMessage(messageType, null, 0)
            } else if (messageType == NAVI_VIDEO_TYPE && NaviVideoSingleton.enabled && payload.isNotEmpty()) {
                NaviVideoSingleton.forwarder.onUsbFrame(payload, payload.size)
                readCallback?.onMessage(messageType, null, 0)
            } else {
                readCallback?.onMessage(messageType, payload.takeIf { it.isNotEmpty() }, payload.size)
            }
        }
    }

    fun connect(timeoutMs: Long = 5000L): Boolean {
        if (bridge != null) return connected.get()
        failureDetail = ""
        ready = CountDownLatch(1)
        val intent = Intent().setComponent(
            ComponentName(UsbBridgeConstants.BRIDGE_PACKAGE, UsbBridgeConstants.BRIDGE_SERVICE_CLASS),
        )
        val resolved = appContext.packageManager.resolveService(intent, 0)
        if (resolved == null) {
            failureDetail = "helper service is not installed for this AAOS user"
            log("[BRIDGE] $failureDetail: ${intent.component}")
            return false
        }
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                bridge = IUsbBridge.Stub.asInterface(service)
                try {
                    bridge?.registerCallback(callback)
                } catch (e: RemoteException) {
                    log("[BRIDGE] callback registration failed: ${e.message}")
                }
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                connected.set(false)
                bridge = null
                readCallback?.onError("USB helper service disconnected")
            }

            override fun onNullBinding(name: ComponentName) {
                failureDetail = "helper service returned a null binder"
                log("[BRIDGE] $failureDetail")
                ready.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                failureDetail = "helper service binding died"
                log("[BRIDGE] $failureDetail")
                ready.countDown()
            }
        }
        connection = serviceConnection
        return try {
            // GM's AAOS service manager can reject bind-only creation for a
            // sideloaded exported service. Start the helper explicitly first;
            // it enters the foreground in onCreate(), then bind to that instance.
            // Use a dedicated callback executor. Calling await() on the main
            // thread would otherwise block the thread that delivers
            // onServiceConnected(), producing a false binding timeout.
            if (!appContext.bindService(intent, Context.BIND_AUTO_CREATE, bindingExecutor, serviceConnection)) {
                failureDetail = "bindService returned false; helper service is unavailable"
                log("[BRIDGE] $failureDetail")
                false
            } else if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                failureDetail = "timed out binding helper service"
                log("[BRIDGE] $failureDetail")
                false
            } else {
                connected.set(bridge?.open() == true)
                if (!connected.get() && failureDetail.isBlank()) {
                    failureDetail = "helper opened, but no usable USB adapter was available"
                }
                if (!connected.get()) log("[BRIDGE] $failureDetail")
                connected.get()
            }
        } catch (e: Exception) {
            failureDetail = "connect failed: ${e.message ?: e.javaClass.simpleName}"
            log("[BRIDGE] $failureDetail")
            false
        }
    }

    override fun write(data: ByteArray, timeout: Int): Int {
        if (!connected.get()) return -1
        val service = bridge ?: return -1
        val transferId = transferIds.getAndIncrement()
        val count = maxOf(1, (data.size + UsbBridgeConstants.CHUNK_SIZE - 1) / UsbBridgeConstants.CHUNK_SIZE)
        data.asList().chunked(UsbBridgeConstants.CHUNK_SIZE).forEachIndexed { index, bytes ->
            if (!service.sendChunk(transferId, index, count, data.size, bytes.toByteArray())) return -1
        }
        if (data.isEmpty() && !service.sendChunk(transferId, 0, 1, 0, ByteArray(0))) return -1
        return data.size
    }

    override fun startReadingLoop(
        callback: UsbTransport.ReadingLoopCallback,
        timeout: Int,
        videoProcessor: UsbTransport.VideoDataProcessor?,
    ) {
        readCallback = callback
        this.videoProcessor = videoProcessor
    }

    override fun stopReadingLoop() {
        readCallback = null
        videoProcessor = null
        inbound.clear()
    }

    override fun close() {
        stopReadingLoop()
        try {
            bridge?.unregisterCallback(callback)
            bridge?.close()
        } catch (_: Exception) {
        }
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
        bridge = null
        connected.set(false)
        bindingExecutor.shutdownNow()
    }

    private fun log(message: String) {
        logCallback(message)
    }

    private class ChunkAccumulator(
        val messageType: Int,
        val sourcePtsMs: Int,
        private val count: Int,
        private val totalLength: Int,
    ) {
        private val chunks = arrayOfNulls<ByteArray>(count)
        fun add(index: Int, data: ByteArray): Boolean {
            if (index !in chunks.indices || chunks[index] != null) return false
            chunks[index] = data.copyOf()
            return true
        }
        fun complete() = chunks.all { it != null }
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
        private const val VIDEO_TYPE = 0x06
        private const val NAVI_VIDEO_TYPE = 0x2C
    }
}
