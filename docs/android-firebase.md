# Android Firebase setup for Kubble

Kubble can use a personal Firebase project for authentication and the cloud-backed watchapp/watchface locker. A dummy configuration is enough to compile, but cannot provide these services. The existing Google sign-in and Firestore implementations are reused; no Core Devices backend credentials are needed for these features.

## Create the project

1. Sign into the [Firebase console](https://console.firebase.google.com/) with a Google account and create a project. The free Spark plan is sufficient to start; Google Analytics and Gemini are optional.
2. Register an Android app with package name `coredevices.coreapp`.
3. Add the SHA-1 fingerprint of the certificate that signs the APK you will install. For the default local debug key:

   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore \
     -alias androiddebugkey -storepass android -keypass android
   ```

   A build signed with another certificate needs its own fingerprint. Do not generate a new debug key when updating an existing installation.
4. In Authentication, enable **Anonymous** and **Google** providers. Anonymous authentication is used automatically; Google links the account to a persistent identity for later installations. Select a support email if prompted.
5. Create the default Cloud Firestore database using Standard edition, if an edition choice is offered. Choose an appropriate region and start in **Production mode**. Publish the rules below before using the app. Test-mode rules expose data to other clients.
6. After enabling Google authentication and adding the certificate fingerprint, download the updated `google-services.json` from Project settings → General → Your apps.

## Configure the local build

Copy the downloaded configuration to `androidApp/src/google-services.json`.

In the root `local.properties`, keep the existing SDK setting and add:

```properties
googleClientId=<WEB_OAUTH_CLIENT_ID>
```

Use the Web OAuth client ID (`client_type: 3`) from the matching Android app's `oauth_client` array in `google-services.json`, not the Android OAuth client ID (`client_type: 1`). It normally ends in `.apps.googleusercontent.com`. Kubble reads this property explicitly; downloading the JSON alone does not configure its Google sign-in button.

Both local files are ignored by Git. Do not commit service-account keys, passwords, or personal configuration. Google login does not require a service-account key. CI still uses the supplied dummy JSON and does not build against the personal project.

Build with JDK 17:

```bash
./gradlew :androidApp:assembleDebug
```

On the local macOS setup:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :androidApp:assembleDebug
mkdir -p ~/notes/exchange
cp androidApp/build/outputs/apk/debug/androidApp-debug.apk ~/notes/exchange/
```

Install over the existing app using the same signing certificate. Open Settings → Phone → General → **Sign In – Pebble Account**, then **Sign in with Google**. Despite the label, this signs into the Firebase project configured in your APK. Rebble is a separate account. After login, the setting shows **Sign Out – Pebble Account** with your email.

## Firestore rules for watch features

In Firestore → Rules, replace the initial rules with the following and click **Publish**. Each user, including an anonymous user, can access only their own profile, locker entries, and watch metadata. User configuration is read-only from the app. Other paths remain denied.

```text
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function isOwner(uid) {
      return request.auth != null && request.auth.uid == uid;
    }

    match /users/{uid} {
      allow read, write: if isOwner(uid);
    }

    match /lockers/{uid}/entries/{entryId} {
      allow read, write: if isOwner(uid);
    }

    match /known_watches/{uid}/watches/{watchId} {
      allow read, write: if isOwner(uid);
    }

    match /user_config/{uid} {
      allow read: if isOwner(uid);
    }
  }
}
```

These rules cover watch features only. Index/Ring cloud collections are not enabled by this setup. Downloading `google-services.json` does not publish rules or enable authentication providers; those are separate console actions.

## What is stored

- **Firebase Authentication:** an anonymous or Google-linked identity.
- **`lockers/{uid}/entries`:** watchapp/watchface UUID, store ID, store source, and optional Timeline token. App binaries are fetched from the store, not stored in these documents.
- **`known_watches/{uid}/watches`:** serial, model, color, nickname, firmware version, last-connected time, and connection-goal flag. This is uploaded metadata, not a backup of Bluetooth pairing.
- **`users/{uid}`:** user token, last-connected watch, and optional integration data such as Rebble/Notion tokens when those integrations are used.
- **`user_config/{uid}`:** optional server-side configuration read by the app; an absent document uses defaults. This is not a backup of the local app settings.

Anonymous accounts alone do not provide recovery after reinstalling. Use Google sign-in to associate the locker with a persistent account. An existing account from another Firebase project does not migrate automatically.

A personal Firebase project does not authorize access to Core Devices' private services, replace Rebble subscriptions, or back up the entire phone. Local BLE, notifications, health/battery storage, GitHub firmware checks, and direct OpenAI transcription do not gain a new backend from this configuration.

## Verification

The personal Firebase Android build based on `1.14.0.1+ecd45f86` compiled successfully; all 170 `util` and `pebble` host tests passed. Google sign-in was confirmed on an Android device after the rules were published. Cross-install locker recovery and authorization-isolation tests have not been performed.

References: [Android setup](https://firebase.google.com/docs/android/setup), [Google sign-in](https://firebase.google.com/docs/auth/android/google-signin), [Firestore security rules](https://firebase.google.com/docs/firestore/security/get-started).
