package com.ayush.ascend.core

/** What screen time could have been instead: "31 h = 6 books or 1 full course". */
object TimeCost {
    private class Thing(val minutes: Int, val one: String, val many: String)

    // biggest first: bigger units give smaller, more tangible counts
    private val things = listOf(
        Thing(20 * 60, "1 full course", "%d online courses"),
        Thing(5 * 60, "1 book", "%d books"),
        Thing(2 * 60, "1 movie", "%d movies"),
        Thing(60, "1 gym session", "%d gym sessions"),
        Thing(60, "1 hour of sleep", "%d hours of sleep"),
        Thing(12, "1 km walked", "%d km walked"),
    )

    /** Up to [max] equivalents whose count lands between 1 and 40, smallest unit first ("6 books or 1 full course"). */
    fun equivalents(minutes: Int, max: Int = 2): List<String> =
        things.mapNotNull { t -> (minutes / t.minutes).takeIf { it in 1..40 }?.let { t to it } }
            .take(max)
            .reversed()
            .map { (t, n) -> if (n == 1) t.one else t.many.format(n) }

    fun line(minutes: Int, max: Int = 2): String = equivalents(minutes, max).joinToString(" or ")
}
