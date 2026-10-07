/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collection;
import java.util.WeakHashMap;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.term.TerminalSession;

/**
 * A real, local editor: composing, autocorrect, deletion and selection never
 * touch the PTY. Only the two explicit buttons call the submission listener.
 * Drafts are process-local, keyed by session identity, and never saved to disk
 * or an Activity's saved-state Bundle (which may contain passwords).
 */
public final class TerminalInputFieldView extends LinearLayout {
    public interface Listener {
        /** Returns false when the target no longer accepts input; keep the draft. */
        boolean onSubmit(TerminalSession target, String text, boolean enter);
    }

    // Main-thread only. Values contain no session, Activity, Editable or IME
    // references. Weak keys avoid retaining a closed session if UI teardown
    // happens elsewhere (e.g. Exit in the foreground-service notification).
    private static final WeakHashMap<TerminalSession, Draft> drafts = new WeakHashMap<>();

    private static final class Draft {
        String text = "";
        int start;
        int end;
        // An opaque generation, never a View/Activity reference. Older editors
        // must not overwrite this draft after a tab is rebound or recreated.
        Object editorGeneration;
    }

    private EditText field;
    private Object editorGeneration;
    private final TextView send;
    private final TextView run;
    private ChromePalette palette;
    private TerminalSession session;
    private Draft draft;
    private Listener listener;
    private boolean capitalize;

