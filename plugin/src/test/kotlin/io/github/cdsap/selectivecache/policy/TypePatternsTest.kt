package io.github.cdsap.selectivecache.policy

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypePatternsTest {

    @Test
    fun `empty pattern set matches nothing and reports empty`() {
        val patterns = TypePatterns(emptySet())
        assertTrue(patterns.isEmpty)
        assertFalse(patterns.matches("com.example.Task"))
        assertFalse(patterns.matches(null))
    }

    @Test
    fun `exact fully qualified name matches`() {
        val patterns = TypePatterns(setOf("com.example.BigTask"))
        assertTrue(patterns.matches("com.example.BigTask"))
        assertFalse(patterns.matches("com.example.BigTaskExtra"))
        assertFalse(patterns.matches("other.com.example.BigTask"))
    }

    @Test
    fun `null class name never matches`() {
        assertFalse(TypePatterns(setOf("com.example.BigTask")).matches(null))
        assertFalse(TypePatterns(setOf("*")).matches(null))
    }

    @Test
    fun `trailing wildcard matches a package prefix`() {
        val patterns = TypePatterns(setOf("com.android.build.gradle.tasks.*"))
        assertTrue(patterns.matches("com.android.build.gradle.tasks.MergeResources"))
        assertTrue(patterns.matches("com.android.build.gradle.tasks.sub.Nested"))
        assertFalse(patterns.matches("com.android.build.gradle.Tasks"))
    }

    @Test
    fun `leading wildcard matches a suffix`() {
        val patterns = TypePatterns(setOf("*.MergeResources"))
        assertTrue(patterns.matches("com.android.build.gradle.tasks.MergeResources"))
        assertFalse(patterns.matches("com.android.MergeResourcesTask"))
    }

    @Test
    fun `wildcard in the middle matches`() {
        val patterns = TypePatterns(setOf("com.example.*.BigTask"))
        assertTrue(patterns.matches("com.example.deeply.nested.BigTask"))
        assertFalse(patterns.matches("com.example.BigTask"))
    }

    @Test
    fun `bare wildcard matches every class name`() {
        val patterns = TypePatterns(setOf("*"))
        assertTrue(patterns.matches("a"))
        assertTrue(patterns.matches("com.example.Whatever"))
    }

    @Test
    fun `dots are literal, not regex wildcards`() {
        val patterns = TypePatterns(setOf("com.example.BigTask"))
        assertFalse(patterns.matches("comXexampleXBigTask"))
    }

    @Test
    fun `regex metacharacters in a pattern are escaped`() {
        // A pattern containing regex syntax must be treated as literal text.
        val patterns = TypePatterns(setOf("com.example.Task\$Inner+"))
        assertTrue(patterns.matches("com.example.Task\$Inner+"))
        assertFalse(patterns.matches("com.example.Task\$Innerr"))
    }

    @Test
    fun `any one of several patterns can match`() {
        val patterns = TypePatterns(setOf("com.a.First", "com.b.*", "*.Third"))
        assertTrue(patterns.matches("com.a.First"))
        assertTrue(patterns.matches("com.b.anything.Here"))
        assertTrue(patterns.matches("com.z.Third"))
        assertFalse(patterns.matches("com.c.Fourth"))
    }

    @Test
    fun `matching is case sensitive like class names are`() {
        val patterns = TypePatterns(setOf("com.example.BigTask"))
        assertFalse(patterns.matches("com.example.bigtask"))
    }
}
