package io.github.cdsap.selectivecache.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeclineTallyTest {

    @Test
    fun `a fresh tally is empty`() {
        val tally = DeclineTally()
        assertTrue(tally.isEmpty)
        assertEquals(0, tally.declinedLoads)
        assertEquals(0, tally.declinedStores)
        assertEquals(0L, tally.declinedStoreBytes)
        assertEquals(emptyMap<String, DeclineTally.Totals>(), tally.byLabel())
    }

    @Test
    fun `loads and stores are counted separately and bytes accumulate`() {
        val tally = DeclineTally()
        tally.recordLoad("A")
        tally.recordLoad("A")
        tally.recordStore("A", 100)
        tally.recordStore("B", 250)

        assertFalse(tally.isEmpty)
        assertEquals(2, tally.declinedLoads)
        assertEquals(2, tally.declinedStores)
        assertEquals(350L, tally.declinedStoreBytes)
    }

    @Test
    fun `totals are broken down per label`() {
        val tally = DeclineTally()
        tally.recordLoad("A")
        tally.recordStore("A", 100)
        tally.recordStore("B", 250)

        assertEquals(
            mapOf(
                "A" to DeclineTally.Totals(loads = 1, stores = 1, bytes = 100),
                "B" to DeclineTally.Totals(loads = 0, stores = 1, bytes = 250),
            ),
            tally.byLabel(),
        )
    }

    @Test
    fun `labels come back sorted so reports are stable`() {
        val tally = DeclineTally()
        listOf("zed", "alpha", "middle").forEach { tally.recordLoad(it) }
        assertEquals(listOf("alpha", "middle", "zed"), tally.byLabel().keys.toList())
    }

    @Test
    fun `counting is safe from many threads at once`() {
        // Gradle runs work units in parallel, so every decline can race every other one.
        val tally = DeclineTally()
        val threads = 16
        val perThread = 200
        val start = CountDownLatch(1)
        val workers = (0 until threads).map { i ->
            Thread {
                start.await(5, TimeUnit.SECONDS)
                repeat(perThread) {
                    tally.recordLoad("label-${i % 4}")
                    tally.recordStore("label-${i % 4}", 10)
                }
            }
        }
        workers.forEach(Thread::start)
        start.countDown()
        workers.forEach { it.join(10_000) }

        assertEquals(threads * perThread, tally.declinedLoads)
        assertEquals(threads * perThread, tally.declinedStores)
        assertEquals(threads * perThread * 10L, tally.declinedStoreBytes)
        assertEquals(4, tally.byLabel().size)
    }
}
