/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static io.github.sylirre.terminal.TestUtil.waitFor;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Parcelable;
import android.text.InputType;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.sylirre.terminal.term.ScreenSnapshot;
import io.github.sylirre.terminal.term.SessionManager;
import io.github.sylirre.terminal.term.TerminalSession;
import io.github.sylirre.terminal.ui.AppSettings;
import io.github.sylirre.terminal.ui.MainActivity;
import io.github.sylirre.terminal.ui.TerminalInputFieldView;
import io.github.sylirre.terminal.ui.TerminalView;

/** Real local EditText/IME editing plus explicit transfer through the JNI/PTY path. */
@RunWith(AndroidJUnit4.class)
public class TerminalInputFieldUiTest {
    private static final long TIMEOUT_MS = 15_000;
    /**
     * How long a command must stay unexecuted to count as "not run". An idle
     * shell given a complete line runs a builtin-plus-touch in milliseconds.
     */
    private static final long NOT_EXECUTED_WINDOW_MS = 3_000;
    private ActivityScenario<MainActivity> scenario;
    private AppSettings settings;
    private boolean oldEnabled, oldCaps, oldExtraKeys, oldTouch, oldRich;

    @Before
    public void launch() {
        SessionManager.get().closeAll();
        settings = new AppSettings(ApplicationProvider.getApplicationContext());
        oldEnabled = settings.inputFieldEnabled();
        oldCaps = settings.inputFieldAutoCapitalization();
        oldExtraKeys = settings.extraKeysEnabled();
        oldTouch = settings.touchKeyboard();
        oldRich = settings.richKeyboard();
        settings.setInputFieldEnabled(true);
        settings.setInputFieldAutoCapitalization(false);
        settings.setExtraKeysEnabled(true);
        settings.setTouchKeyboard(false);
        settings.setRichKeyboard(false);
        scenario = ActivityScenario.launch(new Intent(ApplicationProvider.getApplicationContext(),
                MainActivity.class).putExtra(MainActivity.EXTRA_FORCE_SHELL, true));
        waitFor("first shell prompt", TIMEOUT_MS,
                () -> screen().contains("$"), this::screen);
    }

    @After
    public void cleanup() {
        if (scenario != null) scenario.close();
        SessionManager.get().closeAll();
        if (settings != null) {
            settings.setInputFieldEnabled(oldEnabled);
            settings.setInputFieldAutoCapitalization(oldCaps);
            settings.setExtraKeysEnabled(oldExtraKeys);
            settings.setTouchKeyboard(oldTouch);
            settings.setRichKeyboard(oldRich);
        }
    }

    private void openField() {
        onView(withContentDescription("Show terminal input field")).perform(click());
        onView(withContentDescription(TerminalInputFieldView.FIELD_DESCRIPTION))
                .check(matches(isDisplayed()));
    }

    private EditText editor(MainActivity activity) {
        return (EditText) described(activity.findViewById(R.id.root),
                TerminalInputFieldView.FIELD_DESCRIPTION);
    }

    private static View described(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null
                ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = described(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private TerminalSession current(MainActivity activity) {
        return ((TerminalView) activity.findViewById(R.id.terminal)).session();
    }

    /** The last non-blank screen line, trimmed (where the prompt sits). */
    private String lastLine() {
        String[] lines = screen().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) return lines[i].trim();
        }
        return "";
    }

    private String screen() {
        AtomicReference<String> result = new AtomicReference<>("");
        scenario.onActivity(a -> {
            TerminalSession s = current(a);
            if (s != null) {
                ScreenSnapshot snap = new ScreenSnapshot();
                s.emulator.snapshot(snap);
                result.set(snap.text());
            }
        });
        return result.get();
    }

