package com.termux.terminal;

public interface TerminalSessionIO {
    void write(byte[] data, int offset, int count);
    void onResize(int columns, int rows, int cellWidth, int cellHeight);
    void onClose();
}
