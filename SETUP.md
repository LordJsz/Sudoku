# Setup Guide for Sudoku Auto-Solver

## Prerequisites

You need Android Studio to build this Android app. The project is ready to open in Android Studio, which will automatically download all required dependencies.

### Step 1: Install Android Studio

If you don't have Android Studio installed:

1. Download from: https://developer.android.com/studio
2. Install with default settings
3. During first run, it will download the Android SDK

### Step 2: Open Project in Android Studio

1. Launch Android Studio
2. Click "Open" (not "New Project")
3. Navigate to: `z:\Admin\Desktop\sudoku\s`
4. Click "OK"

Android Studio will automatically:
- Download Gradle wrapper (gradle-wrapper.jar)
- Download all dependencies (OpenCV, ML Kit, AndroidX)
- Index the project
- This may take 5-10 minutes on first open

### Step 3: Sync Gradle

If Android Studio doesn't automatically sync:
1. Click "File" → "Sync Project with Gradle Files"
2. Wait for the sync to complete (check bottom status bar)

### Step 4: Build the APK

**Option A: Using Android Studio UI**
1. Click "Build" → "Build Bundle(s) / APK(s)" → "Build APK(s)"
2. Wait for build to complete
3. Click "locate" in the popup to find the APK
4. APK location: `app\build\outputs\apk\debug\app-debug.apk`

**Option B: Using Terminal in Android Studio**
1. Open Terminal (bottom panel)
2. Run: `.\gradlew assembleDebug`
3. APK will be at: `app\build\outputs\apk\debug\app-debug.apk`

### Step 5: Prepare Your Fire HD 10 Tablet

#### Enable Developer Options:
1. Open Settings → Device Options (or About Tablet)
2. Tap "Serial Number" **7 times**
3. You'll see "You are now a developer!"

#### Enable ADB Debugging:
1. Go back to Settings
2. Open "Developer Options"
3. Toggle ON "ADB Debugging"
4. Toggle ON "Apps from Unknown Sources" (if available)

#### Get Tablet IP Address (for wireless):
1. Settings → Wi-Fi
2. Tap on your connected network
3. Note the IP address (e.g., 192.168.1.100)

### Step 6: Install APK on Tablet

**Option A: USB Installation (Recommended)**
1. Connect Fire tablet to PC via USB
2. Accept "Allow USB debugging" popup on tablet
3. In Android Studio terminal:
   ```bash
   adb devices
   # Should show your device

   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

**Option B: Wireless ADB Installation**
1. Connect tablet and PC to same Wi-Fi
2. In Android Studio terminal:
   ```bash
   adb connect <tablet-ip>:5555
   # Example: adb connect 192.168.1.100:5555

   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

**Option C: Direct File Transfer**
1. Copy `app-debug.apk` to your tablet (via USB or cloud storage)
2. On tablet, use File Manager to locate the APK
3. Tap the APK file
4. Tap "Install"
5. If blocked, go to Settings → Security → "Apps from Unknown Sources" → Enable

### Step 7: Grant Permissions

**First Time Setup:**
1. Open "Sudoku Solver" app on tablet
2. Click "Enable Accessibility Service"
   - Opens Settings → Accessibility
   - Find "Sudoku Solver" in the list
   - Toggle it ON
   - Confirm the warning dialog
   - Press back to return to app
3. Click "Enable Overlay Permission"
   - Opens Settings → Display over other apps
   - Find "Sudoku Solver"
   - Toggle it ON
   - Press back to return to app
4. Click "Start Solving" button
   - A floating "Solve" button should appear
   - You can drag it anywhere on screen

### Step 8: Test the App

1. Open any Sudoku app (recommended: Sudoku.com from Amazon Appstore)
2. Start a new puzzle
3. Make sure the entire 9x9 grid is visible
4. Tap the floating "Solve" button
5. Grant Screen Capture permission (one-time popup)
6. Watch the app:
   - Capture screen (toast notification)
   - Detect board (toast notification)
   - Recognize digits (toast notification)
   - Solve puzzle (toast notification)
   - Auto-input solution (toast notification)
   - "Puzzle solved!" (toast notification)

## Troubleshooting

### "Gradle sync failed"
- Check internet connection
- Click "File" → "Invalidate Caches" → Restart
- Delete `.gradle` and `build` folders, then sync again

### "SDK not found"
- In Android Studio: Tools → SDK Manager
- Install "Android 14.0 (API 34)" and "Android 9.0 (API 28)"
- Install "Android SDK Build-Tools"

### "Cannot resolve symbol OpenCV"
- The OpenCV dependency might take time to download
- Check `build.gradle` has: `implementation("org.opencv:opencv:4.9.0")`
- Try "File" → "Sync Project with Gradle Files"

### "ADB device not found"
- On tablet: Revoke and re-allow USB debugging (Developer Options)
- Unplug/replug USB cable
- Try different USB port or cable
- Check `adb devices` shows device

### "Installation failed"
- Uninstall old version: `adb uninstall com.sudoku.solver`
- Then reinstall: `adb install -r app-debug.apk`

### "App crashes on launch"
- Check logcat: `adb logcat | grep SudokuSolver`
- Ensure OpenCV loaded: Look for "OpenCV loaded successfully" in logs

### "No Sudoku board detected"
- Ensure entire 9x9 grid is visible and clear
- Try increasing screen brightness
- Test with different Sudoku apps
- Some puzzle styles (colored, artistic) may not work well

### "Auto-input not working"
- Accessibility service must be enabled
- Different Sudoku apps have different UI patterns
- The app searches for number buttons after tapping cells
- Try these apps: Sudoku.com, Andoku, Enjoy Sudoku

## Performance Notes

**Expected timing on Fire HD 10:**
- Screen capture: ~500ms
- Board detection: ~1s
- OCR 81 cells: ~3s
- Solving: ~1-2s
- Auto-input: ~60-90s (includes delays)

**Total time: ~2-3 minutes**

## Recommended Sudoku Apps

These apps work best with the auto-solver:

1. **Sudoku.com** (Amazon Appstore)
   - Clean grid design
   - Standard number pad
   - Best compatibility

2. **Andoku** (sideload APK)
   - Simple interface
   - Good OCR accuracy

3. **Web-based Sudoku**
   - Open in Silk browser
   - Full-screen mode
   - Works well with detection

## Next Steps

- Test with different difficulty levels
- Try various Sudoku apps
- Adjust tap delays in Constants.kt if needed
- Monitor battery usage (screen capture is intensive)

## Support

If you encounter issues:
1. Check logcat: `adb logcat -s SudokuSolver:*`
2. Look for error messages
3. Try rebuilding: `.\gradlew clean assembleDebug`

---

**Your app is ready to build!** Follow the steps above to get it running on your Fire HD 10 tablet.
