version = 22

cloudstream {
    // ★ ٢٠٢٦-١٠-١٧: كان «أكثر من ٢٠» وهو حدٌّ أدنى كاذب — القائمة المرجعية
    // /ar/sources فيها ٢٤ منصّة بالضبط، والجدول المنشور يُعاد توليده من هنا في
    // CI (فلا ينفع تعديل plugins.json يدويًّا: يُدهس في الدفعة التالية).
    description = "DramaTip - دراما قصيرة من ٢٤ منصة"
    authors = listOf("hamedhani1998")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "TvSeries",
        "Movie"
    )
}