    public TerminalInputFieldView(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setSaveEnabled(false);
        setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        int pad = Chrome.dp(context, R.dimen.space_1);
        setPaddingRelative(pad, pad, pad, pad);
        palette = ChromePalette.from(context, android.graphics.Color.BLACK);
        send = button(R.string.input_field_send_label,
                R.string.input_field_send_description, false);
        run = button(R.string.input_field_run_label,
                R.string.input_field_run_description, true);
        addView(send);
        addView(run);
        bindSession(null);
        setVisibility(GONE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Discards closed-session drafts, including closures outside this Activity. */
    public static void retainSessions(Collection<TerminalSession> live) {
        drafts.keySet().retainAll(live);
    }

    /**
     * Each binding gets its own EditText and Editable. A late IME commit to the
     * detached old editor can only edit that old draft, never the newly selected
     * session's draft. No IME callback has a submission path.
     */
    public void bindSession(TerminalSession target) {
        bindSession(target, false);
    }

    private void bindSession(TerminalSession target, boolean renewEditor) {
        if (!renewEditor && field != null && session == target) return;
        saveSelection();
        if (field != null) {
            BaseInputConnection.removeComposingSpans(field.getText());
            field.clearFocus();
            removeView(field);
        }
        session = target;
        draft = target == null ? new Draft() : drafts.computeIfAbsent(target, s -> new Draft());
        final Draft owner = draft;
        final Object generation = new Object();
        editorGeneration = owner.editorGeneration = generation;
        field = new EditText(getContext()) {
            @Override
            public InputConnection onCreateInputConnection(EditorInfo attrs) {
                InputConnection connection = super.onCreateInputConnection(attrs);
                if (connection == null) return null;
                return new InputConnectionWrapper(connection, false) {
                    @Override public boolean performEditorAction(int action) {
                        return true; // No IME action can activate Send/Run.
                    }

                    @Override public boolean sendKeyEvent(KeyEvent event) {
                        // BaseInputConnection normally redispatches to the window's
                        // CURRENT focus. A delayed Enter after hiding/switching the
                        // draft could otherwise execute a remote command or click Run.
                        // Route only to this editor, never the window/terminal.
                        if (owner.editorGeneration == generation && isAttachedToWindow()
                                && isShown() && isEnabled() && hasFocus()) {
                            dispatchKeyEvent(event);
                        }
                        return true;
                    }
                };
            }
        };
        field.setId(View.generateViewId());
        field.setSaveEnabled(false);
        field.setHint(R.string.input_field_hint);
        field.setContentDescription(getContext().getString(R.string.input_field_description));
        field.setTextSize(16);
        field.setMinHeight(Chrome.dp(getContext(), R.dimen.touch_min));
        field.setInputType(inputType());
        // setInputType can reset the multiline line limits; apply these last.
        field.setMinLines(1);
        field.setMaxLines(3);
        field.setImeOptions(EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // Some keyboards still issue GO/SEND/DONE. Consume those actions, but
        // let a physical Enter insert a newline in this multiline local editor.
        field.setOnEditorActionListener((v, action, event) -> event == null);
        field.setText(owner.text);
        field.setSelection(Math.min(owner.start, owner.text.length()),
                Math.min(owner.end, owner.text.length()));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (owner.editorGeneration == generation) owner.text = s.toString();
            }
        });
        addView(field, 0, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        field.setEnabled(target != null);
        send.setEnabled(target != null);
        run.setEnabled(target != null);
        restyle();
    }

    public void setAutoCapitalization(boolean enabled) {
        if (capitalize == enabled) return;
        capitalize = enabled;
        saveSelection();
        field.setInputType(inputType());
        field.setMinLines(1);
        field.setMaxLines(3);
        field.setSelection(Math.min(draft.start, field.length()), Math.min(draft.end, field.length()));
    }

    private int inputType() {
        // Normal text enables keyboard suggestions/swipe typing. Deliberately
        // no capitalization by default: shell commands and paths are case-sensitive.
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | (capitalize ? InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : 0);
    }

    public void focusEditor() {
        if (!field.isEnabled() || !isShown()) return;
        field.requestFocus();
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    }

    /** Also called before Activity teardown; saves caret but not composing spans. */
    public void saveSelection() {
        if (field == null || draft == null || draft.editorGeneration != editorGeneration) return;
        draft.start = Math.max(0, field.getSelectionStart());
        draft.end = Math.max(0, field.getSelectionEnd());
    }

    /** Preserve the live-session draft but fence callbacks from a destroyed Activity. */
    public void release() {
        saveSelection();
        if (draft != null && draft.editorGeneration == editorGeneration) {
            draft.editorGeneration = null;
        }
        listener = null;
        session = null;
    }

    public void applyPalette(ChromePalette palette) {
        this.palette = palette;
        restyle();
    }

    private TextView button(int label, int description, boolean enter) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(getContext().getString(description));
        button.setFocusable(true);
        button.setMinWidth(Chrome.dp(getContext(), R.dimen.touch_min));
        button.setMinHeight(Chrome.dp(getContext(), R.dimen.touch_min));
        int pad = Chrome.dp(getContext(), R.dimen.space_2);
        button.setPaddingRelative(pad, 0, pad, 0);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Chrome.dp(getContext(), R.dimen.space_1));
        button.setLayoutParams(lp);
        button.setOnClickListener(v -> submit(enter));
        return button;
    }

    private void submit(boolean enter) {
        if (session == null || listener == null || field.length() == 0) return;
        // Freeze the visible draft, not an IME-maintained composing mirror.
        // No trailing Enter is synthesized except for the explicit Run button.
        String text = field.getText().toString();
        if (!listener.onSubmit(session, text, enter)) return;
        boolean hadFocus = field.hasFocus();
        BaseInputConnection.removeComposingSpans(field.getText());
        field.setText("");
        draft.start = draft.end = 0;
        // A fresh Editable/generation also fences an in-flight autocorrection
        // callback from repopulating the cleared draft after successful Send/Run.
        bindSession(session, true);
        if (hadFocus) focusEditor();
    }

    private void restyle() {
        setBackground(palette.barSurface(false));
        if (field != null) {
            field.setTextColor(palette.textPrimary);
            field.setHintTextColor(palette.textSecondary);
            field.setBackground(palette.rounded(palette.surface2,
                    Chrome.dimen(getContext(), R.dimen.radius_md), palette.border));
            int pad = Chrome.dp(getContext(), R.dimen.space_3);
            field.setPaddingRelative(pad, pad, pad, pad);
        }
        for (TextView button : new TextView[] {send, run}) {
            button.setTextColor(palette.textPrimary);
            button.setBackground(palette.ripple(palette.surface2,
                    Chrome.dimen(getContext(), R.dimen.radius_md), palette.border));
        }
    }
}
