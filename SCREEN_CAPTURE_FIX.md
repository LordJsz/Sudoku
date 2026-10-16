# Screen Capture Permission Fix

## Problem
App showed "Failed to capture screen" error when tapping the Solve button.

## Root Cause
The `ScreenCaptureService` wasn't started because it requires MediaProjection permission, which wasn't being requested.

## Solution

### Changes Made:

**1. MainActivity.kt - Added MediaProjection Permission Flow**

Added imports:
```kotlin
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import com.sudoku.solver.service.ScreenCaptureService
```

Added permission request:
- When user taps "Start Solving", app now requests screen capture permission
- On permission grant, starts both ScreenCaptureService and FloatingButtonService
- Shows toast notification confirming setup

**2. ScreenCaptureService.kt - Fixed Singleton Pattern**

- Moved `instance = this` from `init{}` to `onCreate()`
- Added `instance = null` in `onDestroy()`
- Ensures proper lifecycle management

---

## How It Works Now

### New Flow:

1. **User taps "Start Solving"** in MainActivity

2. **System shows permission dialog**
   - "Sudoku Solver wants to start capturing everything on your screen"
   - User taps "Start now"

3. **App starts services**
   - ScreenCaptureService (with capture permission)
   - FloatingButtonService (overlay button)

4. **User can now solve puzzles**
   - Floating "Solve" button appears
   - Tap it to auto-solve Sudoku
   - Screen capture works correctly

---

## Testing the Fix

### Rebuild and Install:

1. **Clean & Rebuild**
   ```
   Build → Clean Project
   Build → Rebuild Project
   ```

2. **Build APK**
   ```
   Build → Build APK
   ```

3. **Install on Tablet**
   ```bash
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

### Test Steps:

1. **Open app on tablet**

2. **Grant permissions** (if not already done)
   - Enable Accessibility Service
   - Enable Overlay Permission

3. **Tap "Start Solving"**
   - **NEW**: Permission dialog appears
   - Tap "Start now"
   - Toast: "Screen capture enabled! Tap the floating button to solve."
   - Floating button appears

4. **Open Sudoku app**
   - Any Sudoku app (e.g., Sudoku.com)
   - Start a puzzle

5. **Tap floating "Solve" button**
   - Should show "Capturing screen..." ✅
   - No more "Failed to capture screen" error
   - Proceeds to detect board, OCR, solve, auto-input

---

## Expected Behavior

**Before Fix:**
```
Tap Solve → "Failed to capture screen" ❌
```

**After Fix:**
```
Tap Solve → "Capturing screen..." ✅
         → "Detecting board..." ✅
         → "Recognizing digits..." ✅
         → "Solving puzzle..." ✅
         → "Auto-inputting solution..." ✅
         → "Puzzle solved!" ✅
```

---

## Permission Dialog

When tapping "Start Solving", you'll see:

```
┌─────────────────────────────────────┐
│  Sudoku Solver wants to start       │
│  capturing everything that's         │
│  displayed on your screen.          │
│                                      │
│  [Cancel]  [Start now]              │
└─────────────────────────────────────┘
```

**Important:** You must tap "Start now" for screen capture to work!

---

## Troubleshooting

### "Permission denied" error
- Tap "Start Solving" again
- Make sure to tap "Start now" in the dialog

### "ScreenCaptureService not running"
- Force stop the app
- Reopen and tap "Start Solving"
- Grant the permission

### Floating button doesn't appear
- Check Overlay permission is enabled
- Restart the app

---

## Files Modified

1. **MainActivity.kt**
   - Added MediaProjection permission request
   - Added `onActivityResult()` to handle permission
   - Starts ScreenCaptureService with permission

2. **ScreenCaptureService.kt**
   - Fixed singleton instance management
   - Proper lifecycle in onCreate/onDestroy

---

**Status: Ready to test!** Rebuild and install the updated APK.
