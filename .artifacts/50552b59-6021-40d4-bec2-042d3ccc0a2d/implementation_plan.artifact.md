# Implementation Plan - Task 7: Timing and Interaction

Update the call handling logic and UI to track initiation and answer times separately, and add support for manual answering and auto-answer delay for outgoing calls.

## User Review Required

> [!IMPORTANT]
> The "Auto-Answer Delay" will be added to the `SetupScreen` and will apply specifically to outgoing calls to simulate the time it takes for the other party to answer.

## Proposed Changes

### Telecom Logic

#### [MODIFY] [CallStateManager.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/telecom/CallStateManager.kt)
- Update `startTime` and `answerTime` to be exposed as `StateFlow<Long>`.
- Update `onCallAdded` to record `startTime`.
- Update `onStateChanged` (inside `callCallback`) to record `answerTime` when state becomes `STATE_ACTIVE`.
- Add `answer()` and `onAnswerRequested` to allow manual answering of calls.

#### [MODIFY] [SimulatedConnection.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/service/SimulatedConnection.kt)
- Update `init` block to observe `CallStateManager.onAnswerRequested`.
- Add a coroutine scope to handle the auto-answer delay for outgoing calls.
- In `onAnswer()`, ensure timestamps are updated.

#### [MODIFY] [SimulatedConnectionService.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/service/SimulatedConnectionService.kt)
- Update `onCreateOutgoingConnection` to remain in `DIALING` state and pass the `autoAnswerDelay` to the connection.
- Ensure `CallStateManager` is updated with `STATE_DIALING` for outgoing calls initially.

#### [MODIFY] [TelecomHelper.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/telecom/TelecomHelper.kt)
- Update `startOutgoingCall` to accept an `autoAnswerDelay` parameter and pass it via extras.

### UI

#### [MODIFY] [SetupScreen.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/ui/screens/SetupScreen.kt)
- Add an `OutlinedTextField` for "Auto-Answer Delay (seconds)".
- Pass the delay to `telecomHelper.startOutgoingCall`.

#### [MODIFY] [ActiveCallScreen.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/ui/screens/ActiveCallScreen.kt)
- Collect `answerTime` from `CallStateManager`.
- Update the timer logic to calculate duration based on `answerTime` instead of a local counter.
- Show an "Answer" button (Rounded Check icon) when `callState == Call.STATE_RINGING`.
- Display "Calling..." for `STATE_DIALING`.

## Verification Plan

### Automated Tests
- N/A (Manual verification on device/emulator preferred for Telecom APIs)

### Manual Verification
1. Open the app and go to the Setup screen.
2. Enter a phone number and set "Auto-Answer Delay" to 5 seconds.
3. Start an Outgoing call. Verify the screen shows "Calling..." for 5 seconds before transitioning to "Active Call" and starting the timer.
4. Start an Incoming call. Verify the screen shows an "Answer" button.
5. Click "Answer" and verify the call transitions to "Active Call" and the timer starts.
6. End the calls and verify the Call Log has the correct duration (conversation only).
