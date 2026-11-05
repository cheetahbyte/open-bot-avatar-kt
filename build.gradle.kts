plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "re.leob.openbotavatar.kt"
    version = "0.1.0"
}
