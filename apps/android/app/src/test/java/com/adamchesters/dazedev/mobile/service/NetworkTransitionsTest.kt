package com.adamchesters.dazedev.mobile.service

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkTransitionsTest {
    @Test fun backFiresOnlyAfterTheDefaultNetworkIsLost() {
        val events = mutableListOf<String>()
        val transitions = NetworkTransitions({ events.add("lost") }, { events.add("back") })
        transitions.available("wifi")
        // A switch to another network without a loss leaves open connections alone.
        transitions.available("cell")
        transitions.lost("wifi")
        assertEquals(emptyList<String>(), events)
        transitions.lost("cell")
        transitions.available("wifi")
        transitions.available("wifi")
        assertEquals(listOf("lost", "back"), events)
    }
}
