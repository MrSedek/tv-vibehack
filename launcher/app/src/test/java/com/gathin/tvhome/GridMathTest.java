package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GridMathTest {

    @Test
    public void squareGridBehavesLikeBefore() {
        GridMath m = new GridMath(4, 4);
        assertEquals(4, m.spanCount());
        assertEquals(1, m.spanSize(0));
        assertEquals(1, m.spanSize(7));
        assertEquals(-1, m.up(2));          // dock: leave the grid
        assertEquals(1, m.up(5));           // first grid row -> dock, same column
        assertEquals(5, m.up(9));
        assertEquals(6, m.down(2, 12));     // dock -> first grid row
        assertEquals(10, m.down(6, 12));
        assertEquals(10, m.down(10, 12));   // last row: stay
        assertEquals(11, m.down(7, 12));    // short last row: clamp to the last item
    }

    @Test
    public void fourDockTilesOverFiveColumns() {
        GridMath m = new GridMath(4, 5);
        assertEquals(20, m.spanCount());
        assertEquals(5, m.spanSize(0));     // dock tiles are wider
        assertEquals(4, m.spanSize(4));
        // Down from the dock lands under the tile: dock 0,1,2,3 -> grid columns 0,1,3,4.
        assertEquals(4, m.down(0, 20));
        assertEquals(5, m.down(1, 20));
        assertEquals(7, m.down(2, 20));
        assertEquals(8, m.down(3, 20));
        // Up from the first grid row: columns 0..4 -> dock 0,1,2,2,3.
        assertEquals(0, m.up(4));
        assertEquals(1, m.up(5));
        assertEquals(2, m.up(6));
        assertEquals(2, m.up(7));
        assertEquals(3, m.up(8));
        assertEquals(4, m.up(9));           // second grid row -> first, same column
    }

    @Test
    public void edges() {
        GridMath m = new GridMath(4, 5);
        assertTrue(m.atLeftEdge(0));
        assertTrue(m.atRightEdge(3, 20));
        assertFalse(m.atRightEdge(2, 20));
        assertTrue(m.atLeftEdge(4));
        assertTrue(m.atRightEdge(8, 20));
        assertFalse(m.atLeftEdge(5));
        assertTrue(m.atRightEdge(19, 20));  // last item of a full row
        assertTrue(m.atRightEdge(10, 11));  // last item of a short row
    }

    @Test
    public void noDock() {
        GridMath m = new GridMath(0, 5);
        assertEquals(5, m.spanCount());
        assertEquals(1, m.spanSize(0));
        assertEquals(-1, m.up(2));
        assertEquals(7, m.down(2, 10));
        assertEquals(0, m.column(0));
        assertEquals(4, m.column(9));
    }

    @Test
    public void dockOnly() {
        GridMath m = new GridMath(3, 5);
        assertEquals(1, m.down(1, 3));      // nothing below: stay
        assertTrue(m.atRightEdge(2, 3));
    }

    @Test
    public void columnMapping() {
        assertEquals(0, GridMath.mapColumn(0, 4, 5));
        assertEquals(4, GridMath.mapColumn(3, 4, 5));
        assertEquals(2, GridMath.mapColumn(2, 5, 4));
        assertEquals(3, GridMath.mapColumn(4, 5, 4));
        assertEquals(2, GridMath.mapColumn(0, 1, 5));   // one tile spanning the row sits over the middle column
        assertEquals(0, GridMath.mapColumn(4, 5, 1));
        assertEquals(15, GridMath.lcm(3, 5));
        assertEquals(4, GridMath.lcm(4, 4));
    }
}
