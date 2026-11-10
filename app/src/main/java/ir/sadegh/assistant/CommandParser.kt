package ir.sadegh.assistant

sealed class Command {
    data class Call(val name: String) : Command()
    object Unknown : Command()
}

object CommandParser {

    // الگوهای فارسی برای فرمان تماس
    private val callPatterns = listOf(
        Regex("""^(?:صادق\s+)?(?:لطفا\s+)?(?:به\s+)?(.+?)\s+زنگ\s+بزن$"""),
        Regex("""^(?:صادق\s+)?(?:لطفا\s+)?با\s+(.+?)\s+تماس\s+بگیر$"""),
        Regex("""^(?:صادق\s+)?(?:لطفا\s+)?(?:زنگ\s+بزن\s+به|تماس\s+بگیر\s+با)\s+(.+)$"""),
        Regex("""^call\s+(.+)$""", RegexOption.IGNORE_CASE),
    )

    fun parse(raw: String): Command {
        val text = raw.trim()
            .replace('ي', 'ی')   // یای عربی -> فارسی
            .replace('ك', 'ک')   // کاف عربی -> فارسی
        for (p in callPatterns) {
            p.find(text)?.let { return Command.Call(it.groupValues[1].trim()) }
        }
        return Command.Unknown
    }
}
