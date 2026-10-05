version = 1

// Shared Narto playback code, compiled INTO this module (not a dependency).
// ../narto-shared has no build.gradle.kts, so settings.gradle.kts never treats it
// as a project of its own — it only ever exists as extra source for the two Narto
// sources. Their packages stay separate (com.nartodrama.plugin / com.nartoedge.plugin),
// so nothing collides when CloudStream loads both .cs3 files at once.
android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("narto-shared/src/main/java"))
}

cloudstream {
    description = "Edge Narto Drama - مسلسلات دراما مترجمة"
    authors = listOf("hamedhani1998")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "TvSeries",
        "Movie"
    )
}
