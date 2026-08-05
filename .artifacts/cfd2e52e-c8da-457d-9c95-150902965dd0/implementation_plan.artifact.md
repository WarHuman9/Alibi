# Implementation Plan - Persistence and DateTime UI

Integrate custom date and time selection into the `SetupScreen` and ensure these timestamps are correctly used in call simulation and call log registration.

## User Review Required

> [!IMPORTANT]
> The `DatePicker` and `TimePicker` will be implemented using Material 3's `DatePickerDialog` and a custom dialog for `TimePicker` (as M3 doesn't have a built-in `TimePickerDialog` in all Compose versions yet, I'll use `TimePicker` inside a `BasicAlertDialog` or similar).

## Proposed Changes

### Telecom Logic

#### [MODIFY] [CallStateManager.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/telecom/CallStateManager.kt)
- Add `_customStartTime` StateFlow.
- Update `onCallAdded` and `setSimulatedCallActive` to prioritize `_customStartTime` over `System.currentTimeMillis()`.
- Ensure `_customStartTime` is reset in `recordCallEnd`.

#### [MODIFY] [TelecomHelper.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/telecom/TelecomHelper.kt)
- Accept `customStartTime: Long?` in `startIncomingCall` and `startOutgoingCall`.
- Pass it as an extra in the `Bundle`.

#### [MODIFY] [SimulatedConnectionService.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/service/SimulatedConnectionService.kt)
- Retrieve `EXTRA_CUSTOM_START_TIME` from `request.extras`.
- Set it in `CallStateManager` before starting the simulated call.

### UI Components

#### [MODIFY] [SetupScreen.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/ui/screens/SetupScreen.kt)
- Add state for date/time selection.
- Implement UI for "Use Custom Start Time" toggle.
- Add Date and Time picker dialogs.
- Update "Start Simulation" and "Register Only" actions to pass the custom timestamp.

## Verification Plan

### Automated Tests
- Build the project using `./gradlew :app:assembleDebug`.
- (Optional) Add unit tests for `CallStateManager` timestamp logic.

### Manual Verification
1. Open the app and navigate to the Setup Screen.
2. Toggle "Use Custom Start Time".
3. Select a date in the past and a specific time.
4. Click "Register Only" and verify the call log entry has the correct timestamp.
5. Click "Start Simulation" and verify the call log entry after disconnect has the correct timestamp.
