package com.kaytat.simpleprotocolplayer;

import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;

@UnstableApi
public class SppPlayer extends SimpleBasePlayer {
  protected SppPlayer(Looper applicationLooper) {
    super(applicationLooper);
  }

  State state =
      new State.Builder()
          .setAvailableCommands(
              new Commands.Builder().add(COMMAND_PLAY_PAUSE).add(COMMAND_STOP).build())
          .setPlaybackState(STATE_IDLE)
          .build();

  @NonNull
  @Override
  protected State getState() {
    return state;
  }
}
