package com.kaytat.simpleprotocolplayer;

import android.os.Looper;
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
                  .add(COMMAND_SET_MEDIA_ITEM)
                  .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
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
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
    Log.d(TAG, "handleSetPlayWhenReady");
    Log.i(TAG, "handleSetPlayWhenReady:" + playWhenReady);
    state = state.buildUpon().setPlaybackState(STATE_READY).build();
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleSetMediaItems(
      List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
    Log.d(TAG, "handleSetMediaItems");
    if (mediaItems.size() != 1) {
      Log.w(TAG, "handleSetMediaItems:size not 1");
      return Futures.immediateVoidFuture();
    }
    Log.d(TAG, "mediaItem:uri:" + mediaItems.get(0).localConfiguration.uri);
    state =
        state
            .buildUpon()
            .setPlaylist(
                ImmutableList.of(
                    new MediaItemData.Builder(mediaItems.get(0).mediaId)
                        .setMediaItem(mediaItems.get(0))
                        .build()))
            .build();
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleStop() {
    Log.d(TAG, "handleStop");
    state = state.buildUpon().setPlaybackState(STATE_IDLE).build();
    return Futures.immediateVoidFuture();
  }
}
