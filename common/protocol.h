#ifndef DISPLAY_PROTOCOL_H
#define DISPLAY_PROTOCOL_H

#include <stdint.h>

#define CTRL_MSG_CONSUMER_HELLO  1
#define CTRL_MSG_PRODUCER_HELLO  2
#define CTRL_MSG_SCREEN_INFO     7
#define CTRL_MSG_REJECT          8
#define CTRL_MSG_PICKUP_FDS      9
#define CTRL_MSG_FDS_READY      10

#define DATA_MSG_BUF_READY       100
#define DATA_MSG_REFRESH_DONE    101
#define DATA_MSG_INPUT_EVENT     102
#define DATA_MSG_BUFS_READY      200
#define DATA_MSG_WINDOW_EVENT    300
#define DATA_MSG_WINDOW_COMMAND  301

#define MAX_BUFS 8

struct ctrl_msg {
    uint32_t type;
    uint32_t size;
    uint8_t  payload[];
} __attribute__((packed));

struct data_msg {
    uint32_t type;
    uint32_t size;
    uint8_t  payload[];
} __attribute__((packed));

struct screen_info {
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t refresh;
} __attribute__((packed));

struct buf_info {
    uint32_t stride;
    uint32_t format;
    uint64_t modifier;
    uint32_t offset;
} __attribute__((packed));

#define INPUT_TYPE_TOUCH          1
#define INPUT_TYPE_KEY            2
#define INPUT_TYPE_POINTER_MOTION 3
#define INPUT_TYPE_POINTER_BUTTON 4
#define INPUT_TYPE_POINTER_AXIS   5
#define INPUT_TYPE_TOUCH_FRAME    6

#define INPUT_ACTION_DOWN    0
#define INPUT_ACTION_UP      1
#define INPUT_ACTION_MOVE    2

struct InputEvent {
    uint32_t type;
    union {
        struct {
            int32_t  action;
            float    x;
            float    y;
            int32_t  pointer_id;
        } touch;
        struct {
            int32_t  action;
            int32_t  keycode;
        } key;
        struct {
            float    x;
            float    y;
            float    dx;
            float    dy;
        } pointer_motion;
        struct {
            uint32_t button;
            int32_t  pressed;
        } pointer_button;
        struct {
            uint32_t axis;
            float    value;
            int32_t  discrete;
        } pointer_axis;
    };
} __attribute__((packed));

#define WINDOW_EVENT_OPENED      1
#define WINDOW_EVENT_CLOSED      2
#define WINDOW_EVENT_MINIMIZED   3
#define WINDOW_EVENT_RESTORED    4

#define WINDOW_COMMAND_RESTORE   1

#define WINDOW_EVENT_APP_ID_MAX  128
#define WINDOW_EVENT_TITLE_MAX   128

struct WindowEvent {
    uint32_t type;
    uint32_t window_id;
    uint32_t pid;
    char     app_id[WINDOW_EVENT_APP_ID_MAX];
    char     title[WINDOW_EVENT_TITLE_MAX];
} __attribute__((packed));

struct WindowCommand {
    uint32_t type;
    uint32_t window_id;
} __attribute__((packed));

#endif
