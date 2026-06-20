/*
 * ACGlass/Anland window event bridge.
 */

#ifndef WESTON_ANLAND_WINDOW_API_H
#define WESTON_ANLAND_WINDOW_API_H

#include <stdint.h>

#include <libweston/plugin-registry.h>

#ifdef  __cplusplus
extern "C" {
#endif

struct weston_compositor;

#define WESTON_ANLAND_WINDOW_API_NAME "weston_anland_window_api_v1"

enum weston_anland_window_event_type {
	WESTON_ANLAND_WINDOW_EVENT_OPENED = 1,
	WESTON_ANLAND_WINDOW_EVENT_CLOSED = 2,
	WESTON_ANLAND_WINDOW_EVENT_MINIMIZED = 3,
	WESTON_ANLAND_WINDOW_EVENT_RESTORED = 4,
};

enum weston_anland_window_command_type {
	WESTON_ANLAND_WINDOW_COMMAND_RESTORE = 1,
};

typedef void (*weston_anland_window_command_handler_t)(
	struct weston_compositor *compositor,
	uint32_t type,
	uint32_t window_id,
	void *userdata);

struct weston_anland_window_api {
	void (*send_window_event)(struct weston_compositor *compositor,
				  uint32_t type,
				  uint32_t window_id,
				  uint32_t pid,
				  const char *app_id,
				  const char *title);
	void (*set_window_command_handler)(
		struct weston_compositor *compositor,
		weston_anland_window_command_handler_t handler,
		void *userdata);
};

static inline const struct weston_anland_window_api *
weston_anland_window_get_api(struct weston_compositor *compositor)
{
	return (const struct weston_anland_window_api *)
		weston_plugin_api_get(compositor,
				      WESTON_ANLAND_WINDOW_API_NAME,
				      sizeof(struct weston_anland_window_api));
}

#ifdef  __cplusplus
}
#endif

#endif /* WESTON_ANLAND_WINDOW_API_H */
