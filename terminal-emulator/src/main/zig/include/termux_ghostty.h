#ifndef TERMUX_GHOSTTY_H
#define TERMUX_GHOSTTY_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TERMUX_GHOSTTY_APPEND_RESULT_SCREEN_CHANGED (1u << 0)
#define TERMUX_GHOSTTY_APPEND_RESULT_CURSOR_CHANGED (1u << 1)
#define TERMUX_GHOSTTY_APPEND_RESULT_TITLE_CHANGED (1u << 2)
#define TERMUX_GHOSTTY_APPEND_RESULT_BELL (1u << 3)
#define TERMUX_GHOSTTY_APPEND_RESULT_CLIPBOARD_COPY (1u << 4)
#define TERMUX_GHOSTTY_APPEND_RESULT_COLORS_CHANGED (1u << 5)
#define TERMUX_GHOSTTY_APPEND_RESULT_REPLY_BYTES_AVAILABLE (1u << 6)
#define TERMUX_GHOSTTY_APPEND_RESULT_DESKTOP_NOTIFICATION (1u << 7)
#define TERMUX_GHOSTTY_APPEND_RESULT_PROGRESS (1u << 8)

#define TERMUX_GHOSTTY_MODE_CURSOR_KEYS_APPLICATION (1u << 0)
#define TERMUX_GHOSTTY_MODE_KEYPAD_APPLICATION (1u << 1)
#define TERMUX_GHOSTTY_MODE_MOUSE_TRACKING (1u << 2)
#define TERMUX_GHOSTTY_MODE_BRACKETED_PASTE (1u << 3)
#define TERMUX_GHOSTTY_MODE_MOUSE_PROTOCOL_SGR (1u << 4)

#define TERMUX_GHOSTTY_PROGRESS_STATE_NONE 0
#define TERMUX_GHOSTTY_PROGRESS_STATE_SET 1
#define TERMUX_GHOSTTY_PROGRESS_STATE_ERROR 2
#define TERMUX_GHOSTTY_PROGRESS_STATE_INDETERMINATE 3
#define TERMUX_GHOSTTY_PROGRESS_STATE_PAUSE 4

typedef struct termux_ghostty_session termux_ghostty_session;

termux_ghostty_session* termux_ghostty_session_create(int32_t columns, int32_t rows, int32_t transcript_rows, int32_t cell_width_pixels, int32_t cell_height_pixels);
void termux_ghostty_session_destroy(termux_ghostty_session* session);
void termux_ghostty_session_reset(termux_ghostty_session* session);
int32_t termux_ghostty_session_resize(termux_ghostty_session* session, int32_t columns, int32_t rows, int32_t cell_width_pixels, int32_t cell_height_pixels);
int32_t termux_ghostty_session_queue_mouse_event(termux_ghostty_session* session, int32_t action, int32_t button, int32_t modifiers, float surface_x, float surface_y, int32_t screen_width_px, int32_t screen_height_px, int32_t cell_width_px, int32_t cell_height_px, int32_t padding_top_px, int32_t padding_right_px, int32_t padding_bottom_px, int32_t padding_left_px);
uint32_t termux_ghostty_session_append(termux_ghostty_session* session, const uint8_t* data, size_t len);
size_t termux_ghostty_session_drain_pending_output(termux_ghostty_session* session, uint8_t* buffer, size_t capacity);
int32_t termux_ghostty_session_fill_snapshot(termux_ghostty_session* session, int32_t top_row, uint8_t* buffer, size_t capacity);
int32_t termux_ghostty_session_get_columns(const termux_ghostty_session* session);
int32_t termux_ghostty_session_get_rows(const termux_ghostty_session* session);
int32_t termux_ghostty_session_get_active_rows(const termux_ghostty_session* session);
int32_t termux_ghostty_session_get_active_transcript_rows(const termux_ghostty_session* session);
uint32_t termux_ghostty_session_get_mode_bits(const termux_ghostty_session* session);
int32_t termux_ghostty_session_get_cursor_row(const termux_ghostty_session* session);
int32_t termux_ghostty_session_get_cursor_col(const termux_ghostty_session* session);
int32_t termux_ghostty_session_get_cursor_style(const termux_ghostty_session* session);
bool termux_ghostty_session_is_cursor_visible(const termux_ghostty_session* session);
bool termux_ghostty_session_is_reverse_video(const termux_ghostty_session* session);
bool termux_ghostty_session_is_alternate_buffer_active(const termux_ghostty_session* session);
int32_t termux_ghostty_session_get_progress_state(const termux_ghostty_session* session);
int32_t termux_ghostty_session_get_progress_value(const termux_ghostty_session* session);
uint64_t termux_ghostty_session_get_progress_generation(const termux_ghostty_session* session);
void termux_ghostty_session_clear_progress(termux_ghostty_session* session);
char* termux_ghostty_session_consume_notification_title(termux_ghostty_session* session);
char* termux_ghostty_session_consume_notification_body(termux_ghostty_session* session);

#ifdef __cplusplus
}
#endif

#endif
