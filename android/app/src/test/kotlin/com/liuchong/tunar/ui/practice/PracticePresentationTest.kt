package com.liuchong.tunar.ui.practice

import org.junit.Assert.*
import org.junit.Test

class PracticePresentationTest {
    @Test fun measurementTimeDisplaysTenthsWithoutWrappingMinutes() {
        assertEquals("01:02.4", PracticePresentation.time(62.4))
        assertEquals("00:00.0", PracticePresentation.time(0.0))
    }
    @Test fun onlyCaptureDestinationsKeepPracticeActive() {
        assertTrue(PracticePresentation.isCaptureRoute("tuner"))
        assertTrue(PracticePresentation.isCaptureRoute("instrument"))
        assertTrue(PracticePresentation.isCaptureRoute("spectrum"))
        assertTrue(PracticePresentation.isCaptureRoute("practice"))
        assertFalse(PracticePresentation.isCaptureRoute("settings"))
        assertFalse(PracticePresentation.isCaptureRoute("tools"))
    }
}
