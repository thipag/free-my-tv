package io.freemytv;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** Short-lived session process, separate from the always-running voice interaction service. */
public final class FreeMyTVVoiceSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(Bundle args) {
        return new FreeMyTVVoiceSession(this);
    }
}
