package com.kaytat.simpleprotocolplayer;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

public class BackgroundMusicService extends MediaSessionService {
  private MediaSession mediaSession;

  @OptIn(markerClass = UnstableApi.class)
  @Override
  public void onCreate() {
    super.onCreate();
    mediaSession = new MediaSession.Builder(this, new SppPlayer(getMainLooper())).build();
  }

  @Nullable
  public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
    return mediaSession;
  }

  @Override
  public void onTaskRemoved(@Nullable Intent rootIntent) {
    Player player = mediaSession.getPlayer();
    if (!player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
      // Stop the service if not playing, continue playing in the background
      // otherwise.
      stopSelf();
    }
  }

  @Override
  public void onDestroy() {
    mediaSession.getPlayer().release();
    mediaSession.release();
    mediaSession = null;
    super.onDestroy();
  }
}
