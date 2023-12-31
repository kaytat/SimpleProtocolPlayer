/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (C) 2014 kaytat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kaytat.simpleprotocolplayer;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

/**
 * Convenience class to deal with audio focus. This class deals with everything related to audio
 * focus: it can request and abandon focus, and will intercept focus change events and deliver them
 * to a MusicFocusable interface (which, in our case, is implemented by {@link MusicService}).
 */
public class AudioFocusHelper implements AudioManager.OnAudioFocusChangeListener {
  final AudioManager mAM;
  final MusicFocusable mFocusable;
  final AudioFocusRequest mAudioFocusRequest;

  public AudioFocusHelper(Context ctx, MusicFocusable focusable) {
    mAM = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    mFocusable = focusable;
    mAudioFocusRequest =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(this)
            .build();
  }

  /** Requests audio focus. Returns whether request was successful or not. */
  public boolean requestFocus() {
    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED == mAM.requestAudioFocus(mAudioFocusRequest);
  }

  /** Abandons audio focus. Returns whether request was successful or not. */
  public boolean abandonFocus() {
    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        == mAM.abandonAudioFocusRequest(mAudioFocusRequest);
  }

  /**
   * Called by AudioManager on audio focus changes. We implement this by calling our MusicFocusable
   * appropriately to relay the message.
   */
  public void onAudioFocusChange(int focusChange) {
    if (mFocusable == null) {
      return;
    }
    switch (focusChange) {
      case AudioManager.AUDIOFOCUS_GAIN:
        mFocusable.onGainedAudioFocus();
        break;
      case AudioManager.AUDIOFOCUS_LOSS:
      case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
        mFocusable.onLostAudioFocus(false);
        break;
      case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
        mFocusable.onLostAudioFocus(true);
        break;
      default:
    }
  }
}
