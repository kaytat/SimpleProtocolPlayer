package com.kaytat.simpleprotocolplayer;

import android.os.Looper;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;

@UnstableApi
public class SppPlayer extends SimpleBasePlayer {
  protected SppPlayer(Looper applicationLooper) {
    super(applicationLooper);
  }

  @Override
  protected State getState() {
    return null;
  }
}
