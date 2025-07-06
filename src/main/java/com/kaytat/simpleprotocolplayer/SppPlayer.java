package com.kaytat.simpleprotocolplayer;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;

@UnstableApi
public class SppPlayer extends SimpleBasePlayer implements WorkerThreadPair.StopPlaybackCallback {
  private static final String TAG = "SppPlayer";

  private final WifiLockManager wifiLockManager;
  private final Context context;

  @Nullable private WorkerThreadPair workers;

  private State state =
      new State.Builder()
          .setAvailableCommands(
              new Commands.Builder()
                  .add(COMMAND_GET_METADATA)
                  .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
                  .add(COMMAND_PLAY_PAUSE)
                  .add(COMMAND_PREPARE)
                  .add(COMMAND_RELEASE)
                  .add(COMMAND_SET_MEDIA_ITEM)
                  .add(COMMAND_STOP)
                  .build())
          .setPlaybackState(STATE_IDLE)
          .build();

  protected SppPlayer(Context context) {
    super(context.getMainLooper());
    this.context = context;
    wifiLockManager = new WifiLockManager(context);
    wifiLockManager.setEnabled(true);
  }

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
    startStream();
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
    Log.d(TAG, "handleSetPlayWhenReady:" + playWhenReady);
    state =
        state
            .buildUpon()
            .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build();
    startStream();
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
    if (mediaItem == null
        || mediaItem.localConfiguration == null
        || mediaItem.mediaMetadata.extras == null
        || mediaItem.mediaMetadata.extras.keySet() == null) {
      Log.w(TAG, "mediaItem invalid");
      return Futures.immediateVoidFuture();
    }
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
    stopStream();
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleRelease() {
    Log.d(TAG, "handleRelease");
    handleStop();
    wifiLockManager.setEnabled(false);
    return Futures.immediateVoidFuture();
  }

  private void startStream() {
    if (isStreaming()) {
      Log.i(TAG, "startStream:already streaming");
      return;
    }
    if (!state.playWhenReady || state.playbackState != STATE_READY) {
      Log.i(TAG, "startStream:not ready");
      return;
    }
    if (state.getPlaylist().get(0).mediaItem.mediaMetadata.extras == null) {
      Log.e(TAG, "startStream:no media selected");
      return;
    }
    Bundle mediaItemExtra = state.getPlaylist().get(0).mediaItem.mediaMetadata.extras;

    workers =
        new WorkerThreadPair(
            context,
            this,
            mediaItemExtra.getString(MusicService.DATA_IP_ADDRESS),
            mediaItemExtra.getInt(MusicService.DATA_AUDIO_PORT, MusicService.DEFAULT_AUDIO_PORT),
            mediaItemExtra.getInt(MusicService.DATA_SAMPLE_RATE, MusicService.DEFAULT_SAMPLE_RATE),
            mediaItemExtra.getBoolean(MusicService.DATA_STEREO, MusicService.DEFAULT_STEREO),
            mediaItemExtra.getInt(MusicService.DATA_BUFFER_MS, MusicService.DEFAULT_BUFFER_MS),
            mediaItemExtra.getBoolean(MusicService.DATA_RETRY, MusicService.DEFAULT_RETRY),
            mediaItemExtra.getBoolean(
                MusicService.DATA_USE_PERFORMANCE_MODE, MusicService.DEFAULT_USE_PERFORMANCE_MODE),
            mediaItemExtra.getBoolean(
                MusicService.DATA_USE_MIN_BUFFER, MusicService.DEFAULT_USE_MIN_BUFFER));

    wifiLockManager.setStayAwake(true);
  }

  private void stopStream() {
    // we can also release the Wifi lock, if we're holding it
    wifiLockManager.setStayAwake(false);

    if (workers != null) {
      workers.stopAndInterrupt();
      workers = null;
    }
  }

  private boolean isStreaming() {
    return workers != null;
  }

  public void stopPlayback() {
    stopStream();
    invalidateState();
  }
}
