package com.ayush.ascend.core

import com.ayush.ascend.data.MoodEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeeklyLogicTest {
    @Test fun timeCost() {
        assertEquals("6 books or 1 full course", TimeCost.line(31 * 60))
        assertEquals("3 gym sessions or 1 movie", TimeCost.line(180))
        assertEquals("3 km walked", TimeCost.line(45))
        assertEquals("", TimeCost.line(5))
    }

    @Test fun grade() {
        assertEquals("A+", Report.grade(7, 1.0, -20, -20))
        assertEquals("F", Report.grade(0, 0.0, 30, 30))
        assertEquals("C", Report.grade(4, 0.5, 0, 0))   // 20 + 15 + 10 + 7.5 = 52.5
    }

    @Test fun mood() {
        val e = List(7) { MoodEntry(pkg = "ig", ts = 0, mood = -1, sessionMinutes = 5) } +
            List(3) { MoodEntry(pkg = "ig", ts = 0, mood = 1, sessionMinutes = 5) }
        assertEquals("ig leaves you 😞 70% of the time", Mood.highlight(Mood.byApp(e)) { it })
        assertNull(Mood.highlight(Mood.byApp(e.take(4))) { it })
    }
}
