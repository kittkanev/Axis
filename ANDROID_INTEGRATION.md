# Connect Axis Android to an Axis backend

Axis 0.6 Preview installs as `io.github.kittkanev.axis.preview060`. Its signing key is separate from the earlier APKs. Install the signed release APK on Android 10 or newer, then choose **Axis Cloud** in the app menu.

## Local HTTPS over SSH

On the workstation, download the public CA certificate and start the tunnel from [server/README.md](server/README.md). Keep the tunnel running and connect the phone or emulator to the workstation with Android Debug Bridge.

```sh
adb devices
adb reverse tcp:8443 tcp:8443
adb push axis-local-ca.crt /sdcard/Download/axis-local-ca.crt
```

Enter `https://localhost:8443` on the **Axis Cloud** screen. Choose **Add CA certificate** and pick the downloaded file. Axis applies this certificate to this backend connection and verifies the `localhost` hostname. A standard public HTTPS endpoint uses Android's normal CA store. The local deployment remains reachable when the SSH tunnel and `adb reverse` are active.

## Accounts and workspace

Register with an email and a password of at least 12 characters. Pick a separate passphrase for encrypted workspaces. The file format is the following binary sequence.

| Field | Encoding |
| --- | --- |
| Magic and version | ASCII `AXWS`, then byte `01` |
| Salt | 16 cryptographically random bytes |
| Nonce | 12 cryptographically random bytes |
| Body | AES-256-GCM ciphertext followed by a 16-byte tag |

The header is authenticated as additional data. Argon2id v1.3 derives 32 bytes from the passphrase using the salt, 32 MiB of memory, three iterations, and one lane. The encrypted file contains profiles and chats. API keys remain in Android Keystore and matching local keys survive an import when the original profile ID and server URL match. Enter any missing API keys on a new device.

Files hold up to 8 MiB of encrypted data. Server copies hold up to 1 MiB of encrypted data. **Upload copy** writes the last known revision. A conflict preserves both copies. Export the local copy before restoring and replacing it from the server. Restoring a server copy records its revision for the next write.

## Models, reminders, and updates

- **Hosted model.** Open Axis Cloud, refresh the plan, and choose **Enable DeepSeek**. The screen describes Coarena's handling of inference requests. Activate the new model profile to use the plan quota.
- **Server tools.** Sign in, enable the tools you want in a model profile, and use them in chat. The managed DeepSeek profile offers the server tools and the standard Android tools. For a reminder, provide a date or time. Enable **Receive notifications** to keep a foreground connection to your private delivery stream. Allow notification permission on Android 13 or later. Force-stopping the app ends the connection.
- **Voice.** Under the profile's voice settings, choose **Piper** to send reply text to your server for synthesis. A signed-in backend connection is required. Local Android and Gemini voices remain available in the same picker.
- **Updates.** Open **App updates** in Axis Cloud. The APK must target the installed application ID and have a higher version code. Axis verifies the file against the SHA-256 published by the configured HTTPS server. Open the saved APK in Android to install it. Android checks its signing identity.

## Operator setup

Keep the signing key and backup archives protected. Use fresh account credentials for tests. Rotate shared credentials in the hosting panel and provider dashboard before opening the service to others. Plans, checkout, curl examples, and service recovery are documented in [server/README.md](server/README.md).
