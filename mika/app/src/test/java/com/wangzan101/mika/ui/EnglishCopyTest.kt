package com.wangzan101.mika.ui

import com.wangzan101.mika.capsule.CapsuleCards
import com.wangzan101.mika.capsule.CapsuleKind
import com.wangzan101.mika.i18n.EnglishTexts
import com.wangzan101.mika.settings.Problem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 界面语言是英文时,常见的文字都是英文;数量按单复数写。 */
class EnglishCopyTest {
    @get:Rule val texts = EnglishTexts()

    @Test fun chatPage() {
        assertEquals("Good morning", ChatCopy.greeting(7))
        assertEquals("What should Mika do?", ChatCopy.EMPTY_PROMPT)
        assertEquals("Open WeChat and message Wang Zan to add me to the Mika beta group", ChatCopy.examples.first().text)
        assertEquals("1 earlier step", ChatCopy.earlierSteps(1))
        assertEquals("3 earlier steps", ChatCopy.earlierSteps(3))
    }

    @Test fun settingsAndSetup() {
        assertEquals("No usable model yet", StatusCopy.banner(Problem.NoModel).title)
        assertEquals("Set up phone control", PhoneSetupCopy.TITLE)
        assertEquals("Hi, I’m Mika", WelcomeCopy.TITLE)
        assertEquals("About Mika", AboutCopy.TITLE)
    }

    @Test fun capsule() {
        assertEquals("Done", CapsuleCards.status(CapsuleKind.Done))
    }
}
