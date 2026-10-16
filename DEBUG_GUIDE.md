# Debugging Guide - Why Isn't It Solving?

## Check the Logs

The app should show toast notifications for each step. If it's getting stuck, we need to see the logs.

### View Real-Time Logs:

```bash
adb logcat -s SudokuSolver:* SudokuAccessibility:* BoardDetector:* DigitRecognizer:* SudokuSolver:* AutoInputController:*
```

Or simpler:

```bash
adb logcat | grep -E "Sudoku|Board|Digit|Solver"
```

### What You Should See:

**Normal flow:**
```
SudokuAccessibility: Capturing screen...
SudokuAccessibility: Detecting board...
BoardDetector: Found Sudoku board with area: 125000
SudokuAccessibility: Recognizing digits...
DigitRecognizer: Cell (0,0): 5
DigitRecognizer: Cell (0,1): 0
... (81 cells)
SudokuAccessibility: Solving puzzle...
SudokuSolver: Puzzle solved successfully
SudokuAccessibility: Auto-inputting solution...
AutoInputController: Inputting digit 3 at cell (0,1)
...
AutoInputController: Auto-input complete: 45 cells filled
SudokuAccessibility: Puzzle solved!
```

**If it fails:**
```
SudokuAccessibility: Capturing screen...
SudokuAccessibility: Detecting board...
BoardDetector: No Sudoku board detected  ← STUCK HERE
SudokuAccessibility: No Sudoku board detected
```

Or:

```
SudokuAccessibility: Capturing screen...
SudokuAccessibility: Detecting board...
BoardDetector: Found Sudoku board
SudokuAccessibility: Recognizing digits...
DigitRecognizer: Cell (0,0): 0
DigitRecognizer: Cell (0,1): 0
... (all zeros - OCR failed)
SudokuAccessibility: Solving puzzle...
SudokuSolver: Found 0 contradictions
SudokuSolver: Puzzle solved successfully  ← FALSE POSITIVE (empty grid)
```

---

## Common Issues

### Issue 1: Board Not Detected
**Symptoms:** Stops at "Detecting board..."
**Cause:** Sudoku board not clearly visible or wrong format
**Fix:**
- Make sure entire 9x9 grid is visible
- Try zooming the Sudoku app to fill screen
- Use a simple, clean Sudoku app (try Sudoku.com)
- Ensure good contrast (dark grid on light background)

### Issue 2: OCR Fails (All Zeros)
**Symptoms:** Shows "Solving puzzle..." but all cells are 0
**Cause:** ML Kit can't read the digits
**Fix:**
- Increase screen brightness
- Use larger digit size in Sudoku app
- Try different Sudoku apps with clearer fonts
- Ensure digits are black/dark on white/light background

### Issue 3: Solver Fails
**Symptoms:** "Puzzle is unsolvable"
**Cause:** OCR misread some digits, creating invalid puzzle
**Solution:** Check logs for OCR results, might need to improve recognition

### Issue 4: Auto-Input Doesn't Work
**Symptoms:** Solved but doesn't tap
**Cause:** Can't find number pad buttons
**Fix:** Different Sudoku apps have different UI patterns

---

## Quick Debug Steps

1. **Run logcat while solving:**
   ```bash
   adb logcat -s SudokuAccessibility:* BoardDetector:*
   ```

2. **Tap the Solve button**

3. **Watch the output**

4. **Share the logs** so I can see exactly where it fails

---

## Test With Simple Puzzle

Try this:
1. Open Sudoku.com on tablet
2. Start an "Easy" puzzle
3. Make sure the grid fills most of the screen
4. Tap floating Solve button
5. Watch the toast notifications at bottom of screen

**Expected toasts:**
- "Capturing screen..."
- "Detecting board..."
- "Recognizing digits..."
- "Solving puzzle..."
- "Auto-inputting solution..."
- "Puzzle solved!"

**If stuck at any step, check logs!**
