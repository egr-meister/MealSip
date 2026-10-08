# MealSip has no reflection-based code of its own.
# Room, DataStore, Compose, Navigation and AndroidX ship their own consumer rules.
# Receivers and the Application/Activity are referenced from the manifest and kept by AGP.

# Readable stack traces with the preserved mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
