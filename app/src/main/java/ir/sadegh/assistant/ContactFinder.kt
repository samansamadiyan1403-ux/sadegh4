package ir.sadegh.assistant

import android.content.Context
import android.provider.ContactsContract

data class ContactMatch(val name: String, val number: String)

object ContactFinder {

    private fun normalize(s: String) = s.trim()
        .replace('ي', 'ی').replace('ك', 'ک')
        .lowercase()

    /** نزدیک‌ترین مخاطب به اسم گفته‌شده را برمی‌گرداند */
    fun find(context: Context, spoken: String): ContactMatch? {
        val target = normalize(spoken)
        var best: ContactMatch? = null
        var bestScore = 0

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val n = normalize(name)
                val score = when {
                    n == target -> 100
                    n.startsWith(target) -> 80
                    n.contains(target) -> 60
                    target.contains(n) -> 40
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = ContactMatch(name, number)
                }
            }
        }
        return best
    }
}
