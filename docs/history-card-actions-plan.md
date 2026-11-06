# Plan: pending-result actions move into the history media cards

Status: ready for implementation · Agreed with Marc on 2026-11-06.

## Goal

In the History tab a finished-but-unused batch currently shows **twice**: a
`ReadyCard` pinned on top (Reshare / Replace originals / Save copies / Discard)
and again below as a normal history card. This rework deletes the "Ready"
section: awaiting batches are ordered like every other entry (by finish time),
and the action buttons live on the media cards themselves — batch-level buttons
on the batch header, per-video buttons on each video's row (icons collapsed,
icon+text when expanded, icon-only always visible for single-video batches).

Decisions already made (do not re-litigate):

1. Per-video actions are real **item-level finalize** (new plumbing), not a batch
   action in disguise.
2. Acting on one video leaves the batch's other videos `PENDING`; batch-level
   buttons then apply only to the still-pending ones.
3. Per-video buttons: all four (Reshare, Replace original, Save copy, Discard).
   Discard keeps a confirmation dialog.
4. Batch-level buttons sit on the batch header line, always visible while the
   batch still has pending results.
5. Once every video is handled, the batch settles into normal history (no
   buttons). This already works: `ResultsRepository.refreshStatus` flips
   `AWAITING_ACTION` → `DONE` when nothing is pending.
6. History is ordered by finish time (`finishedAt ?: createdAt`), newest first.
   The running/paused queue section stays pinned at the top.
7. Single-video batch: buttons always visible on the item row, not only when
   expanded.
8. Buttons are icons when collapsed; icon+text when expanded; long-press (or
   hover) on an icon reveals its label.
9. Replace goes through the existing `FinalizeActivity` consent flow, just
   item-scoped.
10. The results notification still acts per batch — untouched.
11. Reshare and Play stay available for already-finalized items (Reshare shares
    the saved/replaced file; Play already handles this via `PlayButton`).

No DB schema change, no Room migration.

---

## Phase 1 — item-level finalize (package `app.squeeze.finalize`)

### 1.1 `FinalizePlanner` (FinalizePlanner.kt)

Make the per-item replace kind public so `Finalizer` can reuse it for one item:

```kotlin
fun replaceKind(item: BatchItemEntity): ReplaceKind   // rename/relax private kindFor()
```

`ReplacePlan.itemsOf`/`kindOf` stay as they are.

### 1.2 `Finalizer` (Finalizer.kt)

The per-item building blocks already exist as private functions
(`saveCopy(item, report)`, `overwrite`, `swap`, `attempt`, `discard` body).
Add **public item-scoped** entry points and re-express the batch ones through
them so there is one code path:

```kotlin
/** A DONE item with a staged result still waiting for a decision, or null. */
private fun pendingResult(itemId: Long): BatchItemEntity?   // results.item(id), then FinalizePlanner-style check

suspend fun saveCopyItem(itemId: Long): FinalizeReport      // attempt { saveCopy(item, FinalizeReport()) }
suspend fun replaceItem(itemId: Long): FinalizeReport       // kind = FinalizePlanner.replaceKind(item); SAVE_COPY → saveCopy, IN_PLACE → overwrite, SWAP → swap
suspend fun discardItem(itemId: Long)                        // deleteStaged + results.markFinalized(item, DISCARDED, null)

/** Share chooser for one item: the staged file while pending, the finalUri once finalized. */
suspend fun shareIntentItem(itemId: Long): Intent?           // pending → FileProvider uri of staged; DONE+SAVED/REPLACED → finalUri; else null

/** Originals the user must approve before replaceItem may overwrite them. */
suspend fun needsWriteConsentItem(itemId: Long, trusted: Boolean = true): List<Uri>  // only when replaceKind == IN_PLACE; same hasWriteAccess filter as needsWriteConsent
```

Then refactor the existing batch methods to delegate:

- `saveCopies(batchId)` = fold `results.pending(batchId)` calling `saveCopyItem(it.id)`.
- `replace(batchId)` = fold pending items using `replaceItem` logic (keep the
  single `ReplacePlan` computation if convenient; behavior identical).
- `discard(batchId)` = fold pending items calling `discardItem(it.id)`.

Guards: item-scoped methods must no-op (empty report / null / empty list) when
the item is not `DONE` + `PENDING` + `stagedPath != null` — a double-tap or a
stale card must never throw or double-run. `discardItem` on an already-finalized
item is a no-op.

