package com.taskrx.core.sync

import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.IntervalTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.WeeklyTimes
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun read(json: String) = scheduleFromServer(Json.parseToJsonElement(json))

class ScheduleFromServerTest {
    @Test
    fun `reads a daily schedule and zero-pads its times`() {
        assertEquals(DailyTimes(listOf("08:00", "20:30")), read("""{"type":"daily","times":["8:00","20:30"]}"""))
        assertEquals(DailyTimes(emptyList()), read("""{"type":"daily","times":[]}"""))
    }

    @Test
    fun `reads float64 spellings of whole numbers`() {
        assertEquals(IntervalTimes(6, 1_700_000_000_000), read("""{"type":"interval","hours":6.0,"startAt":1700000000000.0}"""))
        assertEquals(OnceTimes(1_700_000_000_000), read("""{"type":"once","at":1.7E12}"""))
        assertEquals(WeeklyTimes(listOf(0, 6), listOf("07:00")), read("""{"type":"weekly","daysOfWeek":[0.0,6],"times":["07:00"]}"""))
    }

    @Test
    fun `reads none`() {
        assertEquals(NoneTimes, read("""{"type":"none"}"""))
    }

    @Test
    fun `rejects what it cannot read`() {
        assertNull(scheduleFromServer(null))
        assertNull(read("[]"))
        assertNull(read("""{"type":"yearly"}"""))
        assertNull(read("""{"times":[]}"""))
        // daily
        assertNull(read("""{"type":"daily"}"""))
        assertNull(read("""{"type":"daily","times":[5]}"""))
        assertNull(read("""{"type":"daily","times":["noon"]}"""))
        assertNull(read("""{"type":"daily","times":["24:00"]}"""))
        assertNull(read("""{"type":"daily","times":["12:60"]}"""))
        // interval
        assertNull(read("""{"type":"interval","hours":6}"""))
        assertNull(read("""{"type":"interval","startAt":1}"""))
        assertNull(read("""{"type":"interval","hours":0,"startAt":1}"""))
        assertNull(read("""{"type":"interval","hours":3000000000,"startAt":1}"""))
        assertNull(read("""{"type":"interval","hours":1.5,"startAt":1}"""))
        assertNull(read("""{"type":"interval","hours":"6","startAt":1}"""))
        assertNull(read("""{"type":"interval","hours":true,"startAt":1}"""))
        assertNull(read("""{"type":"interval","hours":[6],"startAt":1}"""))
        assertNull(read("""{"type":"interval","hours":1e999,"startAt":1}"""))
        // weekly
        assertNull(read("""{"type":"weekly","times":["07:00"]}"""))
        assertNull(read("""{"type":"weekly","daysOfWeek":[1]}"""))
        assertNull(read("""{"type":"weekly","daysOfWeek":[7],"times":["07:00"]}"""))
        assertNull(read("""{"type":"weekly","daysOfWeek":[-1],"times":["07:00"]}"""))
        assertNull(read("""{"type":"weekly","daysOfWeek":["Mon"],"times":["07:00"]}"""))
        // once
        assertNull(read("""{"type":"once"}"""))
    }
}
