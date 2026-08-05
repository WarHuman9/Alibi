# Task 11: Optimization and Pruning

This task focuses on cleaning up the project by removing unused dependencies, enabling code shrinking (R8), and optimizing call logging operations to run asynchronously on the IO dispatcher.

## Proposed Changes

### Build Configuration

#### [MODIFY] [libs.versions.toml](file:///C:/Users/santo/AndroidStudioProjects/Alibi/gradle/libs.versions.toml)
- Remove unused library versions and definitions:
    - CameraX
    - Room
    - Retrofit & Moshi
    - OkHttp & Logging Interceptor
    - DataStore
    - Play Services Location
    - Accompanist Permissions
    - Coil

#### [MODIFY] [build.gradle.kts](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/build.gradle.kts)
- Remove unused dependencies corresponding to the pruned libraries.
- Remove KSP and Serialization plugins.
- Enable `minifyEnabled` and `shrinkResources` for both `release` and `debug` build types.

---

### Logic Optimization

#### [MODIFY] [CallLogHelper.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/util/CallLogHelper.kt)
- Update `insertCallLog` to perform database operations inside `CoroutineScope(Dispatchers.IO).launch`.
- Consider making it a singleton-like utility if needed, or keeping it as is but ensuring async execution.

#### [MODIFY] [CallStateManager.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/telecom/CallStateManager.kt)
- Centralize state management for `startTime` and `answerTime`.
- Ensure `recordCallEnd` is efficient and clean.

#### [MODIFY] [SimulatedConnection.kt](file:///C:/Users/santo/AndroidStudioProjects/Alibi/app/src/main/java/com/example/alibi/service/SimulatedConnection.kt)
- Review interaction with `CallStateManager` to ensure consistency in state updates.

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to ensure the project builds with shrinking enabled and pruned dependencies.
- Run unit tests if any exist that cover call state logic.

### Manual Verification
- Verify that call logging still works by triggering a simulated call and checking if it's recorded in the system call log (if permissions allow on the test device).
- Check logs for any async-related issues.
