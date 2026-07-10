package zeno.carlink.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.carlink.ipc.INaviVideoSink
import com.carlink.ipc.INaviVideoSource
import com.carlink.ipc.NaviVideoSingleton

/**
 * Bound Service that exposes the carlink_native AltVideo (USB 0x2C) stream
 * to other apps over AIDL.
 *
 * Class FQN deliberately lives under `zeno.carlink.ipc` (matching the
 * applicationId, not the Kotlin namespace `com.carlink`) so the ComponentName
 * the consumer binds to —
 *   ComponentName("zeno.carlink", "zeno.carlink.ipc.NaviVideoSourceService")
 * — resolves correctly. The AIDL interfaces themselves stay under
 * `com.carlink.ipc` to match the consumer's package import.
 *
 * Protected by `com.carlink.permission.NAVI_VIDEO_STREAM` (signature|privileged);
 * only co-signed apps can bind. See AndroidManifest.xml.
 */
class NaviVideoSourceService : Service() {
    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : INaviVideoSource.Stub() {
        override fun registerSink(sink: INaviVideoSink) {
            NaviVideoSingleton.forwarder.registerSink(sink)
        }

        override fun unregisterSink(sink: INaviVideoSink) {
            NaviVideoSingleton.forwarder.unregisterSink(sink)
        }

        override fun isStreamActive(): Boolean =
            NaviVideoSingleton.forwarder.isStreamActive()
    }
}
