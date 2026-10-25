package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

/** Turns the system voice-button gesture into Google transcription followed by Free My TV search. */
final class FreeMyTVVoiceSession extends VoiceInteractionSession {
    private final Context context;

    FreeMyTVVoiceSession(Context context) {
        super(context);
        this.context = context;
    }

    @Override public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        try {
            Intent search = new Intent(context, VoiceSearchActivity.class)
                    .putExtra("voice", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // startVoiceActivity adds CATEGORY_VOICE, which Android rejects for our
            // singleTask search activity. This is an assistant-owned activity instead.
            startAssistantActivity(search);
        } catch (RuntimeException e) {
            AppLog.w("voice session launch: " + e.getClass().getSimpleName());
            finish();
        }
    }
}
