package app.simmo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DragReorderTest {

    // Four 100px rows stacked from 0: [0,100) [100,200) [200,300) [300,400).
    private val items = List(4) { ReorderItemBounds(index = it, offset = it * 100, size = 100) }

    private fun target(current: Int, top: Float) =
        reorderTargetIndex(current, draggedTop = top, draggedBottom = top + 100f, items = items)

    @Test
    fun `staying within the own slot moves nothing`() {
        assertNull(target(current = 1, top = 100f))
        // Nudges short of the neighbor's midpoint don't reorder either.
        assertNull(target(current = 1, top = 140f))
        assertNull(target(current = 1, top = 60f))
    }

    @Test
    fun `passing the next row's midpoint moves down one`() {
        // Dragged bottom at 251 crosses row 2's midpoint (250).
        assertEquals(2, target(current = 1, top = 151f))
    }

    @Test
    fun `passing the previous row's midpoint moves up one`() {
        // Dragged top at 49 crosses row 0's midpoint (50).
        assertEquals(0, target(current = 1, top = 49f))
    }

    @Test
    fun `a fast drag can jump several rows in one step`() {
        // Dragged bottom at 360 has passed the midpoints of rows 1 (150),
        // 2 (250), and 3 (350): the furthest crossed row wins.
        assertEquals(3, target(current = 0, top = 260f))
        assertEquals(0, target(current = 3, top = 20f))
    }

    @Test
    fun `movedItem reorders the working copy and ignores bad indices`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), list.movedItem(0, 2))
        assertEquals(listOf("d", "a", "b", "c"), list.movedItem(3, 0))
        assertEquals(list, list.movedItem(1, 1))
        assertEquals(list, list.movedItem(-1, 2))
        assertEquals(list, list.movedItem(1, 9))
    }
}
