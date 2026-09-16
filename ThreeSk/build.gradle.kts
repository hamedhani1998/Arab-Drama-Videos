plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.threesk.plugin"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

version = 8

cloudstream {
    description = "قصة عشق - مسلسلات تركية مترجمة ومدبلجة"
    authors = listOf("hamedhani1998")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "ar"
}
