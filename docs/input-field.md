# Terminal input field

The **Aa** control in the main top bar opens a separate Android `EditText`
above the extra-keys toolbar. It starts closed. This is a local draft, like
Termux's additional text-input field, not a mirror of the shell's command line.
It works with every session type and is independent of the extra-key layout,
toolbar visibility, and the existing **Rich keyboard** setting.

## Editing and sending

- Type, swipe-type, compose, accept corrections, move the caret, select, delete,
  or paste into the field using ordinary Android text editing. None of those
  operations sends bytes to the terminal.
- **Send** transfers the visible draft through the terminal's paste encoder,
  without adding Enter. Use it to insert text into a shell prompt or editor.
- **Run** transfers the draft through the same encoder, then sends one Enter
  through the existing terminal key API. This is the only control that adds
  Enter. Empty drafts do nothing.
- Keyboard GO/SEND/DONE actions never submit. A physical Enter or a keyboard
  newline can edit the multiline local draft, but cannot execute it. Composition
  is finalized locally when an explicit transfer succeeds; the draft is then
  cleared into a fresh editor/IME context. A rejected transfer retains both the
  text and local composition.
- The transfer respects the application's bracketed-paste mode and newline
  normalization. Sticky Ctrl/Alt do not modify either the pasted text or Run's
  appended Enter, including for a one-character draft.
- **Send is not a security boundary:** a draft containing newlines may execute
  commands in a shell without bracketed paste. Review multiline text before
  either transfer. Run also executes any text already present at the remote
  prompt; the draft is not a replacement for that remote line.
- Extra keys and direct terminal input still address the terminal immediately,
  not the draft. Use the IME or a hardware keyboard while the draft has focus
  to edit it; close Aa for ordinary raw-key terminal operation.

Hide the field with Aa or Back without discarding its text. When search is
open, Back closes search first and returns focus to the open draft; a subsequent
Back closes the draft. Explicitly opening Aa raises the keyboard even if the
**Touch keyboard** automatic-opening preference is disabled.

## Keyboard preferences and accessibility

**Settings → Keyboard → Terminal input field** enables or disables the Aa
control (enabled by default). Disabling closes the editor but retains drafts for
live sessions. It does not change the extra-key configuration or Rich keyboard.

The input type is normal, multiline text with `AUTO_CORRECT`, without
`NO_SUGGESTIONS`; a compatible keyboard can supply suggestions, autocorrect,
and gesture typing. Those features ultimately depend on the installed IME and
its own language/preferences, and are not implemented by the terminal.

Automatic capitalization is **off by default**, because commands, paths, and
identifiers are case-sensitive. **Capitalize draft sentences** requests sentence
capitalization for prose. It affects only the draft keyboard flags, not existing
text, raw terminal input, or the rich-keyboard mirror. Autocorrect can also alter
commands: review the visible draft before sending it.

Aa has explicit **Show terminal input field** / **Hide terminal input field**
accessibility labels and an open/closed state description on Android 11+.
The draft, Send, and Run have descriptive accessibility labels; controls are
focusable and support accessibility activation without relying on gestures.
The new toggle and submission controls use at least 48dp touch targets. The
editor and buttons follow the current terminal-derived chrome palette.

## Session and lifecycle safety

`ui/TerminalInputFieldView.java` owns the editing UI. Its process-local weak-key
map stores plain text and selection offsets by `TerminalSession` identity.
Values contain no session, Activity, editor, or composing-span references.
`MainActivity` binds the field on tab switches, checks the exact active live
session before transferring, and removes closed-session drafts during tab
updates. The current tab and open/closed flag survive Activity recreation.

Every session binding uses a **different EditText/Editable**. An IME callback
arriving for a detached old editor cannot edit the newly selected tab's draft.
An opaque editor generation also prevents old callbacks from overwriting a
newer draft after returning to the same tab, recreation, or successful transfer.
IME key events are routed only to their original visible, focused editor, never
to the window's current focus; a delayed Enter cannot reach the terminal or Run.
No IME callback has a path to submission. Switching sessions drops composing
spans instead of carrying composition across terminals. Draft text and caret
selection survive tab switches, hiding, opening settings, and Activity
recreation while the session lives. Closing a tab cannot transfer its draft to
a newly created session, even if the new tab has the same label/index.

Drafts are deliberately **not persisted** in preferences, files, Android view
saved state, or saved-state Bundles, and the editor subtree opts out of Android
autofill and content capture. Process death loses them. The field is
normal suggested text, not a password editor: avoid drafting secrets if your
keyboard's suggestions/learning or screen visibility are a concern.

## Validation

`app/src/androidTest/java/io/github/sylirre/terminal/TerminalInputFieldUiTest.java`
exercises real EditText InputConnections and shell-backed explicit transfers:

- composing replacements, commits, deletions, batch edits and ignored IME actions;
- Send versus Run, composing-span finalization and empty-draft behavior;
- literal single-character transfers despite sticky Ctrl;
- Unicode and multiline drafts;
- independent per-tab drafts, delayed callbacks from an old editor, caret
  retention and Activity recreation, including old callbacks after rebinding;
- empty/rejected transfers and old IME callbacks after successful Send;
- hidden-editor IME key events cannot be redirected to the terminal;
- multiline height limits, saved-state exclusion, and rich-keyboard independence;
- resumed search keeps keyboard focus instead of redirecting input;
- exited-session draft isolation;
- settings toggles, independent toolbar visibility and capitalization flags;
- search/Back focus and draft retention.

The Send case proves the negative rather than sampling it once: after Send the
shell must echo the text, then neither the command's marker file nor its
computed output (`$((6*7))`, so echoed input cannot match) may appear for a
bounded polling window (`assertNeverWithin`). A positive control follows: a
separate Enter must then run the very same line, so the absence was not a
broken or slow command. Empty Run is checked the same way against re-running
the previous command. The suite saves and restores the input-field, extra-keys,
touch-keyboard and rich-keyboard settings around every test.

The suite has been run on API 34 x86_64; that does not establish Pixel/arm64,
API 29/36, or 16 KiB-device compatibility. Actual autocorrection/swipe behavior still needs
a suitable IME/device.

Run the suite with an Android device/emulator and the normal instrumented build:

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.sylirre.terminal.TerminalInputFieldUiTest
```

Actual autocorrection/swipe results are keyboard-dependent; verify those
interactively with a keyboard that supports them. The instrumentation suite
checks the requested input flags and composing semantics rather than pretending
to implement or predict an IME's language model.