Note: reshare does **not** change `finalizeState` (same as today's batch share).

### 1.3 `FinalizeManager` (FinalizeManager.kt)

Inject `ResultsRepository` (constructor param). Add:

```kotlin
fun saveItem(itemId: Long): UUID    // enqueue worker: KEY_ITEM = itemId, action SAVE
fun replaceItem(itemId: Long): UUID // same, action REPLACE
fun discardItem(itemId: Long)       // scope.launch { finalizer.discardItem(itemId); maybeCancelNotification(itemId) }
private suspend fun maybeCancelNotification(itemId: Long)  // batchId = results.item(itemId)?.batchId; if (results.pending(batchId).isEmpty()) notifications.cancelResult(batchId)
```

Worker unique names: rename the batch one to `"finalize-batch-$batchId"` and use
`"finalize-item-$itemId"` for item work, both `ExistingWorkPolicy.APPEND_OR_REPLACE`.
Distinct names are intentional: a batch finalize and an item finalize may both be
alive; both only ever touch *currently pending* items and `markFinalized` clears
`stagedPath`, so a straggler run finds nothing and is a harmless no-op.

### 1.4 `FinalizeWorker` (FinalizeWorker.kt)

Add `KEY_ITEM = "item"` (default `-1`). In `doWork()`:

- item mode (`itemId >= 0`): run `finalizer.saveCopyItem`/`replaceItem`; resolve
  `batchId = results.item(itemId)?.batchId` for the notification; `stillPending =
  results.pending(batchId).isNotEmpty()` as today.
- batch mode: unchanged.

`getForegroundInfo()` unchanged (action-based).

### 1.5 `FinalizeActivity` (FinalizeActivity.kt)

Add `EXTRA_ITEM` and:

```kotlin
fun itemIntent(context: Context, itemId: Long, action: FinalizeAction)  // alongside the existing intent()
```

In `onCreate`, read `itemId` (default `-1`). When in item mode:

- `SHARE` → `finalizer.shareIntentItem(itemId)` then the existing
  chooser/finish/toast path.
- `REPLACE` → `askThenReplace` uses `finalizer.needsWriteConsentItem(itemId, …)`
  and `runReplace` uses `manager.replaceItem(itemId)`. The rest of the
  consent/refusal/delete dance (`writeConsent`, `askedAfterRefusal`,
  `deleteConsent`, worker output keys) is unchanged and works per item as-is.
- `SAVE` → `manager.saveItem(itemId)`, finish.

Keep the batch branch byte-identical for the notification and shortcuts
(`FinalizeReceiver`, `ShortcutAction`, `NotificationHelper` all keep using the
batch intent).

---

## Phase 2 — ordering (package `app.squeeze.data`)

### 2.1 `HistoryBuilder` (Stats.kt, ~line 92)

Sort by finish time instead of creation time:

```kotlin
return batches.sortedByDescending { it.finishedAt ?: it.createdAt }.mapNotNull { ... }
```

(`observeFinished()` already returns `AWAITING_ACTION` batches — they are the
duplicate entries today — so no DAO change is needed.)

### 2.2 `HistoryEntry` (Stats.kt)

Add a helper so the UI does not re-derive the predicate:

```kotlin
/** Items whose compressed result still waits for a decision. */
val pendingResults: List<BatchItemEntity> get() = FinalizePlanner.pending(items)
val hasPendingResults: Boolean get() = pendingResults.isNotEmpty()
```

`Stats.kt` may need `import app.squeeze.finalize.FinalizePlanner`
(ResultsRepository already does this, so the dependency direction is fine).

---

## Phase 3 — UI (packages `feature.history`, `feature.queue`, `ui`)

### 3.1 New `ui/IconAction.kt` — the compact action button (Q8)

One reusable composable used by both batch and item action rows:

```kotlin
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
)
```

Behavior:
- Renders a 40.dp icon button. A long-press (`Modifier.combinedClickable`) or
  hover toggles a small label that appears directly below the icon (a
  `Text(label, style = MaterialTheme.typography.labelSmall)` in a
  `Column`), disappearing on the next click/long-press. The label is also shown
  permanently while the host card is expanded — implement by giving `IconAction`
  a `showLabel: Boolean` parameter the caller sets from the expanded state.
- `contentDescription = label` always, so talk-back is fine when hidden.

### 3.2 `feature/queue/ActiveJobs.kt`

- Delete the `ready` section from `activeJobs()` (`"ready-title"` item and the
  `ReadyCard` items) — the running/paused controls and `QueueRow` stay.
- Delete the `ReadyCard` composable and the discard dialog that lives inside it
  (its logic moves to the history card, see 3.4).
- `ActiveJobsActions`: drop `onSaveCopies` and `onDiscard` (only
  `onPause/onResume/onCancelAll/onRemove` remain). Update the one construction
  site in `HistoryViewModel`.
- **Keep** `QueueUi.ready` and `ResultsRepository.observeAwaiting()`: the Home
  screen's `JobProgressWidget` still counts `queue.ready.size`
  (HomeComponents.kt:114) and both view models still build `QueueUi` from
  `observeAwaiting()`.

### 3.3 `HistoryViewModel`

- `jobActions` shrinks to the four remaining fields.
- Add the action surface the cards need (same save-in-background /
  activity-for-consent split as the old `ReadyCard`):

```kotlin
val batchActions = BatchResultActions(          // simple data class in this package or reuse of names
    onSaveCopies = { finalize.save(it) },       // batchId
    onDiscard = { finalize.discard(it) },       // batchId
)
val itemActions = ItemResultActions(
    onSaveCopy = { finalize.saveItem(it) },     // itemId
    onDiscard = { finalize.discardItem(it) },   // itemId
)
```

Reshare and Replace are launched from the composables with `LocalContext`
(exactly like the old `ReadyCard` did):

- batch: `FinalizeActivity.intent(context, batchId, FinalizeAction.SHARE / REPLACE)`
- item: `FinalizeActivity.itemIntent(context, itemId, …)`

### 3.4 `HistoryScreen.kt` — the core of the rework

**`HistoryList`**
- Drop `jobs.ready` from the empty check (`entries.isEmpty() && jobs.items.isEmpty()`)
  since awaiting batches now appear in `entries`.
- `activeJobs(jobs, vm.jobActions)` stays (it now renders only the queue).

**`HistoryCard`**
- Single item: `ItemBlock(single, …, actionsVisibleAlways = true)`.
- Batch: `BatchHeader(entry)` then items; header shows the batch action row
  when `entry.hasPendingResults`.

**`BatchHeader`** — add the always-visible batch action row (Q4), shown only
while `entry.hasPendingResults`:

- Four `IconAction`s: Reshare (`Icons.Outlined.Share`), Replace originals
  (`Icons.Outlined.SwapHoriz`), Save copies (`Icons.Outlined.FileDownload`),
  Discard (`Icons.Outlined.Delete`) — `material-icons-extended` is already a
  dependency (app/build.gradle.kts:101), pick Outlined variants to match M3 tone.
- Wiring: Reshare/Replace launch `FinalizeActivity` with the **batch** id;
  Save/Discard call `vm.batchActions`; Discard first shows the confirmation
  `AlertDialog` (reuse strings `ready_discard_title` / `ready_discard_text`,
  `android.R.string.cancel`), with `confirmDiscard` remembered via
  `rememberSaveable(entry.batch.id)`.
- When some items were handled individually, the same buttons simply act on the
  remaining pending ones — `Finalizer` already filters by pending, nothing extra
  to do. When the last item is handled the row disappears by itself
  (`AWAITING_ACTION` → `DONE` via Room flow).

**`ItemBlock`** — per-video actions (Q3, Q7, Q8, Q11):

- A pending item is `status == DONE && finalizeState == PENDING && stagedPath != null`
  (reuse `entry.pendingResults` membership or re-check per item — pick one, the
  helper in `HistoryEntry` is the source of truth).
- **Collapsed**: trailing icon-only `IconAction`s for Reshare / Replace / Save
  copy / Discard in the name row (next to the existing `OutcomePill`, compare
  and `PlayButton`; if the row gets crowded on narrow screens, put the action
  icons on the sizes line, right-aligned — decide by eye, keep it one row).
  For a **single-video batch** these icons are shown always (Q7); for a
  multi-video batch, icons appear only when `open` is true — except that
  expanding is what reveals the full buttons, so collapsed multi-video items
  show no per-item actions (the batch header carries them).
- **Expanded**: a `FlowRow` under the details with icon+text buttons
  (`IconAction(showLabel = true)` or a sibling `TextAction` composable — one
  widget, `showLabel` flag, per 3.1). Include the same four actions.
- **Discard**: per-item confirmation dialog, `rememberSaveable(item.id)`.
- **Always (Q11)**: `PlayButton` stays as today; add a Reshare icon for
  finalized items (`SAVED` / `REPLACED` / `SHARED_ONLY`) next to it, launching
  `FinalizeActivity.itemIntent(context, item.id, SHARE)` —
  `Finalizer.shareIntentItem` resolves staged vs finalUri.
- Buttons hide automatically once the item is finalized (`finalizeState` flips
  via Room flow). No manual state refresh anywhere.

### 3.5 Strings (res/values/strings.xml, only `values/` exists — no locales)

Reuse where the wording fits: `ready_reshare` ("Reshare"), `ready_discard`,
`ready_discard_title`, `ready_discard_text`. Add singular forms for items and
descriptions:

```xml
<string name="history_item_replace">Replace original</string>
<string name="history_item_save_copy">Save copy</string>
```

(Use these for both the collapsed icon `contentDescription` and the expanded
label; batch buttons keep the existing plural strings.) After removing
`ReadyCard`, grep `R.string.ready_` — `ready_title`, `ready_summary`,
`ready_replace`, `ready_save`, `ready_save_started` become unused unless the
notification uses them; delete only what grep proves unused.

---

## Phase 4 — tests

- **`FinalizerTest` (androidTest)** — add:
  - `savingOneItemLeavesTheOthersPending`: batch of 2 pending, `saveCopyItem(id1)`
    → item1 `SAVED` with `finalUri`, item2 still `PENDING`, batch still
    `AWAITING_ACTION`.
  - `discardingOneItemLeavesTheOthersPending` (same shape, `DISCARDED`, staged
    file of item1 gone).
  - `replacingOneItemOnlyTouchesThatItem` (reuse the in-place fixture from
    `replaceOverwritesTheOriginalInPlace`).
  - `actingOnAnAlreadyFinalizedItemIsANoOp`: `saveCopyItem`/`discardItem` after
    finalize → returns empty report, no exception, nothing changes.
  - `reshareSharesTheSavedCopyAfterFinalizing`: `shareIntentItem` before
    (staged) and after `saveCopyItem` (finalUri) — both non-null, second points
    at the saved uri.
- **`HistoryRepositoryTest` (androidTest)** — update/add: entries are ordered by
  `finishedAt ?: createdAt`, newest first; an `AWAITING_ACTION` batch appears in
  history with `hasPendingResults == true`.
- **Unit tests** — run the whole suite; `BatchSummaryTest`, `StatsTest`,
  `FinalizePlannerTest` should be unaffected but check `FinalizePlannerTest`
  covers the renamed `replaceKind` (rename the call sites in the test too).
- Instrumented: `./gradlew connectedDebugAndroidTest` if an emulator is
  available; otherwise at minimum `./gradlew testDebugUnitTest assembleDebug`
  must pass.

---

## Implementation order & checkpoints

1. Phase 1 (finalize plumbing) → `./gradlew testDebugUnitTest assembleDebug`.
2. Phase 2 (ordering + `HistoryEntry` helpers) → unit tests + repo test.
3. Phase 3 (UI) → build + manual smoke on emulator: pending single, pending
   batch, mixed batch (act on one video), fully finalized batch, long-press
   label reveal, discard dialogs, reshare of a saved copy.
4. Phase 4 (tests) → full suite.

## Pitfalls for the implementer

- Do not touch `FinalizeReceiver`, `ShortcutAction`, `NotificationHelper` — they
  keep using batch-scoped intents (Q10).
- `HomeViewModel`/`JobProgressWidget` still consume `QueueUi.ready` — don't
  delete it, only the history rendering of it.
- Reshare must not change `finalizeState`.
- Item-scoped no-op guards are mandatory: the UI can double-fire while a Room
  recomposition is in flight.
- `CombinedClickable` needs
  `@OptIn(ExperimentalFoundationApi::class)`.
- `HistoryList`'s LazyColumn key is `it.batch.id`; per-item dialogs use
  `rememberSaveable(item.id)` so scroll state doesn't mix up confirmations.
