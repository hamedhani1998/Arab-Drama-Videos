rootProject.name = "CloudstreamPlugins"


// ShortDramaAR: إضافة قيد الإنشاء محلياً (غير متتبَّعة بـgit، فلا تدخل
// بناء GitHub). أضف اسمها هنا فقط إن أردتَ بناءها مع البقية محلياً.
val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
