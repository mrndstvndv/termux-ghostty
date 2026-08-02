package com.termux.view;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.termux.terminal.ScreenSnapshot;
import com.termux.terminal.TerminalLinkSource;
import com.termux.terminal.TextStyle;
import com.termux.terminal.ViewportLinkSnapshot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(RobolectricTestRunner.class)
public final class TerminalRendererTest {

    private static final int SNAPSHOT_MAGIC = 0x54475832;
    private static final int SNAPSHOT_FLAG_FULL_REBUILD = 1;
    private static final long NORMAL_STYLE = ((long) TextStyle.COLOR_INDEX_FOREGROUND << 40)
        | ((long) TextStyle.COLOR_INDEX_BACKGROUND << 16);

    @Test
    public void rowRenderingDrawsSyntheticLinkUnderline() {
        int columns = 8;
        ScreenSnapshot snapshot = createBlankSnapshot(columns);
        ViewportLinkSnapshot viewportLinks = ViewportLinkSnapshot.create(1L, 0, 1, columns,
            new ViewportLinkSnapshot.Segment[] {
                new ViewportLinkSnapshot.Segment(0, 0, columns, "https://example.com",
                    TerminalLinkSource.SOURCE_OSC8)
            });
        TerminalViewLinkLayout linkLayout = TerminalViewLinkLayout.build(snapshot, viewportLinks);
        assertNotNull(linkLayout.findAt(0, 3));

        TerminalRenderer renderer = new TerminalRenderer(32, Typeface.MONOSPACE);
        RecordingCanvas canvas = new RecordingCanvas();
        renderer.renderRow(snapshot, canvas, 0, -1, -1, -1, 0, false, linkLayout);

        assertTrue("synthetic link underline is missing", canvas.drawRectCalls > 0);
    }

    private static ScreenSnapshot createBlankSnapshot(int columns) {
        ScreenSnapshot snapshot = new ScreenSnapshot();
        ByteBuffer buffer = snapshot.duplicateBuffer().order(ByteOrder.nativeOrder());
        buffer.clear();
        buffer.putInt(SNAPSHOT_MAGIC);
        buffer.putInt(0);
        buffer.putInt(1);
        buffer.putInt(columns);
        buffer.putInt(SNAPSHOT_FLAG_FULL_REBUILD);
        buffer.putInt(0);
        buffer.putInt(0);

        int alignedPosition = (buffer.position() + 7) & ~7;
        while (buffer.position() < alignedPosition) {
            buffer.put((byte) 0);
        }
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putLong(0L);
        for (int column = 0; column < columns; column++) {
            buffer.putInt(0);
        }
        for (int column = 0; column < columns; column++) {
            buffer.putShort((short) 0);
        }
        for (int column = 0; column < columns; column++) {
            buffer.put((byte) 1);
        }

        alignedPosition = (buffer.position() + 7) & ~7;
        while (buffer.position() < alignedPosition) {
            buffer.put((byte) 0);
        }
        for (int column = 0; column < columns; column++) {
            buffer.putLong(NORMAL_STYLE);
        }
        invoke(snapshot, "markNativeSnapshot", int.class, buffer.position());
        invoke(snapshot, "setFrameSequence", long.class, 1L);
        return snapshot;
    }

    private static final class RecordingCanvas extends Canvas {
        private int drawRectCalls;

        @Override
        public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            drawRectCalls++;
        }
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
}
