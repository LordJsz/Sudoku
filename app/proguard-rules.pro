# Add project specific ProGuard rules here.

# OpenCV
-keep class org.opencv.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# Models
-keep class com.sudoku.solver.model.** { *; }

# Accessibility
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
