# Task 3: Call Simulation Implementation Plan

This plan outlines the implementation of a realistic call simulation using the Android Telecom framework, including a `ConnectionService`, `InCallService`, and an adaptive `ActiveCallScreen`.

## Proposed Changes

### 1. Telecom Framework Components

#### [NEW] [CallConnection.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/service/CallConnection.kt)
Implement a custom `Connection` class to represent the simulated call state. It will handle events like disconnect, hold, etc.

#### [NEW] [CallConnectionService.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/service/CallConnectionService.kt)
Implement a `ConnectionService` to manage outgoing calls. This service will be registered in the manifest with the `MANAGE_OWN_CALLS` permission to allow self-managed calls.

#### [MODIFY] [CallService.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/service/CallService.kt)
Update the existing `InCallService` to handle `onCallAdded` and `onCallRemoved`. It will communicate call state changes to the UI via a singleton or observable state.

#### [NEW] [TelecomHelper.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/util/TelecomHelper.kt)
Utility class to register the `PhoneAccount` and place calls via `TelecomManager`.

### 2. UI Components

#### [MODIFY] [ActiveCallScreen.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/ui/screens/ActiveCallScreen.kt)
Enhance the UI to look like a realistic dialer.
- Add a simulated dialpad.
- Display call duration (timer).
- Use `ListDetailPaneScaffold` for adaptive layout on larger screens.
- Use Material 3 Expressive components.

### 3. Manifest and Permissions

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/AndroidManifest.xml)
- Register `CallConnectionService`.
- Ensure all necessary permissions are declared.
- Add a notification channel for the calling notification.

### 4. Logic Integration

#### [MODIFY] [MainActivity.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/MainActivity.kt)
- Register the `PhoneAccount` on startup.
- Handle navigation to `ActiveCallScreen` when a call is initiated.

## Verification Plan

### Automated Tests
- Create a unit test for `CallConnection` to verify state transitions.
- Create an instrumented test for `ActiveCallScreen` to check UI elements.

### Manual Verification
- Launch the app, set as default dialer.
- Enter a phone number and click "Start Call".
- Verify that the `ActiveCallScreen` appears.
- Verify that a persistent notification "Calling..." is displayed.
- Click "End Call" and verify return to `SetupScreen`.
