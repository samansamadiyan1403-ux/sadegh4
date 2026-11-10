package ir.sadegh.assistant

sealed class Command {
    data class Call(val name: String) : Command()
    object Unknown : Command()
}

object CommandParser {

    // کلماتی که نشان می‌دهند کاربر تماس می‌خواهد
    private val callWords = setOf("زنگ", "تماس", "کال", "call", "dial", "phone")

    // کلمات اضافه که جزو اسم مخاطب نیستند
    private val fillerWords = setOf(
        "صادق", "لطفا", "لطفن", "بزن", "بزنید", "بزنین", "بزنی", "بزنم", "بگیر", "بگیرید", "بگیرین", "بگیری", "بگیرم",
        "به", "با", "برای", "برام", "برایم", "من", "رو", "را", "کن", "کنید",
        "میشه", "می", "شه", "میتونی", "میتوانی", "تونی", "توانی", "یه", "یک", "بار",
        "to", "please", "a"
    )

    private fun normalize(raw: String): String = raw
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace(Regex("[\\u064B-\\u065F\\u0640]"), "")   // اعراب و کشیده
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")        // علامت‌ها و نیم‌فاصله
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

    fun parse(raw: String): Command {
        val tokens = normalize(raw).split(" ").filter { it.isNotBlank() }
        if (tokens.none { it in callWords }) return Command.Unknown

        val name = tokens
            .filter { it !in callWords && it !in fillerWords }
            .joinToString(" ")

        return if (name.isNotBlank()) Command.Call(name) else Command.Unknown
    }
}