    private boolean outputLine(String line) {
        AtomicReference<Boolean> found = new AtomicReference<>(false);
        scenario.onActivity(a -> {
            TerminalSession s = current(a);
            if (s == null) return;
            ScreenSnapshot snap = new ScreenSnapshot();
            s.emulator.snapshot(snap);
            for (int i = 0; i < snap.rows; i++) {
                if (line.equals(snap.rowText(i).trim())) found.set(true);
            }
        });
        return found.get();
    }

    private void newTab() {
        scenario.onActivity(a -> assertTrue(described(a.findViewById(R.id.tabs),
                a.getString(R.string.tab_new_description)).performClick()));
    }

    // Tab labels may be scrolled offscreen on a narrow device. These tests
    // exercise binding, not the TabStrip's scrolling gesture.
    private void selectTab(String text) {
        scenario.onActivity(a -> assertTrue(selectLabel(a.findViewById(R.id.tabs), text)));
    }

    private boolean selectLabel(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return ((View) view.getParent()).performClick();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (selectLabel(group.getChildAt(i), text)) return true;
            }
        }
        return false;
    }

    @Test
    public void composingCorrectionDeletionAndImeActionsRemainLocal() {
        openField();
        scenario.onActivity(a -> {
            EditText edit = editor(a);
            EditorInfo attrs = new EditorInfo();
            InputConnection ic = edit.onCreateInputConnection(attrs);
            assertTrue((attrs.inputType & InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) != 0);
            assertEquals(0, attrs.inputType & (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS));
            assertEquals(0, attrs.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            assertTrue(ic.beginBatchEdit());
            assertTrue(ic.setComposingText("ecoh", 1));
            assertTrue(ic.setComposingText("echo local", 1));
            assertTrue(ic.commitText("echo local!", 1));
            // EditableInputConnection returns whether another batch remains,
            // not whether ending this final batch succeeded.
            assertFalse(ic.endBatchEdit());
            assertEquals("echo local!", edit.getText().toString());
            assertTrue(ic.deleteSurroundingText(1, 0));
            assertEquals("echo local", edit.getText().toString());
            assertTrue(ic.performEditorAction(EditorInfo.IME_ACTION_GO));
            assertTrue(ic.performEditorAction(EditorInfo.IME_ACTION_SEND));
            assertTrue(ic.performEditorAction(EditorInfo.IME_ACTION_DONE));
            assertFalse("draft and IME actions never reach the PTY", current(a).userInteracted());
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            assertEquals("hardware Enter inserts a local newline", "echo local\n",
                    edit.getText().toString());
            assertFalse("hardware Enter edits locally, never executes", current(a).userInteracted());
        });
    }

    /** Joined screen text with all whitespace removed: immune to soft wraps. */
    private String unwrappedScreen() {
        return screen().replaceAll("\\s+", "");
    }

    /**
     * Asserts {@code condition} stays false for the whole bounded window,
     * polling like {@link TestUtil#waitFor} so a late effect is still caught.
     */
    private void assertNeverWithin(String what, long windowMs,
            java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + windowMs;
        while (System.currentTimeMillis() < deadline) {
            assertFalse(what + "\n" + screen(), condition.getAsBoolean());
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new AssertionError("interrupted", e);
            }
        }
        assertFalse(what + "\n" + screen(), condition.getAsBoolean());
    }

    @Test
    public void sendTransfersTextWithoutAddingEnterAndRunExecutesExplicitly() {
        // Each command leaves a unique marker file and prints a line that its
        // own (echoed, possibly soft-wrapped) text never contains: "$((6*7))"
        // expands to 42 only when the shell executes it. Both are therefore
        // proof of execution independent of screen width.
        String id = Long.toString(System.nanoTime(), 36);
        File dir = ApplicationProvider.getApplicationContext().getFilesDir();
        File sendMarker = new File(dir, "field-send-" + id);
        File runMarker = new File(dir, "field-run-" + id);
        String sendOutput = "sent_42_" + id;
        String runOutput = "ran_42_" + id;
        try {
            openField();
            scenario.onActivity(a -> editor(a).setText("touch " + sendMarker.getAbsolutePath()
                    + " && echo sent_$((6*7))_" + id));
            onView(withContentDescription(TerminalInputFieldView.SEND_DESCRIPTION)).perform(click());
            scenario.onActivity(a -> {
                assertTrue(current(a).userInteracted());
                assertEquals("", editor(a).getText().toString());
            });
            // The shell has received the text: it echoes it on its edit line.
            waitFor("Send transferred the command text", TIMEOUT_MS,
                    () -> unwrappedScreen().contains("sent_$((6*7))_" + id), this::screen);
            assertNeverWithin("Send must not execute the command", NOT_EXECUTED_WINDOW_MS,
                    () -> sendMarker.exists() || outputLine(sendOutput));
            // Positive control: the very same line runs on a separate Enter,
            // so the absence above was not a broken or slow command.
            scenario.onActivity(a -> ((TerminalView) a.findViewById(R.id.terminal))
                    .dispatchKey(KeyEvent.KEYCODE_ENTER));
            waitFor("Send waits for separate Enter", TIMEOUT_MS,
                    () -> sendMarker.exists() && outputLine(sendOutput), this::screen);

            scenario.onActivity(a -> {
                EditText edit = editor(a);
                InputConnection ic = edit.onCreateInputConnection(new EditorInfo());
                ic.setComposingText("touch " + runMarker.getAbsolutePath()
                        + " && echo ran_$((6*7))_" + id, 1);
                assertTrue(BaseInputConnection.getComposingSpanStart(edit.getText()) >= 0);
            });
            onView(withContentDescription(TerminalInputFieldView.RUN_DESCRIPTION)).perform(click());
            waitFor("explicit Run output", TIMEOUT_MS,
                    () -> runMarker.exists() && outputLine(runOutput), this::screen);
            assertTrue("Run marker removable", runMarker.delete());
            // Settle on the fresh prompt first: a stray Enter would add a new
            // prompt line, which the marker file alone can never show.
            waitFor("prompt after Run", TIMEOUT_MS, () -> lastLine().endsWith("$"), this::screen);
            String settled = screen();
            scenario.onActivity(a -> {
                assertEquals("", editor(a).getText().toString());
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor(a).getText()));
                // Empty Run must not repeat the previous command or send Enter.
                assertTrue(described(a.findViewById(R.id.root),
                        TerminalInputFieldView.RUN_DESCRIPTION).performClick());
            });
            assertNeverWithin("empty Run must not re-run the previous command",
                    NOT_EXECUTED_WINDOW_MS, runMarker::exists);
            assertEquals("empty Run must not send Enter", settled, screen());
        } finally {
            sendMarker.delete();
            runMarker.delete();
        }
    }

    @Test
    public void singleCharacterDraftAndRunIgnoreStickyCtrl() {
        openField();
        scenario.onActivity(a -> {
            TerminalView terminal = a.findViewById(R.id.terminal);
            // The command text never contains the expected output line, so a
            // soft-wrapped echo of it cannot be mistaken for the result.
            terminal.dispatchText("printf 'sticky_%s\\n' ");
            TerminalView.StickyModifiers modifiers = new TerminalView.StickyModifiers();
            modifiers.ctrl = true;
            terminal.setStickyModifiers(modifiers);
            editor(a).setText("x");
        });
        onView(withContentDescription(TerminalInputFieldView.RUN_DESCRIPTION)).perform(click());
        waitFor("draft is literal x, not Ctrl-X or Ctrl-Enter", TIMEOUT_MS,
                () -> outputLine("sticky_x"), this::screen);
    }

    @Test
    public void unicodeMultilineDraftIsTransferredOnlyOnExplicitRun() {
        openField();
        scenario.onActivity(a -> {
            // $((6*7)) expands only on execution: the output lines never
            // appear in the (possibly soft-wrapped) command echo. The wide 雪
            // goes last: rowText renders its spacer cell as a blank.
            editor(a).setText("echo café_$((6*7))\necho $((6*7))_雪");
            assertFalse(current(a).userInteracted());
        });
        onView(withContentDescription(TerminalInputFieldView.RUN_DESCRIPTION)).perform(click());
        waitFor("both multiline Unicode outputs", TIMEOUT_MS,
                () -> outputLine("café_42") && outputLine("42_雪"), this::screen);
    }

    @Test
    public void draftsArePerSessionAndSurviveHideAndRecreationWithoutComposition() {
        openField();
        AtomicReference<InputConnection> oldConnection = new AtomicReference<>();
        AtomicReference<EditText> oldEditor = new AtomicReference<>();
        scenario.onActivity(a -> {
            EditText edit = editor(a);
            oldEditor.set(edit);
            InputConnection ic = edit.onCreateInputConnection(new EditorInfo());
            oldConnection.set(ic);
            ic.setComposingText("tab-one-draft", 1);
            edit.setSelection(3, 7);
        });
        newTab();
        waitFor("second session", TIMEOUT_MS, () -> SessionManager.get().sessions().size() == 2);
        scenario.onActivity(a -> {
            assertNotSame(oldEditor.get(), editor(a));
            assertEquals("", editor(a).getText().toString());
            // A delayed callback belongs to the old Editable, not tab two.
            oldConnection.get().setSelection(0, oldEditor.get().length());
            oldConnection.get().commitText("tab-one-final", 1);
            assertEquals("", editor(a).getText().toString());
            editor(a).setText("tab-two-draft");
            editor(a).setSelection(4, 8);
        });
        onView(withContentDescription("Hide terminal input field")).perform(click());
        onView(withContentDescription(TerminalInputFieldView.FIELD_DESCRIPTION))
                .check(matches(not(isDisplayed())));
        openField();
        scenario.recreate();
        scenario.onActivity(a -> {
            assertEquals(SessionManager.get().sessions().get(1), current(a));
            assertEquals("tab-two-draft", editor(a).getText().toString());
            assertEquals(4, editor(a).getSelectionStart());
            assertEquals(8, editor(a).getSelectionEnd());
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor(a).getText()));
            assertFalse(current(a).userInteracted());
        });
        selectTab("sh:1");
        scenario.onActivity(a -> {
            assertEquals("tab-one-final", editor(a).getText().toString());
            assertFalse(current(a).userInteracted());
        });
    }

    @Test
    public void exitingSessionCannotLeakDraftIntoANewTab() {
        openField();
        scenario.onActivity(a -> editor(a).setText("keep-first"));
        newTab();
        scenario.onActivity(a -> {
            editor(a).setText("discard-on-close");
            ((TerminalView) a.findViewById(R.id.terminal)).dispatchText("exit\n");
        });
        waitFor("second session exits", TIMEOUT_MS, () -> SessionManager.get().sessions().size() == 1);
        scenario.onActivity(a -> assertEquals("keep-first", editor(a).getText().toString()));
        newTab();
        scenario.onActivity(a -> {
            assertEquals("", editor(a).getText().toString());
            assertFalse(current(a).userInteracted());
        });
    }

    @Test
    public void settingsDisableControlIndependentlyOfExtraKeysAndPreserveDraft() {
        openField();
        scenario.onActivity(a -> editor(a).setText("saved-local"));
        onView(withId(R.id.settings_button)).perform(click());
        onView(withText("Terminal input field")).perform(scrollTo(), click());
        pressBack();
        onView(withContentDescription("Show terminal input field"))
                .check(matches(not(isDisplayed())));
        onView(withContentDescription(TerminalInputFieldView.FIELD_DESCRIPTION))
                .check(matches(not(isDisplayed())));
        onView(withId(R.id.extra_keys)).check(matches(isDisplayed()));
        onView(withId(R.id.settings_button)).perform(click());
        onView(withText("Terminal input field")).perform(scrollTo(), click());
        // Let Espresso observe the completed scroll/layout before injecting
        // the tap; this row moves when the draft-dependent setting re-enables.
        onView(withText(R.string.setting_extra_keys_enabled_title)).perform(scrollTo());
        onView(withText(R.string.setting_extra_keys_enabled_title)).perform(click());
        assertFalse("settings click disables the toolbar preference", settings.extraKeysEnabled());
        pressBack();
        assertFalse("returning to MainActivity preserves the toolbar preference", settings.extraKeysEnabled());
        onView(withId(R.id.extra_keys)).check(matches(not(isDisplayed())));
        openField();
        scenario.onActivity(a -> assertEquals("saved-local", editor(a).getText().toString()));
    }

    @Test
    public void capitalizationPreferenceOnlyChangesDraftKeyboard() {
        openField();
        scenario.onActivity(a -> editor(a).setText("lowercase-command"));
        onView(withId(R.id.settings_button)).perform(click());
        onView(withText("Capitalize draft sentences")).perform(scrollTo(), click());
        pressBack();
        assertTrue(settings.inputFieldAutoCapitalization());
        scenario.onActivity(a -> {
            assertTrue((editor(a).getInputType() & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0);
            assertTrue((editor(a).getInputType() & InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) != 0);
            assertEquals("lowercase-command", editor(a).getText().toString());
            assertFalse(current(a).userInteracted());
            assertFalse(settings.richKeyboard());
        });
    }

    @Test
    public void rejectedAndEmptyTransfersRetainDraftAndSuccessfulSendFencesOldIme() {
        openField();
        AtomicInteger submissions = new AtomicInteger();
        scenario.onActivity(a -> {
            TerminalInputFieldView field = (TerminalInputFieldView) editor(a).getParent();
            field.setListener((target, text, enter) -> {
                submissions.incrementAndGet();
                return false;
            });
            described(field, TerminalInputFieldView.SEND_DESCRIPTION).performClick();
            described(field, TerminalInputFieldView.RUN_DESCRIPTION).performClick();
            assertEquals("empty Send/Run never call the transfer path", 0, submissions.get());
            EditText edit = editor(a);
            InputConnection ic = edit.onCreateInputConnection(new EditorInfo());
            ic.setComposingText("retain on rejection", 1);
            described(field, TerminalInputFieldView.RUN_DESCRIPTION).performClick();
            assertEquals(1, submissions.get());
            assertEquals("retain on rejection", edit.getText().toString());
            assertTrue("failed transfer preserves composition",
                    BaseInputConnection.getComposingSpanStart(edit.getText()) >= 0);
            field.setListener((target, text, enter) -> {
                submissions.incrementAndGet();
                assertFalse(enter);
                return true;
            });
            described(field, TerminalInputFieldView.SEND_DESCRIPTION).performClick();
            assertEquals(2, submissions.get());
            assertNotSame(edit, editor(a));
            ic.commitText("stale correction", 1);
            assertEquals("successful Send cannot be repopulated by its old IME",
                    "", editor(a).getText().toString());
            assertFalse(current(a).userInteracted()); // Stub listener transferred nothing.
        });
        scenario.recreate();
        scenario.onActivity(a -> assertEquals("", editor(a).getText().toString()));
    }

    @Test
    public void staleEditorCannotOverwriteReboundOrRecreatedSessionDraft() {
        openField();
        AtomicReference<InputConnection> old = new AtomicReference<>();
        scenario.onActivity(a -> {
            editor(a).setText("original");
            old.set(editor(a).onCreateInputConnection(new EditorInfo()));
        });
        newTab();
        selectTab("sh:1");
        scenario.onActivity(a -> {
            editor(a).setText("newer draft");
            old.get().commitText("stale tab callback", 1);
            assertEquals("newer draft", editor(a).getText().toString());
            old.set(editor(a).onCreateInputConnection(new EditorInfo()));
        });
        scenario.recreate();
        scenario.onActivity(a -> {
            editor(a).setText("newest draft");
            old.get().commitText("stale Activity callback", 1);
            assertEquals("newest draft", editor(a).getText().toString());
            assertFalse(current(a).userInteracted());
        });
        scenario.recreate();
        scenario.onActivity(a -> assertEquals("newest draft", editor(a).getText().toString()));
    }

    @Test
    public void imeKeyEventsFromHiddenDraftCannotReachTerminal() {
        openField();
        AtomicReference<InputConnection> connection = new AtomicReference<>();
        scenario.onActivity(a -> connection.set(
                editor(a).onCreateInputConnection(new EditorInfo())));
        onView(withContentDescription("Hide terminal input field")).perform(click());
        scenario.onActivity(a -> {
            assertTrue(a.findViewById(R.id.terminal).hasFocus());
            connection.get().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X));
            connection.get().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_X));
            connection.get().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            connection.get().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            connection.get().performEditorAction(EditorInfo.IME_ACTION_GO);
        });
        // Separate main-thread turn: the stock BaseInputConnection redispatches
        // asynchronously to the window's current focus, not the old editor.
        scenario.onActivity(a -> assertFalse(current(a).userInteracted()));
    }

    @Test
    public void multilineLimitsAndSavedStateRemainSafeWithRichKeyboardEnabled() {
        settings.setRichKeyboard(true);
        scenario.moveToState(Lifecycle.State.STARTED);
        scenario.moveToState(Lifecycle.State.RESUMED);
        openField();
        scenario.onActivity(a -> {
            EditText edit = editor(a);
            assertEquals(3, edit.getMaxLines());
            edit.setText("one\ntwo\nthree\nfour\nfive");
            ((TerminalInputFieldView) edit.getParent()).setAutoCapitalization(true);
            assertEquals("input-type changes retain the height limit", 3, edit.getMaxLines());
            SparseArray<Parcelable> saved = new SparseArray<>();
            a.findViewById(R.id.root).saveHierarchyState(saved);
            assertTrue("draft Editable is never stored in view saved state",
                    saved.get(edit.getId()) == null);
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                    ((View) edit.getParent()).getImportantForAutofill());
            assertFalse("rich terminal input is independent of draft edits", current(a).userInteracted());
        });
    }

    @Test
    public void resumingWithSearchOpenDoesNotRedirectKeyboardToDraftOrTerminal() {
        settings.setTouchKeyboard(true);
        openField();
        onView(withId(R.id.search_button)).perform(click());
        scenario.onActivity(a -> {
            assertTrue(a.getCurrentFocus() instanceof EditText);
            assertNotSame(editor(a), a.getCurrentFocus());
        });
        scenario.moveToState(Lifecycle.State.STARTED);
        scenario.moveToState(Lifecycle.State.RESUMED);
        scenario.onActivity(a -> {
            assertTrue(a.getCurrentFocus() instanceof EditText);
            assertNotSame(editor(a), a.getCurrentFocus());
            assertFalse(current(a).userInteracted());
        });
    }

    @Test
    public void searchAndBackReturnToDraftWithoutSendingIt() {
        openField();
        scenario.onActivity(a -> editor(a).setText("unsubmitted"));
        onView(withId(R.id.search_button)).perform(click());
        scenario.onActivity(a -> a.onBackPressed());
        scenario.onActivity(a -> {
            assertEquals("unsubmitted", editor(a).getText().toString());
            assertTrue(editor(a).hasFocus());
            assertFalse(current(a).userInteracted());
            a.onBackPressed();
        });
        onView(withContentDescription(TerminalInputFieldView.FIELD_DESCRIPTION))
                .check(matches(not(isDisplayed())));
        openField();
        scenario.onActivity(a -> assertEquals("unsubmitted", editor(a).getText().toString()));
    }
}
