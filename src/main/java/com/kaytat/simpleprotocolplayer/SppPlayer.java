package com.kaytat.simpleprotocolplayer;

import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

@UnstableApi
public class SppPlayer extends SimpleBasePlayer {
  protected SppPlayer(Looper applicationLooper) {
    super(applicationLooper);
  }

  State state =
      new State.Builder()
          .setAvailableCommands(
              new Commands.Builder()
                  .add(COMMAND_PLAY_PAUSE)
                  .add(COMMAND_STOP)
                  .add(COMMAND_PREPARE)
                  .build())
          .setPlaybackState(STATE_IDLE)
          .build();

  @NonNull
  @Override
  protected State getState() {
    return state;
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handlePrepare() {
    return Futures.immediateVoidFuture();
  }
}
