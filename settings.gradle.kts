rootProject.name = "CloudstreamPlugins"


// ShortDramaAR: إضافة قيد الإنشاء (18:46) لم تكتمل بعد، وبما أن هذا الملف
// يضمّ كل مجلد فيه build.gradle.kts فإن فشلها يُسقط بناء كل الإضافات.
// معطّلة مؤقتاً — يكفي حذف اسمها من القائمة لإكمال بناءها.
val disabled = listOf<String>("ShortDramaAR")

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
