package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test

class EditHistoryTest {
    @Test fun restoredHistoryKeepsTheRedoOrderAndStartsANewTypingGroup() {
        val first=EditHistory<String>(); first.record("","a","typing",100); first.record("a","b","separate",200); assertEquals("a",first.undo("b"))
        val restored=EditHistory<String>(); restored.restore(first.snapshot())
        assertTrue(restored.canUndo && restored.canRedo); assertEquals("b",restored.redo("a")); assertEquals("a",restored.undo("b"))
        restored.record("a","c","typing",201); assertFalse(restored.canRedo); assertEquals("a",restored.undo("c"))
    }
    @Test fun typingCoalescesAndUndoRedoPreserveBothEndsOfTheEdit() {
        val h=EditHistory<String>()
        h.record("","h","text",100); h.record("h","hi","text",200)
        assertEquals("",h.undo("hi")); assertFalse(h.canUndo)
        assertEquals("hi",h.redo("")); assertFalse(h.canRedo)
    }
    @Test fun unrelatedActionsAndLongPausesStayIndependent() {
        val h=EditHistory<Int>()
        h.record(0,1,"slider",0); h.record(1,2,"slider",1000); h.record(2,3,"trim",1100)
        assertEquals(2,h.undo(3)); assertEquals(1,h.undo(2)); assertEquals(0,h.undo(1))
    }
    @Test fun aNewEditAfterUndoClearsTheOldRedoBranch() {
        val h=EditHistory<Int>()
        h.record(0,1); h.record(1,2); assertEquals(1,h.undo(2))
        h.record(1,9); assertFalse(h.canRedo); assertEquals(1,h.undo(9)); assertEquals(0,h.undo(1))
    }
    @Test fun oldSnapshotsAreBoundedAndClearReleasesThem() {
        val h=EditHistory<Int>(2)
        h.record(0,1); h.record(1,2); h.record(2,3)
        assertEquals(listOf(1,2),h.states()); assertEquals(2,h.undo(3)); assertEquals(1,h.undo(2)); assertNull(h.undo(1))
        h.clear(); assertTrue(h.states().isEmpty()); assertFalse(h.canUndo); assertFalse(h.canRedo)
    }
}
