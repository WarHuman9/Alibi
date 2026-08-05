# Walkthrough - Persistence and DateTime UI

I have implemented custom date and time selection for call simulations and log registrations.

## Changes Made

### 1. Enhanced Setup Screen UI
- Added a "Use Custom Start Time" toggle to the `SetupScreen`.
- Integrated Material 3 `DatePicker` and `TimePicker` components.
- The UI now allows users to pick a specific date and time for the call to be logged at.
- Added visual feedback for the selected date and time.

### 2. Telecom Logic Updates
- **`CallStateManager`**: Now supports a `customStartTime`. If provided, it overrides `System.currentTimeMillis()` when a call starts, ensuring the call log entry uses the user-specified timestamp.
- **`TelecomHelper`**: Updated to accept `customStartTime` in both `startIncomingCall` and `startOutgoingCall` and pass it through to the `ConnectionService`.
- **`SimulatedConnectionService`**: Retrieves the custom timestamp from extras and sets it in the `CallStateManager`.

### 3. Call Log Integration
- Both simulated calls and "Register Only" entries now respect the custom timestamp if provided.
- For "Register Only", the timestamp is used directly as the call's start time.
- For simulated calls, the `CallStateManager` ensures the `DATE` field in the system call log matches the custom start time.

## Verification Results

### Automated Tests
- Successfully built the application using `./gradlew :app:assembleDebug`.

### Manual Verification Steps (Suggested)
1. Open the Alibi app.
2. On the Setup Screen, enter a phone number.
3. Toggle "Use Custom Start Time".
4. Select a date (e.g., yesterday) and a time.
5. Click "Register Only".
6. Check the system's Phone app call history. The entry should appear with the selected date and time.
7. Repeat with "Start Simulation" (requires default dialer role). After the call ends, verify the call log entry.

## Screenshots/UI Preview

The `SetupScreen` now includes a dedicated section for custom timing:

```kotlin
// UI Preview snippet
Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
) {
    Text("Use Custom Start Time")
    Switch(checked = useCustomStartTime, onCheckedChange = { ... })
}
```

![Setup Screen with Custom Time](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/ui/screens/SetupScreen.kt)
