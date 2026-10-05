package dev.pam.webrtc

import android.content.Context
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.NativeModule
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

/** PAM module `webrtc`: peer connections addressed by session id. */
class WebRtcModule(private val context: Context) : NativeModule {
    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val values = WireMap.decode(payload)
            val id = values.text("sessionId")
            when (method) {
                "create" -> {
                    WebRtcRuntime.create(
                        context,
                        RtcSessionConfig(
                            id = id,
                            iceServersJson = values.text("iceServersJson", "[]"),
                            video = values.flag("video"),
                            facing = values.integer("facing", 1).toInt(),
                            width = values.integer("width", 1280).toInt().coerceIn(160, 3840),
                            height = values.integer("height", 720).toInt().coerceIn(120, 2160),
                            fps = values.integer("fps", 30).toInt().coerceIn(5, 60),
                            relayOnly = values.flag("relayOnly"),
                        ),
                    )
                    completion.success()
                }
                "next" -> WebRtcRuntime.session(id)?.events?.next(completion)
                    ?: completion.failure("RTC session $id not found")
                "startLocal" -> WebRtcRuntime.sessionOrThrow(id).startLocal(completion)
                "createOffer" -> WebRtcRuntime.sessionOrThrow(id).createOffer(values.flag("iceRestart"), completion)
                "createAnswer" -> WebRtcRuntime.sessionOrThrow(id).createAnswer(completion)
                "setRemoteDescription" -> WebRtcRuntime.sessionOrThrow(id).setRemote(
                    values.integer("type", 0).toInt(),
                    values.text("sdp"),
                    completion,
                )
                "addIceCandidate" -> completion.success(
                    mapOf(
                        "added" to WireValue.Flag(
                            WebRtcRuntime.sessionOrThrow(id).addCandidate(
                                values.text("sdpMid", ""),
                                values.integer("sdpMLineIndex", 0).toInt(),
                                values.text("candidate"),
                            ),
                        ),
                    ),
                )
                "setMicrophone" -> {
                    WebRtcRuntime.sessionOrThrow(id).setMicrophone(values.flag("enabled", true))
                    completion.success()
                }
                "setCamera" -> WebRtcRuntime.sessionOrThrow(id).setCamera(values.flag("enabled", true), completion)
                "switchCamera" -> WebRtcRuntime.sessionOrThrow(id).switchCamera(completion)
                "stats" -> WebRtcRuntime.sessionOrThrow(id).stats(completion)
                "close" -> {
                    WebRtcRuntime.close(id)
                    completion.success()
                }
                else -> completion.failure("Unknown WebRTC method $method")
            }
        }.onFailure { completion.failure(it.message ?: "WebRTC operation failed") }
    }
}
