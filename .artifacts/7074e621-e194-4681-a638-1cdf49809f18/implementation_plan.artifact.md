# Optimization for Startup and Screen Transitions

This plan aims to reduce the observed lag during app bootup and screen transitions by optimizing state collection, navigation stable-ness, and deferred rendering of expensive UI components.

## User Review Required

> [!NOTE]
> These changes are primarily under-the-hood optimizations and should not change the app's visual behavior, except for making it smoother.

## Proposed Changes

### [Component] UI Framework & Navigation

#### [MODIFY] [MainActivity.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/MainActivity.kt)
- Switch to `collectAsStateWithLifecycle()` for better performance and lifecycle safety.
- `remember` the `entryProvider` lambda for `NavDisplay` to ensure navigation mapping is stable across recompositions.

#### [MODIFY] [ActiveCallScreen.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/ui/screens/ActiveCallScreen.kt)
- Extract the pulsing animation into a dedicated sub-composable (`PulsingAvatar`).
- Conditionally compose `PulsingAvatar` only when the call state is `RINGING` or `DIALING`, preventing the animation loop from running during an active call.

#### [MODIFY] [SetupScreen.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/ui/screens/SetupScreen.kt)
- Call `ReportDrawnWhen` or `reportFullyDrawn()` once the main setup content is displayed to assist the system's startup optimization.

## Verification Plan

### Automated Tests
- Run `app:assembleDebug` to ensure no build regressions.

### Manual Verification
- Monitor Logcat for `Choreographer: Skipped ... frames!` messages during startup and screen transitions.
- Visually verify that screen transitions between "Setup" and "Active Call" are smoother.
- Ensure the pulsing animation still works correctly during the ringing/dialing phase.
