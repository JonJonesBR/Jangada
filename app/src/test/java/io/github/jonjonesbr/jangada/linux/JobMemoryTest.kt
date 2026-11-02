package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobMemoryTest {

    @Test
    fun leDoisInteiros() {
        assertEquals(JobMemory.Usage(7516, 4), JobMemory.parse("7516 4\n"))
    }

    @Test
    fun vazioNaoEValido() {
        assertNull(JobMemory.parse(""))
    }

    @Test
    fun naoNumeroNaoEValido() {
        assertNull(JobMemory.parse("abc 1"))
    }

    @Test
    fun tresCamposNaoEValido() {
        assertNull(JobMemory.parse("1 2 3"))
    }

    @Test
    fun negativoNaoEValido() {
        assertNull(JobMemory.parse("-1 2"))
    }
}
