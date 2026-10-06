# WebRTC loopback demo

A one-screen PAM Native app that places a video call between two peer
connections inside the same app with `pushinbr/pam-native-webrtc`. The offer,
answer and ICE candidates are handed over directly, which is what your
signalling server does between two phones; everything else (capture, ICE,
DTLS-SRTP, rendering, call audio) is the real native stack.

```bash
cd example
pam composer install
pam doctor --fix
pam dev            # or: pam build
```

The app installs the released package from Packagist. Grant camera and microphone, tap **Start loopback call** and the
remote view shows the front camera received over WebRTC; try Mute, Switch
camera and Stats.
