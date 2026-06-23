// build.gradle.kts (PROJECT level / top of the repo)
// -------------------------------------------------------------------
// This file declares the plugins available to all modules but does
// NOT apply them here ("apply false"). Each module (the :app module)
// decides which ones to actually turn on. Keeping versions in one
// place avoids version mismatches between modules.
// -------------------------------------------------------------------

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
