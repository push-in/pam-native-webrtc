package dev.pam.webrtc

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.Executor

/**
 * Communication audio state machine (one per process).
 *
 * Routing priority: preferred Bluetooth headset, wired headset (unless the
 * speaker was explicitly requested), speaker when requested, earpiece.
 */
class CallAudioSession private constructor(context: Context) {
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val power = this.context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    internal val events = EventChannel(capacity = 16)

    @Volatile
    var active = false
        private set
    var mode = MODE_VOICE
        private set
    private var speakerRequested = false
    private var preferBluetooth = true
    private var proximityEnabled = true
    private var previousMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private var ringback: ToneGenerator? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var proximityLock: PowerManager.WakeLock? = null
    private var lastRoute = 0
    private var scoStarted = false
    private var communicationListener: Any? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = reroute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = reroute()
    }

    @Synchronized
    fun start(mode: Int) {
        if (!active) {
            previousMode = audio.mode
            requestFocus()
            audio.registerAudioDeviceCallback(deviceCallback, handler)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val listener = AudioManager.OnCommunicationDeviceChangedListener { report(currentRoute()) }
                audio.addOnCommunicationDeviceChangedListener(Executor { handler.post(it) }, listener)
                communicationListener = listener
            }
        }
        this.mode = if (mode == MODE_VIDEO) MODE_VIDEO else MODE_VOICE
        speakerRequested = this.mode == MODE_VIDEO
        active = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        applyRoute()
    }

    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        stopRingback()
        stopRingtone()
        releaseProximity()
        audio.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (communicationListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let {
                audio.removeOnCommunicationDeviceChangedListener(it)
            }
            communicationListener = null
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
            stopSco()
        }
        audio.isMicrophoneMute = false
        audio.mode = previousMode
        focusRequest?.let(audio::abandonAudioFocusRequest)
        focusRequest = null
        lastRoute = 0
    }

    @Synchronized
    fun setSpeaker(enabled: Boolean) {
        speakerRequested = enabled
        if (active) applyRoute()
    }

    @Synchronized
    fun setBluetooth(enabled: Boolean) {
        preferBluetooth = enabled
        if (active) applyRoute()
    }

    @Synchronized
    fun setProximity(enabled: Boolean) {
        proximityEnabled = enabled
        updateProximity(currentRoute())
    }

    fun setMicrophoneMute(muted: Boolean) {
        audio.isMicrophoneMute = muted
    }

    @Synchronized
    fun setRingback(enabled: Boolean) {
        if (!enabled) return stopRingback()
        if (ringback != null) return
        ringback = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME).also {
                it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }.getOrNull()
    }

    @Synchronized
    fun setRingtone(enabled: Boolean) {
        if (!enabled) return stopRingtone()
        if (ringtone != null) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_RINGTONE_URI
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }
        if (audio.ringerMode != AudioManager.RINGER_MODE_SILENT) vibrate()
    }

    fun isRingbackPlaying(): Boolean = ringback != null

    fun isRingtonePlaying(): Boolean = ringtone?.isPlaying == true

    fun isProximityHeld(): Boolean = proximityLock?.isHeld == true

    /** Current output route as one of the ROUTE_* constants (0 when inactive). */
    fun currentRoute(): Int {
        if (!active) return 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return audio.communicationDevice?.let { routeOf(it.type) }?.takeIf { it != 0 } ?: ROUTE_EARPIECE
        }
        @Suppress("DEPRECATION")
        return when {
            audio.isBluetoothScoOn -> ROUTE_BLUETOOTH
            audio.isSpeakerphoneOn -> ROUTE_SPEAKER
            ROUTE_WIRED in availableRoutes() -> ROUTE_WIRED
            else -> ROUTE_EARPIECE
        }
    }

    fun availableRoutes(): Set<Int> {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.availableCommunicationDevices
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        return devices.map { routeOf(it.type) }.filter { it != 0 }.toSet()
    }

    private fun reroute() {
        synchronized(this) { if (active) applyRoute() }
    }

    private fun desiredRoute(available: Set<Int>): Int = when {
        preferBluetooth && ROUTE_BLUETOOTH in available -> ROUTE_BLUETOOTH
        !speakerRequested && ROUTE_WIRED in available -> ROUTE_WIRED
        speakerRequested -> ROUTE_SPEAKER
        ROUTE_EARPIECE in available -> ROUTE_EARPIECE
        else -> ROUTE_SPEAKER
    }

    private fun applyRoute() {
        val target = desiredRoute(availableRoutes())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audio.availableCommunicationDevices.firstOrNull { routeOf(it.type) == target }
            if (device == null || !audio.setCommunicationDevice(device)) audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            when (target) {
                ROUTE_BLUETOOTH -> {
                    audio.isSpeakerphoneOn = false
                    if (!scoStarted) {
                        audio.startBluetoothSco()
                        scoStarted = true
                    }
                    audio.isBluetoothScoOn = true
                }
                ROUTE_SPEAKER -> {
                    stopSco()
                    audio.isSpeakerphoneOn = true
                }
                else -> {
                    stopSco()
                    audio.isSpeakerphoneOn = false
                }
            }
        }
        val route = currentRoute()
        updateProximity(route)
        report(route)
    }

    private fun report(route: Int) {
        if (route == lastRoute || route == 0) return
        lastRoute = route
        events.offer(mapOf("route" to WireValue.Integer(route.toLong())))
        updateProximity(route)
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        if (!scoStarted) return
        audio.isBluetoothScoOn = false
        audio.stopBluetoothSco()
        scoStarted = false
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        audio.requestAudioFocus(request)
        focusRequest = request
    }

    @SuppressLint("WakelockTimeout")
    private fun updateProximity(route: Int) {
        val wanted = active && proximityEnabled && mode == MODE_VOICE && route == ROUTE_EARPIECE
        if (!wanted) return releaseProximity()
        if (proximityLock?.isHeld == true) return
        if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximityLock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "pam:call-proximity").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseProximity() {
        proximityLock?.let { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
        proximityLock = null
    }

    private fun stopRingback() {
        ringback?.let {
            it.stopTone()
            it.release()
        }
        ringback = null
    }

    private fun stopRingtone() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    @SuppressLint("MissingPermission")
    private fun vibrate() {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (!device.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
        } else {
            @Suppress("DEPRECATION")
            device.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
            )
        }
        vibrator = device
    }

    companion object {
        const val MODE_VOICE = 1
        const val MODE_VIDEO = 2
        const val ROUTE_EARPIECE = 1
        const val ROUTE_SPEAKER = 2
        const val ROUTE_WIRED = 3
        const val ROUTE_BLUETOOTH = 4
        private const val RINGBACK_VOLUME = 80
        private val VIBRATION_PATTERN = longArrayOf(0, 700, 500, 700, 1_200)

        // Holds only the application context.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: CallAudioSession? = null

        fun get(context: Context): CallAudioSession =
            instance ?: synchronized(this) { instance ?: CallAudioSession(context).also { instance = it } }

        @SuppressLint("InlinedApi")
        fun routeOf(type: Int): Int = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> ROUTE_EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> ROUTE_SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> ROUTE_WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> ROUTE_BLUETOOTH
            else -> 0
        }
    }
}
