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

import android.media.AudioTrack;
import android.util.Log;

/**
 * Worker thread that takes data from the buffer and sends it to audio track
 */
class BufferToAudioTrackThread extends ThreadStoppable {
  final String TAG;

  private final WorkerThreadPair syncObject;

  public BufferToAudioTrackThread(WorkerThreadPair syncObject,
      String debugTag) {
    this.setName(debugTag);
    TAG = debugTag;
    this.mTrack = syncObject.audioTrack;
    this.syncObject = syncObject;
  }

  // Media track
  private AudioTrack mTrack;

  @Override
  public void run() {
    Log.i(TAG, "start");

    int floatLengthBuffer = syncObject.bytesPerAudioPacket / 4;
    float[] floatArray = new float[floatLengthBuffer];
    byte[] audioData = new byte[syncObject.bytesPerAudioPacket];
    if(!syncObject.useFloatAudio){
      floatArray = null;
    }
    mTrack.play();

    try {
      while (running) {
        audioData = syncObject.dataQueue.take();
        if (syncObject.useFloatAudio) {
          for(int i = 0; i < floatLengthBuffer; i++){
            floatArray[i] = Float.intBitsToFloat((audioData[i * 4] & 0xFF) 
            | ((audioData[i * 4 + 1] & 0xFF) << 8)
            | ((audioData[i * 4 + 2] & 0xFF) << 16) 
            | ((audioData[i * 4 + 3] & 0xFF) << 24));
          }
          mTrack.write(floatArray, 0, floatArray.length, AudioTrack.WRITE_NON_BLOCKING);
        } else {
          mTrack.write(audioData, 0, audioData.length);
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "exception:" + e);
    }

    // Do some cleanup
    mTrack.stop();
    mTrack.release();
    mTrack = null;
    Log.i(TAG, "done");
  }
}
