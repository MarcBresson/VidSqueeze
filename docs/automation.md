# Launcher shortcuts

Long-press the Squeeze icon in the launcher for three actions:

| Shortcut | Effect |
|---|---|
| *Run auto compression now* | Scans the library and queues what the age rules select, now, whatever the time window says. The charger and battery gates still apply. Only shown while auto compression is switched on. |
| *Pause the queue* | Pauses the whole queue, like the button in History. |
| *Resume the queue* | Resumes it. |

A short toast confirms what happened (for example "Queued 12 videos (3.4 GB)" or why nothing started). The shortcuts are always there, with no switch or token: they start `app.squeeze.shortcuts.ShortcutActivity`, which is **not exported**, so only the launcher can fire them.

## Samsung Routines and similar

Routines own the conditions: *If* [location / Wi-Fi / charger / bedtime] *then* launch the **Run auto compression now** shortcut; *when the condition ends* launch **Pause the queue**. Any tool that can launch an app shortcut works the same way.

## What was removed

Earlier builds had an *Automation* section with a secret token, an exported broadcast receiver (`adb shell am broadcast ... AutomationReceiver`), outgoing batch events and a log of received commands. Android cannot tell which app sent a broadcast, so there was no safe way to let only chosen apps use it, and the token was a burden. All of it is gone; the token left in older installs is deleted at the first start. Tasker / MacroDroid broadcasts no longer work: use the shortcuts, or a launcher-shortcut action in those tools.
