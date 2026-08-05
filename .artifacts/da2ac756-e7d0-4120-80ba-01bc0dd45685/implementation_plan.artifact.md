# Task 2: Setup UI and Role Management Implementation Plan

This task involves creating the initial UI for the app, handling the default dialer role request, and setting up the Navigation 3 architecture.

## Proposed Changes

### [Navigation & UI Structure]

#### [NEW] [NavRoutes.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/ui/NavRoutes.kt)
Define serializable routes for the application using Navigation 3.
- `SetupRoute`: Initial screen for inputting the phone number and requesting roles.
- `ActiveCallRoute`: Placeholder screen for when a call is "active".

#### [MODIFY] [MainActivity.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/MainActivity.kt)
Update `MainActivity` to host the Navigation 3 `NavDisplay`. It will manage the backstack and provide the entry point for role management.

### [Screens]

#### [NEW] [SetupScreen.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/ui/screens/SetupScreen.kt)
Implement the Setup UI:
- `TextField` for entering the target phone number.
- `Button` to submit and trigger the `ROLE_DIALER` request.
- Logic to check if the app is already the default dialer.

#### [NEW] [ActiveCallScreen.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/ui/screens/ActiveCallScreen.kt)
A simple placeholder screen that displays the active call status and allows navigating back to Setup.

### [Role Management]

#### [NEW] [RoleHelper.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/util/RoleHelper.kt)
Utility class to handle `RoleManager` (or `TelecomManager` for legacy) logic for requesting the default dialer role.

## Verification Plan

### Automated Tests
- Build the project using `./gradlew :app:assembleDebug`.
- Run unit tests (if any) using `./gradlew :app:testDebugUnitTest`.

### Manual Verification
- Launch the app.
- Enter a phone number.
- Click Submit.
- Verify that the system "Set default dialer" dialog appears.
- Verify navigation to the placeholder Active Call screen.
