package com.termux.app.bubbles;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

public final class SessionBubbleIconFactory {

    private static final int ICON_SIZE_DP = 108;
    private static final float SINGLE_CHAR_TEXT_SIZE_RATIO = 0.56f;
    private static final float MULTI_CHAR_TEXT_SIZE_RATIO = 0.42f;

    private static final int BACKGROUND_COLOR = 0xFF000000;

    private final Context mContext;

    public SessionBubbleIconFactory(@NonNull Context context) {
        mContext = context;
    }

    @NonNull
    public Icon createSessionIcon(@NonNull TerminalSession session, @NonNull String sessionLabel,
                                  @Nullable Integer bubbleSlotId) {
        return Icon.createWithAdaptiveBitmap(createSessionBitmap(session, sessionLabel, bubbleSlotId));
    }

    @NonNull
    private Bitmap createSessionBitmap(@NonNull TerminalSession session, @NonNull String sessionLabel,
                                       @Nullable Integer bubbleSlotId) {
        int iconSize = getIconSize();
        Bitmap bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(BACKGROUND_COLOR);
        canvas.drawRect(0, 0, iconSize, iconSize, backgroundPaint);

        String badgeText = getBadgeText(session, sessionLabel, bubbleSlotId);
        Paint textPaint = createTextPaint(iconSize, badgeText);
        drawCenteredText(canvas, textPaint, badgeText, iconSize);

        return bitmap;
    }

    @NonNull
    private Paint createTextPaint(int iconSize, @NonNull String badgeText) {
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setTextSize(iconSize * getTextSizeRatio(badgeText));
        return textPaint;
    }

    private void drawCenteredText(@NonNull Canvas canvas, @NonNull Paint textPaint,
                                  @NonNull String badgeText, int iconSize) {
        Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
        float x = iconSize / 2f;
        float y = (iconSize / 2f) - ((fontMetrics.ascent + fontMetrics.descent) / 2f);
        canvas.drawText(badgeText, x, y, textPaint);
    }

    @NonNull
    private String getBadgeText(@NonNull TerminalSession session, @NonNull String sessionLabel,
                                @Nullable Integer bubbleSlotId) {
        String sessionName = session.mSessionName;
        String sessionMonogram = getMonogram(sessionName);
        if (!sessionMonogram.isEmpty()) return sessionMonogram;

        if (bubbleSlotId != null && bubbleSlotId >= 0)
            return String.valueOf(bubbleSlotId);

        String labelMonogram = getMonogram(sessionLabel);
        if (!labelMonogram.isEmpty()) return labelMonogram;

        return "$";
    }

    @NonNull
    private String getMonogram(@Nullable String value) {
        if (TextUtils.isEmpty(value)) return "";

        String trimmedValue = value.trim();
        if (trimmedValue.isEmpty()) return "";

        int fallbackCodePoint = -1;
        for (int index = 0; index < trimmedValue.length(); ) {
            int codePoint = trimmedValue.codePointAt(index);
            if (!Character.isWhitespace(codePoint) && fallbackCodePoint < 0)
                fallbackCodePoint = codePoint;
            if (Character.isLetterOrDigit(codePoint))
                return new String(Character.toChars(Character.toUpperCase(codePoint)));
            index += Character.charCount(codePoint);
        }

        if (fallbackCodePoint < 0) return "";
        return new String(Character.toChars(Character.toUpperCase(fallbackCodePoint)));
    }

    private float getTextSizeRatio(@NonNull String badgeText) {
        if (badgeText.length() <= 1) return SINGLE_CHAR_TEXT_SIZE_RATIO;
        return MULTI_CHAR_TEXT_SIZE_RATIO;
    }

    private int getIconSize() {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, ICON_SIZE_DP,
            mContext.getResources().getDisplayMetrics()));
    }

}
