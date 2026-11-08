# Local ADB third-party attribution

The local ADB transport and DevTools verification code in `core/adb` is adapted from:

- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku), including its ADB protocol,
  message format and RSA public-key encoding/signing, licensed under Apache License 2.0.
- [thejaustin/ShizukuPlus](https://github.com/thejaustin/ShizukuPlus), the intermediate
  source of the transport. Shizuku+ credits thedjchi/Shizuku and RikkaApps/Shizuku.
- The local `ShizukuPlus` verification changes developed for this project: bounded
  `AdbServiceStream`, read-only WeChat HTTP/WebSocket/CDP probing, and protocol tests.

The Apache License 2.0 text is included in the repository's [LICENSE](../LICENSE).
Upstream copyright and license notices remain applicable.

Modifications for DSH: package relocation; independent Android Keystore-encrypted key
storage via DSH SecretStore; simplified single-offer TCP authorization; cancellation,
timeouts and serialized operation ownership; Kotlin capability tools and settings UI;
wireless TLS pairing with a public Conscrypt exporter and a source-built BoringSSL SPAKE2 JNI library; no packet/key logging; credential-free
page summaries and private snapshot persistence. The implementation does not use or
extract the installed Shizuku manager's private keys.

Native pairing sources under `app/src/main/cpp/pairing` and the JNI interface are adapted
from Shizuku/ShizukuPlus (Apache-2.0). The APK also includes Conscrypt (Apache-2.0),
Bouncy Castle (Bouncy Castle License), and BoringSSL (its upstream mixed permissive licenses).
Their upstream license terms remain applicable.
