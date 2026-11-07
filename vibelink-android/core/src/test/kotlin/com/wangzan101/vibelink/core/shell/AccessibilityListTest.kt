package com.wangzan101.vibelink.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityListTest {
    private val vibelink = PermissionCommands.accessibilityService("com.wangzan101.vibelink")
    private val vibelinkLong = "com.wangzan101.vibelink/com.wangzan101.vibelink.a11y.VibeLinkAccessibilityService"
    private val other = "com.example.reader/.ReaderService"
    private val other2 = "org.sample.keys/org.sample.keys.KeyService"

    @Test
    fun emptyOrUnsetValues() {
        listOf(null, "", "   ", "null", "null\n", ":", " : ").forEach { raw ->
            assertEquals("raw=$raw", emptyList<String>(), AccessibilityList.entries(raw))
            assertFalse(AccessibilityList.contains(raw, vibelink))
            assertEquals(vibelink, AccessibilityList.with(raw, vibelink))
            assertEquals("", AccessibilityList.without(raw, vibelink))
        }
    }

    @Test
    fun appendKeepsOtherServicesInOrder() {
        assertEquals("$other:$vibelink", AccessibilityList.with(other, vibelink))
        assertEquals("$other:$other2:$vibelink", AccessibilityList.with("$other:$other2\n", vibelink))
    }

    @Test
    fun alreadyPresentIsNotDuplicated() {
        assertEquals("$other:$vibelink", AccessibilityList.with("$other:$vibelink", vibelink))
        // 系统里存的是完整类名写法,也算已经有了
        assertEquals("$vibelinkLong:$other", AccessibilityList.with("$vibelinkLong:$other", vibelink))
        assertTrue(AccessibilityList.contains(vibelinkLong, vibelink))
    }

    @Test
    fun removeKeepsOthersAndHandlesBothSpellings() {
        assertEquals("$other:$other2", AccessibilityList.without("$other:$vibelink:$other2", vibelink))
        assertEquals(other, AccessibilityList.without("$vibelinkLong:$other", vibelink))
        assertEquals(other, AccessibilityList.without(other, vibelink))
        assertEquals("", AccessibilityList.without(vibelink, vibelink))
    }

    @Test
    fun strayColonsAndSpacesAreCleanedUp() {
        assertEquals("$other:$other2:$vibelink", AccessibilityList.with(":$other:: $other2 :", vibelink))
        assertEquals("$other:$other2", AccessibilityList.without("$other::$vibelink:$other2:", vibelink))
    }

    @Test
    fun differentServiceOfSamePackageIsNotConfused() {
        val sibling = "com.wangzan101.vibelink/.a11y.OtherService"
        assertFalse(AccessibilityList.contains(sibling, vibelink))
        assertEquals(sibling, AccessibilityList.without("$sibling:$vibelink", vibelink))
    }
}
