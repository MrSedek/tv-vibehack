package com.gathin.tvhome;

/**
 * Index arithmetic for the home grid: the first {@code dockSize} positions are the dock row, the
 * rest fill rows of {@code columns}. The two rows can differ in width, so the layout manager works
 * in {@link #spanCount()} spans and D-pad moves map columns between the rows by horizontal position.
 * Plain Java so it can be unit tested.
 */
final class GridMath {

    final int dockSize;
    final int columns;

    GridMath(int dockSize, int columns) {
        this.dockSize = Math.max(0, dockSize);
        this.columns = Math.max(1, columns);
    }

    boolean isDock(int position) {
        return position < dockSize;
    }

    /** Spans per row: the smallest number both row widths divide. */
    int spanCount() {
        return lcm(Math.max(1, dockSize), columns);
    }

    int spanSize(int position) {
        return spanCount() / (isDock(position) ? Math.max(1, dockSize) : columns);
    }

    int column(int position) {
        return isDock(position) ? position : (position - dockSize) % columns;
    }

    int rowWidth(int position) {
        return isDock(position) ? dockSize : columns;
    }

    /** Position reached by Up, or -1 to leave the grid (Up from the dock goes to the shelf or top bar). */
    int up(int position) {
        if (isDock(position)) return -1;
        int gi = position - dockSize;
        if (gi < columns) {
            if (dockSize == 0) return -1;
            return mapColumn(gi, columns, dockSize);
        }
        return position - columns;
    }

    /** Position reached by Down; the same position when already on the last row. */
    int down(int position, int count) {
        if (isDock(position)) {
            if (count <= dockSize) return position;
            return Math.min(dockSize + mapColumn(position, dockSize, columns), count - 1);
        }
        int gi = position - dockSize;
        int gridCount = count - dockSize;
        int lastRowStart = ((gridCount - 1) / columns) * columns;
        if (gi >= lastRowStart) return position;
        return Math.min(position + columns, count - 1);
    }

    boolean atLeftEdge(int position) {
        return column(position) == 0;
    }

    boolean atRightEdge(int position, int count) {
        return column(position) == rowWidth(position) - 1 || position == count - 1;
    }

    /** The column in a row of {@code to} cells under the centre of column {@code col} in a row of {@code from}. */
    static int mapColumn(int col, int from, int to) {
        if (from == to) return col;
        return Math.min(to - 1, (int) (((col + 0.5) * to) / from));
    }

    static int lcm(int a, int b) {
        return a / gcd(a, b) * b;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GridMath && ((GridMath) o).dockSize == dockSize && ((GridMath) o).columns == columns;
    }

    @Override
    public int hashCode() {
        return dockSize * 31 + columns;
    }
}
