# Alibi 📞

<!--suppress HtmlDeprecatedAttribute -->
<div align="center">

<img src="assets/img.png" alt="Alibi App Icon" height="256" width="256" style="margin-bottom: 16px;" />

<br>
<br>

<p>

[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.1.20-7F52FF.svg?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg?style=flat-square&logo=android&logoColor=white)](https://www.android.com)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Telecom API](https://img.shields.io/badge/API-Android%20Telecom%201.0.1-00897B.svg?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/guide/topics/connectivity/telecom)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-37%20(Android%2016)-2563EB.svg?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/about/versions/16)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-24-F59E0B.svg?style=flat-square)](https://developer.android.com/about/versions/nougat/)
[![License: PolyForm Noncommercial](https://img.shields.io/badge/License-PolyForm%20Noncommercial%201.0.0-blue.svg?style=flat-square)](LICENSE)

</p>
</div>

---

**Alibi** is a modern, high-reliability Android application designed for advanced telephony management and realistic simulated calls. Built from the ground up using modern Android architecture, standard Jetpack libraries, and Android's native **Telecom Framework**, Alibi provides a seamless call UI, system-integrated call logging, background audio session management, and rock-solid lockscreen full-screen intent (FSI) delivery.

---

## 🛠 Tech Stack & Core Resources

| Resource / Component                  | Role & Usage in Alibi                                                                                                                                                           |
|:--------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Pure Kotlin**                       | Written 100% in idiomatic Kotlin utilizing Kotlin Coroutines (`StateFlow`, `SharedFlow`, `SupervisorJob`, `Mutex`) for structured concurrency and reactive state management.    |
| **Jetpack Compose Ecosystem**         | Modern declarative UI powered by **Compose Material 3**, **Jetpack Navigation 3**, **Material 3 Adaptive Layouts**, and `Lifecycle` integration.                                |
| **Telecom API (`InCallService`)**     | `CallService` binds to Android's native Telecom manager to manage real phone calls, capture call events, and act as a Default Dialer UI.                                        |
| **Telecom API (`ConnectionService`)** | `SimulatedConnectionService` registers a self-managed `PhoneAccount` (`CAPABILITY_SELF_MANAGED`) to drive native-like simulated calls through Android's telephony architecture. |
| **Foreground Services**               | `CallNotificationService` enforces Android 14/15/16 Foreground Service (FGS) compliance (`phoneCall`, `microphone`) with synchronous `startForeground` execution.               |
| **Audio & Sensor Hardware**           | Integrated `ProximityController` (Proximity Sensor management) and `AudioHeartbeatManager` (AudioTrack heartbeat) for hardware-level call behavior.                             |

### 💡 Why This Architecture?
* **System Native Integration:** Rather than displaying floating overlays or fake screens that break when navigating away or locking the device, Alibi plugs directly into Android's native `TelecomManager`.
* **Lockscreen & Lifecycle Resiliency:** Calls remain active and controllable across screen locks, device sleep, app switching, and multi-window scenarios.
* **Clean Session Architecture:** Clear separation between data repositories (`CallRepository`), session management (`RealCallSession`, `SimulatedCallSession`, `OptimisticCallSession`), UI state bridges (`CallStateManager`), and execution controllers (`SimulationController`).

---

## 🎭 How Simulated Calls Work

Alibi allows users to schedule or immediately trigger authentic-looking incoming, outgoing, or missed calls with customizable caller information, delay timers, and call durations.

```
[ User Input / Trigger ]
           │
           ▼
[ SimulatedCallRequest ] ──► [ OptimisticCallSession ] (Instant UI Responsiveness)
                                       │
                                       ▼
                       [ TelecomManager.addNewIncomingCall / placeCall ]
                                       │
                                       ▼
                         [ SimulatedConnectionService ]
                                       │
                                       ▼
  [ SimulatedConnection ] ──► Registered in [ SimulationController ]
                                       │
                    ┌──────────────────┴──────────────────┐
                    ▼                                     ▼
        [ AudioHeartbeatManager ]             [ CallLogHelper ]
      (Keeps Audio Session Alive)           (System Call Log Entry)
```

### Key Components of Simulation:
1. **Request & Session Instantiation:** A `SimulatedCallRequest` is created with caller name, number, SIM handle, call direction (Incoming/Outgoing/Missed), delay, and duration.
2. **Optimistic Phase:** An `OptimisticCallSession` immediately surfaces the call UI for responsive user interaction while system Telecom handles asynchronous `PhoneAccount` binding.
3. **Simulation Controller (`SimulationController`):** Manages timers for auto-answering outgoing calls, auto-missing unanswered incoming calls, and auto-hanging up after specified durations.
4. **Android 15/16+ Cloaking Watchdog:** Prevents the Android OS from abruptly killing long-running ringing/dialing calls by safely transitioning connection state to `ACTIVE` and muting audio when watchdog timeouts (40s) are reached.
5. **Audio Heartbeat (`AudioHeartbeatManager`):** Streams a continuous low-latency silent audio loop to maintain an active Telecom audio session and prevent CPU deep-sleep audio drops.
6. **Atomic Call Logging (`CallLogHelper`):** Upon call termination, call metadata (number, start time, answer time, actual duration, SIM slot) is written directly into Android's native system call log (`CallLog.Calls`).

---

## 🔀 Multi-Call Architecture & Preemption Engine

Alibi features a dedicated multi-call orchestration engine built around `CallRepository`, `CallStateManager`, and `CallCoordinator`. It safely handles concurrent real and simulated call sessions.

```
                  ┌───────────────────────────────┐
                  │      CallRepository           │
                  │  (PersistentMap<ID, Session>) │
                  └───────────────┬───────────────┘
                                  │
       ┌──────────────────────────┼──────────────────────────┐
       ▼                          ▼                          ▼
[ RealCallSession ]    [ SimulatedCallSession ]   [ OptimisticCallSession ]
(System android.telecom)  (SimulatedConnection)    (Transient UI state)
       │                          │
       └──────────────┬───────────┘
                      ▼
             [ CallCoordinator ] ──► Preempts Simulated Call if Real Call Starts
                      │
                      ▼
             [ CallStateManager ] ──► Smart Promotion of Primary Call Focus
```

### 1. Thread-Safe Session Registry (`CallRepository`)
All call sessions implement the unified `CallSession` interface and are registered in an immutable `PersistentMap` inside `CallRepository`.
* **Session Types:**
  * `RealCallSession`: Wraps native cellular or VoIP calls via `android.telecom.Call`.
  * `SimulatedCallSession`: Wraps custom simulated calls via `SimulatedConnection`.
  * `OptimisticCallSession`: A transient session providing instant UI responsiveness while system Telecom finishes registering the self-managed connection.
* **Seamless Session Merging:** When the system finishes instantiating a concrete connection, `CallRepository` automatically merges and replaces the optimistic session matching the phone number, preventing duplicate entries or UI flickers.

### 2. Call Preemption Rules (`CallCoordinator`)
Real cellular or VoIP calls always take priority over simulated calls.
* **Preemption Enforcement:** `CallCoordinator` actively observes active sessions. If a real call becomes active (`isRealCall == true`), `CallCoordinator` instantly preempts and disconnects any running simulated call (`session.hangup()`).
* **Busy State Protection:** If a real or simulated call is active, `CallCoordinator` publishes a system-wide busy state message (`"A real call is currently happening. Try again later."`), preventing accidental overlapping simulated triggers.

### 3. Hold, Unhold, and Swap Logic
Alibi supports active holding and swapping between multiple call lines:
* **Real Calls:** Dispatches native `Call.hold()` and `Call.unhold()` commands directly to Android's telephony layer.
* **Simulated Calls:** `SimulationController` executes `connection.setOnHold()` or `connection.setActive()`, updates the simulation phase, pauses or resumes the `AudioHeartbeatManager` track, and synchronizes state across the UI and notification shade.

### 4. Smart Primary Call Promotion
When multiple calls exist (e.g. one on hold and one active, or an incoming call waiting) and the primary active call is hung up:
* `CallStateManager.removeCall()` automatically evaluates remaining calls and promotes the next best session to `currentCallId` (Priority: Active Call > Ringing Call > Most recent session).
* `CallNotificationService` simultaneously promotes the next best call to the primary foreground notification slot (`NOTIFICATION_ID = 101`), guaranteeing continuous call control without UI dropouts.

---

## 🎨 Notification Styling & System Architecture

Managing phone call notifications across different Android versions, multiple simultaneous calls, and edge cases requires strict architectural guardrails. Alibi implements a specialized notification system built into `CallNotificationService` and `CallNotificationFactory`.

### 1. `Notification.CallStyle` vs Standard Notification Styling
Android 12+ introduced `Notification.CallStyle` to provide standardized native call notifications (large caller avatars, Answer/Decline chips, ongoing call timers). However, **Android limits `CallStyle` to a single primary foreground notification**.
* **Primary Foreground Call:** Uses native `Notification.CallStyle.forIncomingCall()` or `Notification.CallStyle.forOngoingCall()` for maximum system fidelity and lockscreen chip integration.
* **Secondary / Waiting Calls:** Automatically formatted as standard high-priority notifications with explicit action buttons (`Answer`, `Hangup`), preventing notification crashes or UI corruption when multiple calls exist concurrently.

### 2. Fixing the System Timer Bar / Chip Issue (Connecting State)
A common bug in Android call apps is status bar chips counting elapsed call duration prematurely while a call is still dialing or connecting ("Calling...").
* **Connecting State:** During `DIALING` or `CONNECTING` states, Alibi explicitly suppresses the chronometer by calling `setUsesChronometer(false)` , `setShowWhen(false)` and use a **Standard Notification**.
* **Active State:** The chronometer (`setUsesChronometer(true)`) and elapsed timestamp (`setWhen(startTime)`) are enabled **only** after the call transitions to `STATE_ACTIVE` (`startTime > 0L`), And the notification is transition to `Notification.CallStyle` ensuring accurate status bar call chips.

### 3. Eliminating Zombie Notifications
Zombie notifications (stale or stuck notifications that persist after a call ends) are completely eliminated through four architectural layers:
* **Thread-Safe Serialization (`Mutex.withLock`):** All notification updates, promotions, and cancellations pass through a centralized `notificationMutex` in `CallNotificationService`.
* **Idempotent Promotion Engine:** When a primary call ends, `performCancelNotification()` evaluates remaining active calls (`getBestCallToPromote()`) and automatically promotes the highest-priority active call (Ringing > Active > Connecting) into the primary slot (`NOTIFICATION_ID = 101`).
* **Orphan Guard:** Before posting, the service verifies that `foregroundCallId` points to an existing call in `CallRepository` or `CallStateManager`, instantly clearing orphaned foreground states.
* **5-Second Grace Period (`startDelayedStopCheck`):** When active calls drop to zero, `CallNotificationService` waits 5 seconds before invoking `stopSelf()`. If a new call arrives within 5 seconds, the delayed stop is canceled, preventing rapid service restarts and state locks.

### 4. Foreground Service (FGS) Compliance
`CallNotificationService` declares and enforces `FOREGROUND_SERVICE_TYPE_PHONE_CALL` and `FOREGROUND_SERVICE_MICROPHONE` types, using `FOREGROUND_SERVICE_IMMEDIATE` behavior to adhere strictly to Android 14+ background execution policies.

---

## 🔒 FullScreenIntent (FSI) & Lockscreen Reliability

Launching an activity over a locked screen when an incoming call arrives is notoriously difficult on Android 14+ (API 34+) due to strict Background Activity Launch (BAL) restrictions and Foreground Service (FGS) deadlines. 

Alibi achieves **100% reliable FullScreenIntent launches** by following a precise execution sequence and flag-tracking strategy:

### 1. Synchronous FGS Deadline Compliance
In `CallNotificationService.onStartCommand()`, Alibi posts a high-importance foreground notification with `startForeground()` **on line 1 of execution**, satisfying Android 14's strict synchronous FGS initialization deadline before running any asynchronous background tasks.

### 2. Specialized Notification Channels & Background Activity Launch Rights
* **Incoming Calls Channel (`CHANNEL_ID_INCOMING`):** Configured with `IMPORTANCE_HIGH`, ringtone audio attributes (`USAGE_NOTIFICATION_RINGTONE`), and vibration pattern to trigger system heads-up displays and full-screen intents.
* **Ongoing/Outgoing Calls Channel (`CHANNEL_ID`):** Configured with `IMPORTANCE_LOW` to quietly stay in the notification shade without intrusive banners.
* **PendingIntent BAL Flags:** Built using `ActivityOptions.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)` on Android 14+, granting background launch rights directly to SystemUI.

### 3. Dedicated Lockscreen Window Flags (`InCallActivity`)
The lockscreen activity (`InCallActivity`) is isolated with key AndroidManifest attributes:
```xml
<activity
    android:name=".ui.InCallActivity"
    android:exported="false"
    android:showWhenLocked="true"
    android:turnScreenOn="true"
    android:excludeFromRecents="true"
    android:launchMode="singleTask"
    android:taskAffinity="com.warhuman.alibi.incall"
    android:theme="@style/Theme.Alibi.InCall" />
```
At runtime, `InCallActivity` dynamically updates window flags based on call state:
* `setShowWhenLocked(true)` and `setTurnScreenOn(isRinging)` via `Activity` API / `WindowManager.LayoutParams`.
* `FLAG_KEEP_SCREEN_ON` during active ringing.

### 4. Screen Wake & Reissue Logic
To prevent missable calls when the screen turns on manually while a call is ringing:
* **Screen Wake Broadcast Receiver:** `screenWakeReceiver` listens for `Intent.ACTION_SCREEN_ON`.
* **State Verification:** When the screen turns on, if a call is active/ringing and the device is locked (`KeyguardManager.isKeyguardLocked`), Alibi re-issues `InCallActivity` via `FLAG_ACTIVITY_REORDER_TO_FRONT`.
* **Notification State Tracking & Reissue:** `CallNotificationService` tracks `NotificationState` and deduplicates redundant notifications, while automatically promoting remaining active calls to the primary foreground slot (`NOTIFICATION_ID = 101`) when a primary call disconnects.

### 5. Cold-Start Scenario Reliability
Even if the application process is completely dead / killed in the background when an incoming call (real or simulated) is triggered:
1. The Android OS or Telecom framework invokes `CallService` (`InCallService`) or `SimulatedConnectionService` (`ConnectionService`).
2. The service calls `ContextCompat.startForegroundService()` to wake `CallNotificationService`.
3. `CallNotificationService.onStartCommand()` instantly executes `startForeground()` on line 1, creating a valid FGS context before Android's 10-second process kill timer expires.
4. `CallNotificationFactory` attaches `setFullScreenIntent(pendingIntent, true)` with `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` background launch privileges.
5. SystemUI launches `InCallActivity` seamlessly over the keyguard, bringing up the Jetpack Compose `ActiveCallScreen` instantly without requiring the main app process to have been pre-warmed.

---

## ✨ Key Features & Highlights

* 📱 **Full Default Dialer Support:** Handles native phone calls, incoming call alerts, call holding, audio routing (Speaker, Earpiece, Bluetooth), and DTMF keypad.
* 👂 **Proximity Sensor Integration:** Automatically turns-off screen display when placing the phone near your face during calls (`ProximityController`).
* 📶 **Multi-SIM Slot Selection:** Automatically detects call-capable SIM cards and lets users select specific SIM accounts for simulated call logs.
* 🛡 **Graceful Permissions Onboarding:** A streamlined onboarding flow handling Dialer Role, Call Log access, Notifications, Phone State, and Contacts.
* 📱 **Adaptive UI Layouts:** UI adapts cleanly to standard phones, foldables, and large screen devices using Jetpack Compose Adaptive.

---

## 📦 Download & Installation

Currently, Alibi is in active development and has not yet been published to public app stores. You can build and deploy the application directly from source.

### Prerequisites
* **Android Studio** (2024.1+ recommended)
* **Android SDK 37** (Compile SDK: 37, Min SDK: 24)
* **JDK 11 / 17**

### Building from Source (GitHub)

1. **Clone the repository:**
   ```bash
   git clone https://github.com/WarHuman9/Alibi.git
   cd Alibi
   ```

2. **Build Debug APK via Gradle:**
   ```bash
   ./gradlew assembleDebug
   ```
   *OR*
   ```bash
   ./gradlew :app:assembleRelease
   ```

3. **Install on connected device via ADB:**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
   * *(Change ``` debug ``` to ```release``` , if you want to release-build)*

### Releases
Pre-built APK files for releases can be downloaded from the **GitHub Repository Releases** tab when available.

---

## 🔑 Required System Permissions

To function as a complete default dialer and simulated call manager, Alibi requests:
* `android.permission.MANAGE_OWN_CALLS`
* `android.permission.READ_PHONE_STATE` & `READ_PHONE_NUMBERS`
* `android.permission.ANSWER_PHONE_CALLS`
* `android.permission.READ_CALL_LOG` & `WRITE_CALL_LOG`
* `android.permission.POST_NOTIFICATIONS`
* `android.permission.USE_FULL_SCREEN_INTENT`
    *(Strict android devices may need extra permissions like **"Show on Lock Screen", "background-start", "Lock-screen permissions"** etc. Which are needed for lock-screen access & cold-start granting)*
* `android.permission.FOREGROUND_SERVICE_PHONE_CALL` & `FOREGROUND_SERVICE_MICROPHONE`

---

## 📄 License

This project is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE).

<br>
<br>

