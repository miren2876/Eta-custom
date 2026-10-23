package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTopBarTitleTest {
    @Test
    fun shortTitleIsKeptVerbatim() {
        assertEquals("钻杆螺纹护丝", conversationTopBarTitle("钻杆螺纹护丝"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("多模态是什么", conversationTopBarTitle("  多模态是什么 "))
    }

    @Test
    fun blankTitleStaysEmpty() {
        assertEquals("", conversationTopBarTitle("   "))
    }

    @Test
    fun longTitleIsTruncatedToLimitWithEllipsis() {
        val title = "LSP 日志查系统界面崩溃模块排查记录"
        val result = conversationTopBarTitle(title)
        assertEquals(CONVERSATION_TITLE_MAX_CHARS, result.length)
        assertEquals("LSP 日志查系统界面…", result)
    }

    @Test
    fun customLimitIsHonored() {
        assertEquals("abcdefg…", conversationTopBarTitle("abcdefghijklmn", maxChars = 8))
    }
}
