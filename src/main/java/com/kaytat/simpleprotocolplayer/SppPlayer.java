package com.kaytat.simpleprotocolplayer;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;

@UnstableApi
public class SppPlayer extends SimpleBasePlayer {
  private static final String TAG = "SppPlayer";

  private final WifiLockManager wifiLockManager;


  protected SppPlayer(Context context) {
    super(context.getMainLooper());
    wifiLockManager = new WifiLockManager(context);
    wifiLockManager.setEnabled(true);
  }

  State state =
      new State.Builder()
          .setAvailableCommands(
              new Commands.Builder()
                  .add(COMMAND_GET_METADATA)
                  .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
                  .add(COMMAND_PLAY_PAUSE)
                  .add(COMMAND_PREPARE)
                  .add(COMMAND_SET_MEDIA_ITEM)
                  .add(COMMAND_STOP)
                  .build())
          .setPlaybackState(STATE_IDLE)
          .build();

  @NonNull
  @Override
  protected State getState() {
    Log.d(TAG, "getState");
    return state;
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handlePrepare() {
    Log.d(TAG, "handlePrepare");
    state = state.buildUpon().setPlaybackState(STATE_READY).build();
    wifiLockManager.setStayAwake(true);
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
    Log.d(TAG, "handleSetPlayWhenReady");
    Log.i(TAG, "handleSetPlayWhenReady:" + playWhenReady);
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleSetMediaItems(
      List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
    Log.d(TAG, "handleSetMediaItems");
    if (mediaItems.size() != 1) {
      Log.w(TAG, "handleSetMediaItems size not 1");
      return Futures.immediateVoidFuture();
    }
    MediaItem mediaItem = mediaItems.get(0);
    Log.d(TAG, "mediaItem:uri:" + mediaItem.localConfiguration.uri);
    for (String key : mediaItem.mediaMetadata.extras.keySet()) {
      Log.d(TAG, "mediaItem:" + key + ":" + mediaItem.mediaMetadata.extras.get(key));
    }
    state =
        state
            .buildUpon()
            .setPlaylist(
                ImmutableList.of(
                    new MediaItemData.Builder(mediaItem).setMediaItem(mediaItem).build()))
            .build();
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleStop() {
    Log.d(TAG, "handleStop");
    state = state.buildUpon().setPlaybackState(STATE_IDLE).build();
    wifiLockManager.setStayAwake(false);
    return Futures.immediateVoidFuture();
  }
}
