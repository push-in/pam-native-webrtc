package dev.pam.webrtc

import android.content.Context
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.NativeModule
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

/** PAM module `call-audio`: replaces InCallManager for PAM applications. */
class CallAudioModule(private val context: Context) : NativeModule {
    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val values = WireMap.decode(payload)
            val audio = CallAudioSession.get(context)
            when (method) {
                "start" -> audio.start(values.integer("mode", CallAudioSession.MODE_VOICE.toLong()).toInt())
                "stop" -> audio.stop()
                "setSpeaker" -> audio.setSpeaker(values.flag("enabled", true))
                "setBluetooth" -> audio.setBluetooth(values.flag("enabled", true))
                "setRingback" -> audio.setRingback(values.flag("enabled", true))
                "setRingtone" -> audio.setRingtone(values.flag("enabled", true))
                "setProximity" -> audio.setProximity(values.flag("enabled", true))
                "setMicrophoneMute" -> audio.setMicrophoneMute(values.flag("muted", true))
                "route" -> return@runCatching completion.success(mapOf("route" to WireValue.Integer(audio.currentRoute().toLong())))
                "next" -> return@runCatching audio.events.next(completion)
                else -> return@runCatching completion.failure("Unknown call audio method $method")
            }
            completion.success()
        }.onFailure { completion.failure(it.message ?: "Call audio operation failed") }
    }
}
