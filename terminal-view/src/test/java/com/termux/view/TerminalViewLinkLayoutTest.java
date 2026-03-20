package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.termux.terminal.ScreenSnapshot;
import com.termux.terminal.TerminalLinkSource;
import com.termux.terminal.ViewportLinkSnapshot;

import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class TerminalViewLinkLayoutTest {

    private static final int SNAPSHOT_MAGIC = 0x54475832;
    private static final int SNAPSHOT_FLAG_FULL_REBUILD = 1;

    @Test
    public void buildsWrappedLiteralUrlAcrossVisibleRows() {
        ScreenSnapshot snapshot = createSnapshot(1L, 0,
            asciiRow(true, "https://exam", 12),
            asciiRow(false, "ple.com", 12)
        );

        TerminalViewLinkLayout layout = TerminalViewLinkLayout.build(snapshot, null);

        TerminalViewLinkLayout.LinkHit firstRowHit = layout.findAt(0, 0);
        TerminalViewLinkLayout.LinkHit secondRowHit = layout.findAt(1, 2);
        assertNotNull(firstRowHit);
        assertNotNull(secondRowHit);
        assertEquals("https://example.com", firstRowHit.getUrl());
        assertEquals(TerminalLinkSource.SOURCE_VISIBLE_URL, firstRowHit.getSource());
        assertEquals("https://example.com", secondRowHit.getUrl());
        assertEquals(TerminalLinkSource.SOURCE_VISIBLE_URL, secondRowHit.getSource());
        assertNull(layout.findAt(1, 10));
    }

    @Test
    public void prefersOsc8SegmentsOverOverlappingRegexMatches() {
        ScreenSnapshot snapshot = createSnapshot(2L, 0,
            asciiRow(false, "https://example.com", 19)
        );
        ViewportLinkSnapshot osc8Snapshot = ViewportLinkSnapshot.create(2L, 0, 1, 19,
            new ViewportLinkSnapshot.Segment[] {
                new ViewportLinkSnapshot.Segment(0, 0, 19, "https://osc8.example")
            }
        );

        TerminalViewLinkLayout layout = TerminalViewLinkLayout.build(snapshot, osc8Snapshot);

        TerminalViewLinkLayout.LinkHit hit = layout.findAt(0, 5);
        assertNotNull(hit);
        assertEquals("https://osc8.example", hit.getUrl());
        assertEquals(TerminalLinkSource.SOURCE_OSC8, hit.getSource());
    }

    @Test
    public void mapsUrlsCorrectlyWhenWideCharactersPrecedeThem() {
        ScreenSnapshot snapshot = createSnapshot(3L, 0,
            row(false, concat(
                new CellSpec[] {
                    new CellSpec("🙂", 2),
                    new CellSpec("", 1)
                },
                asciiCells("https://a.example")
            ))
        );

        TerminalViewLinkLayout layout = TerminalViewLinkLayout.build(snapshot, null);

        assertNull(layout.findAt(0, 1));
        assertNull(layout.findAt(0, 2));

        TerminalViewLinkLayout.LinkHit hit = layout.findAt(0, 3);
        assertNotNull(hit);
        assertEquals("https://a.example", hit.getUrl());
        assertEquals(TerminalLinkSource.SOURCE_VISIBLE_URL, hit.getSource());
    }

    private static ScreenSnapshot createSnapshot(long frameSequence, int topRow, RowSpec... rows) {
        int columns = rows.length == 0 ? 0 : rows[0].getColumns();
        for (RowSpec row : rows) {
            if (row.getColumns() != columns) {
                throw new IllegalArgumentException("All rows must use the same column count");
            }
        }

        ScreenSnapshot snapshot = new ScreenSnapshot();
        ByteBuffer buffer = snapshot.duplicateBuffer().order(ByteOrder.nativeOrder());
        buffer.clear();
        buffer.putInt(SNAPSHOT_MAGIC);
        buffer.putInt(topRow);
        buffer.putInt(rows.length);
        buffer.putInt(columns);
        buffer.putInt(SNAPSHOT_FLAG_FULL_REBUILD);
        buffer.putInt(0);
        buffer.putInt(0);
        for (RowSpec row : rows) {
            writeRow(buffer, row, columns);
        }

        invoke(snapshot, "markNativeSnapshot", int.class, buffer.position());
        invoke(snapshot, "setFrameSequence", long.class, frameSequence);
        return snapshot;
    }

    private static void writeRow(ByteBuffer buffer, RowSpec row, int columns) {
        List<Integer> starts = new ArrayList<>(columns);
        List<Integer> lengths = new ArrayList<>(columns);
        List<Integer> widths = new ArrayList<>(columns);
        List<Long> styles = new ArrayList<>(columns);
        StringBuilder text = new StringBuilder();
        int usedColumns = 0;

        for (CellSpec cell : row.mCells) {
            int start = text.length();
            if (!cell.mText.isEmpty()) {
                text.append(cell.mText);
            }
            starts.add(start);
            lengths.add(cell.mText.length());
            widths.add(cell.mDisplayWidth);
            styles.add(cell.mStyle);
            usedColumns += cell.mDisplayWidth;
            for (int tail = 1; tail < cell.mDisplayWidth; tail++) {
                starts.add(start);
                lengths.add(0);
                widths.add(0);
                styles.add(cell.mStyle);
            }
        }

        while (usedColumns < columns) {
            starts.add(text.length());
            lengths.add(0);
            widths.add(1);
            styles.add(0L);
            usedColumns++;
        }

        buffer.putInt(text.length());
        buffer.putInt(row.mLineWrap ? 1 : 0);
        for (int column = 0; column < columns; column++) {
            buffer.putInt(starts.get(column));
            buffer.putShort((short) (int) lengths.get(column));
            buffer.put((byte) (int) widths.get(column));
            buffer.put((byte) 0);
            buffer.putLong(styles.get(column));
        }
        for (int index = 0; index < text.length(); index++) {
            buffer.putChar(text.charAt(index));
        }
    }

    private static RowSpec asciiRow(boolean lineWrap, String text, int columns) {
        CellSpec[] cells = new CellSpec[text.length()];
        for (int index = 0; index < text.length(); index++) {
            cells[index] = new CellSpec(String.valueOf(text.charAt(index)), 1);
        }
        return row(lineWrap, cells, columns);
    }

    private static RowSpec row(boolean lineWrap, CellSpec[] cells, int columns) {
        int usedColumns = 0;
        for (CellSpec cell : cells) {
            usedColumns += cell.mDisplayWidth;
        }
        if (usedColumns > columns) {
            throw new IllegalArgumentException("Row exceeds column count");
        }

        CellSpec[] padded = new CellSpec[columns == 0 ? cells.length : cells.length + (columns - usedColumns)];
        System.arraycopy(cells, 0, padded, 0, cells.length);
        for (int index = cells.length; index < padded.length; index++) {
            padded[index] = new CellSpec("", 1);
        }
        return new RowSpec(lineWrap, padded);
    }

    private static RowSpec row(boolean lineWrap, CellSpec[] cells) {
        return new RowSpec(lineWrap, cells);
    }

    private static CellSpec[] asciiCells(String text) {
        CellSpec[] cells = new CellSpec[text.length()];
        for (int index = 0; index < text.length(); index++) {
            cells[index] = new CellSpec(String.valueOf(text.charAt(index)), 1);
        }
        return cells;
    }

    private static CellSpec[] concat(CellSpec[] first, CellSpec[] second) {
        CellSpec[] result = new CellSpec[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static void invoke(Object target, String methodName, Class<?> parameterType, Object argument) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterType);
            method.setAccessible(true);
            method.invoke(target, argument);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke " + methodName, e);
        }
    }

    private static final class RowSpec {
        private final boolean mLineWrap;
        private final CellSpec[] mCells;

        private RowSpec(boolean lineWrap, CellSpec[] cells) {
            mLineWrap = lineWrap;
            mCells = cells;
        }

        private int getColumns() {
            int columns = 0;
            for (CellSpec cell : mCells) {
                columns += cell.mDisplayWidth;
            }
            return columns;
        }
    }

    private static final class CellSpec {
        private final String mText;
        private final int mDisplayWidth;
        private final long mStyle;

        private CellSpec(String text, int displayWidth) {
            this(text, displayWidth, 0L);
        }

        private CellSpec(String text, int displayWidth, long style) {
            mText = text;
            mDisplayWidth = displayWidth;
            mStyle = style;
        }
    }
}
