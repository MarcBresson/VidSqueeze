# Automation API

Other apps (Samsung Modes & Routines, Tasker, MacroDroid, Automate, `adb`) can start and stop compression. Off by default: **Auto → Automation → Allow external automation** turns it on and creates a secret token.

Every command must carry the token as the string extra `token`. Commands without it, with a wrong one, or sent while the switch is off are refused and written to the **Received commands** log in the same screen (newest first, last 100). At most 20 commands per minute are accepted.

Action names are the same in every build variant. The package is `app.squeeze` (release) or `app.squeeze.debug`.

## Commands

| Action (`app.squeeze.action.…`) | Extras | Effect |
|---|---|---|
| `START_AUTO_RUN` | `duration_minutes`, `until` (`HH:mm`), `ignore_constraints`, `preset_override` (id or name), `dry_run` | Scans the library and queues what the age rules select, now, whatever the time window says. Needs auto compression to be switched on. The run ends at the earliest of `duration_minutes`, `until` and the end of the window that is open, if any. |
| `STOP_AUTO_RUN` | – | Cancels waiting auto items and the one being encoded. Whatever is left is picked up by the next scan. |
| `PAUSE_QUEUE`, `RESUME_QUEUE`, `CANCEL_ALL` | – | Controls the whole queue, like the buttons on the Queue screen. |
| `SET_AUTO_ENABLED` | `enabled` (`true`/`false`) | The master switch, so a routine can enable auto mode only in some contexts. |
| `COMPRESS_FOLDER` | `folder` (relative, e.g. `DCIM/Camera`), `older_than_days`, `preset` (id or name, default Share), `export_mode` (`save`, `replace`, `ask`), `ignore_constraints` | Queues the videos of one media folder (and its sub-folders) that are old enough, with that preset. Works without auto mode being on. |

Numbers and booleans may be sent as text, which is what Tasker and MacroDroid do. The charger and battery conditions of the Auto screen apply unless `ignore_constraints` is `true`. Folders are MediaStore-relative: absolute paths, `..` and URIs are refused.

## Entry points

- **Broadcast** (Tasker, MacroDroid, Automate, Routine+ "Send intent", adb):
  ```
  adb shell am broadcast -n app.squeeze/app.squeeze.automation.AutomationReceiver \
      -a app.squeeze.action.START_AUTO_RUN --es token <token> --ei duration_minutes 120
  ```
  The result code is 1 when accepted and 0 when refused; the result data says why or what was queued.
- **Activity** `app.squeeze.automation.AutomationTriggerActivity`: the same actions and extras through `startActivity`, with no visible UI. Starting an activity gives the app a foreground context, which a bare broadcast from the background does not.
- **Launcher shortcuts** *Run auto compression now*, *Pause the queue*, *Resume the queue* are published while automation is on. They call the activity and carry the token inside the shortcut, which only the launcher can read. Samsung Routines can fire an app shortcut as its "launch app" action.

## Outgoing events (opt-in)

With **Tell other apps when a batch starts or finishes** on, Squeeze broadcasts `app.squeeze.event.BATCH_STARTED` (`batch_id`, `source`, `items`) and `app.squeeze.event.BATCH_FINISHED` (`batch_id`, `source`, `status`, `items`, `bytes_saved`). `source` is `AUTO`, `SHARE` or `PICKER`. No file names or paths are included. Register for them dynamically (Tasker's "Intent Received" does).

## Samsung Routines

Routines own the conditions: *If* [location / Wi-Fi / charger / bedtime] *then* launch the **Run auto compression now** shortcut; *when the condition ends* launch **Pause the queue** or send `STOP_AUTO_RUN`. `duration_minutes` and `until` are a safety net if the "ends" event is missed.

## Not covered yet

A Quick Settings tile and a Tasker/Locale plugin.
