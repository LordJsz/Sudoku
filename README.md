# Sudoku Auto-Solver for Android

An Android app that automatically detects, solves, and inputs Sudoku puzzles on your Amazon Fire HD 10 tablet (or any Android device).

## Features

- **Screen Capture**: Uses MediaProjection API to capture the screen in real-time
- **Board Detection**: OpenCV-based contour detection with perspective correction
- **Digit Recognition**: Verified game-font matching with ML Kit/Tesseract fallback
- **MRV Solver**: Efficient constraint satisfaction algorithm
- **Auto-Input**: Accessibility Service for automatic gesture simulation

## Requirements

- Android 9.0 (API 28) or higher
- Fire OS 7+ (for Fire HD 10)
- Android Studio Giraffe or higher
- Java 17

## Project Structure

```
app/src/main/java/com/sudoku/solver/
├── ui/
│   └── MainActivity.kt                 # Permission management UI
├── service/
│   ├── SudokuAccessibilityService.kt   # Main orchestrator
│   ├── ScreenCaptureService.kt         # Screen capture via MediaProjection
│   └── FloatingButtonService.kt        # Overlay trigger button
├── processing/
│   ├── BoardDetector.kt                # OpenCV board detection
│   ├── DigitRecognizer.kt              # ML Kit OCR
│   ├── SudokuSolver.kt                 # MRV algorithm
│   └── AutoInputController.kt          # Gesture automation
├── model/
│   ├── SudokuGrid.kt                   # 9x9 grid data model
│   ├── DetectedBoard.kt                # Board detection result
│   └── CellCoordinate.kt               # Screen coordinate mapping
└── util/
    └── Constants.kt                    # Configuration constants
```

## Build Instructions

### 1. Install Dependencies

Make sure you have:
- Android Studio
- Android SDK (API 28-34)
- Gradle 8.2+

### 2. Build the APK

```bash
cd z:\Admin\Desktop\sudoku\s
.\gradlew assembleDebug
```

The APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`

### 3. Install on Fire HD 10

#### Enable Developer Options:
1. Go to Settings → Device Options
2. Tap "Serial Number" 7 times
3. Go back to Settings → Developer Options
4. Enable "ADB Debugging"

#### Install via ADB:

```bash
# Connect to tablet via USB
adb devices

# Install the APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or for wireless installation:

```bash
# Get tablet IP from Settings → Device Options → About Fire Tablet
adb connect <tablet-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

### Setup (One-time)

1. Open the app on your tablet
2. Click "Enable Accessibility Service"
   - Go to Settings → Accessibility → Sudoku Solver
   - Toggle ON
3. Click "Enable Overlay Permission"
   - Toggle "Allow display over other apps"

### Solving Puzzles

1. Open any Sudoku app on your tablet
2. A floating "Solve" button will appear
3. Drag it to a convenient location
4. Make sure the entire Sudoku board is visible on screen
5. Tap the "Solve" button
6. Wait while the app:
   - Captures the screen
   - Detects the board
   - Recognizes digits
   - Solves the puzzle
   - Auto-inputs the solution

## Fire OS Compatibility

This app is specifically designed for Amazon Fire HD 10 tablets:

- **Fire OS 7** (Android 9 base) - Fully supported
- **Fire OS 8** (Android 11 base) - Fully supported
- **No Google Play Services required** - ML Kit uses on-device model

## Troubleshooting

### "No Sudoku board detected"
- Ensure the entire 9x9 grid is visible on screen
- Try adjusting brightness and contrast
- Make sure the grid has clear borders

### "Failed to capture screen"
- Restart the app
- Re-enable Accessibility Service in Settings

### Auto-input not working
- Some Sudoku apps use different UI patterns
- The app looks for number buttons after tapping a cell
- Try different Sudoku apps (recommended: Sudoku.com, Andoku)

## Performance

Huawei JAD-LX9 input tuning (v54, 2026-10-13):

| Mode | Live input result |
| --- | --- |
| 9x9 solo | 31 entries in about 2.5 seconds; 100% accuracy, no hints |
| 16x16 | 126 entries in 13.4 seconds; 100% accuracy, no hints |
| 9x9 battle | 39 entries in about 3.5 seconds; battle won in 7 seconds total |

These are individual phone runs, not guaranteed puzzle completion times. Input
timings exclude OCR except where total game time is stated. The previous 16x16
build entered 125 cells in about 37 seconds on this phone, giving roughly 2.8x
the entry throughput after tuning. Logs and screenshots are summarized in
`diagnostics/HUAWEI_SPEED_V54.md`.

The phone uses 1 ms taps with 32 ms selection/value settling, cached keypad
coordinates, and verification in batches of sixteen. Zero-delay and combined
cell/key gestures caused incorrect entries in live tests and are not used by
the Huawei profile. As of v56 (2026-10-14), the Huawei JAD-LX9 uses this fixed
profile for solo, battle, and 16x16. It ignores accumulated slow timing
preferences, and missed entries no longer increase future delays. Verification
still stops input for wrong or missing values so recovery can run.

Expected timing on Fire HD 10:
- Screen capture: < 500ms
- Board detection: < 1s
- OCR (81 cells): < 3s
- Solving: < 2s (hard puzzles)
- Auto-input: 60-90s (with delays for stability)

**Total time: ~2-3 minutes** from button tap to completed puzzle

## Tested Sudoku Apps

- ✅ Sudoku.com
- ✅ Andoku
- ✅ Enjoy Sudoku
- ✅ Web-based Sudoku (browser)

## Technical Details

### Algorithms

**Board Detection:**
1. Grayscale conversion
2. Gaussian blur
3. Adaptive thresholding
4. Morphological closing
5. Contour detection
6. 4-point perspective transform

**OCR Pipeline:**
1. Grid line removal
2. Cell extraction (9x9)
3. White pixel counting for empty detection
4. 14% margin cropping
5. Normalize the main glyph and compare against verified game-font references
6. Use ML Kit and selective Tesseract for glyphs outside strict match thresholds
7. Recover unread clues from original and smaller whole-board images
8. Require a unique solution that preserves scanned clues before automatic input

Version 57 fixes the Fire HD 10 board that left a printed 1 and 5 unread.
All 50 clues matched the screenshot transcription after the change. The live
puzzle completed with 100% accuracy, zero mistakes, and zero hints. See
`diagnostics/OCR_V57.md` for measurements, reference provenance, and test scope.

**MRV Solver:**
1. Find empty cell with minimum remaining values
2. Try each valid candidate
3. Recursive backtracking
4. Constraint propagation

## Credits

Ported from Python implementation by the user, featuring:
- OpenCV for computer vision
- ML Kit for OCR (replaces Tesseract)
- MRV constraint satisfaction solver

## License

This project is for personal use only.
