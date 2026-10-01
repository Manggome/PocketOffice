package kr.neptune.pocketoffice.core

import java.util.Locale

/** 화면에서 문서를 묶어 보여 주는 단위 */
enum class DocKind(val label: String) {
    WORD("문서"),
    SHEET("스프레드시트"),
    SLIDE("프레젠테이션"),
    PDF("PDF"),
}

/**
 * 편집기가 여는 형식 하나.
 *
 * @param saveExt 편집한 결과를 저장할 형식. 구형 바이너리(doc/xls/ppt)는 엔진이 다시 쓰지 못해
 *   OOXML 쪽(docx/xlsx/pptx)으로 바뀐다. 이 경우 원본 위에 덮어쓸 수 없으니 새 파일로 저장한다.
 */
enum class DocFormat(
    val ext: String,
    val kind: DocKind,
    val mime: String,
    val saveExt: String = ext,
) {
    DOCX("docx", DocKind.WORD, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    DOC("doc", DocKind.WORD, "application/msword", saveExt = "docx"),
    ODT("odt", DocKind.WORD, "application/vnd.oasis.opendocument.text"),
    RTF("rtf", DocKind.WORD, "application/rtf"),
    TXT("txt", DocKind.WORD, "text/plain"),

    XLSX("xlsx", DocKind.SHEET, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    XLS("xls", DocKind.SHEET, "application/vnd.ms-excel", saveExt = "xlsx"),
    ODS("ods", DocKind.SHEET, "application/vnd.oasis.opendocument.spreadsheet"),
    CSV("csv", DocKind.SHEET, "text/csv"),

    PPTX("pptx", DocKind.SLIDE, "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    PPT("ppt", DocKind.SLIDE, "application/vnd.ms-powerpoint", saveExt = "pptx"),
    ODP("odp", DocKind.SLIDE, "application/vnd.oasis.opendocument.presentation"),

    PDF("pdf", DocKind.PDF, "application/pdf");

    /** 저장하면 형식이 바뀌어 원본에 덮어쓸 수 없는가 */
    val convertsOnSave: Boolean get() = saveExt != ext

    companion object {
        private val byExt = entries.associateBy { it.ext }

        // 앱마다 같은 형식에 다른 MIME 을 붙여 보낸다. 대표값 외의 것도 받아 준다
        private val byMime: Map<String, DocFormat> = buildMap {
            DocFormat.entries.forEach { put(it.mime, it) }
            put("text/rtf", RTF)
            put("text/comma-separated-values", CSV)
            put("application/csv", CSV)
            put("application/vnd.ms-word", DOC)
            put("application/x-pdf", PDF)
        }

        fun fromExt(ext: String?): DocFormat? = ext?.lowercase(Locale.ROOT)?.let { byExt[it] }

        fun fromMime(mime: String?): DocFormat? = mime?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()?.let { byMime[it] }

        fun fromName(name: String?): DocFormat? = fromExt(name?.substringAfterLast('.', ""))

        /** 파일 이름이 우선, 이름에 확장자가 없으면 MIME 으로 */
        fun detect(name: String?, mime: String?): DocFormat? = fromName(name) ?: fromMime(mime)

        /** 폰 전체 문서 목록에 보여 줄 형식. txt 는 너무 많고 다른 앱이 더 낫다 */
        val listed: List<DocFormat> = entries.filter { it != TXT }

        /** 파일 열기 화면에 넘길 MIME 목록 */
        val pickerMimes: Array<String> = (entries.map { it.mime } + listOf("text/comma-separated-values", "text/rtf")).distinct().toTypedArray()
    }
}

/** "보고서.doc" -> "보고서" */
fun baseName(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot > 0) fileName.substring(0, dot) else fileName
}

/** 이름의 확장자를 바꾼다. "보고서.doc", "docx" -> "보고서.docx" */
fun withExt(fileName: String, ext: String): String = baseName(fileName) + "." + ext
