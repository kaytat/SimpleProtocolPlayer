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

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * group everything belongs a stream together, makes multi stream easier
 * including NetworkReadThread BufferToAudioTrackThread and AudioTrack
 */
public class WorkerThreadPair {

  private static final String TAG = WorkerThreadPair.class.getSimpleName();

  private final BufferToAudioTrackThread audioThread;
  private final NetworkReadThread networkThread;
  final AudioTrack audioTrack;
  boolean useFloatAudio;
  static int bitDepthToFormat (int bitDepth){
    switch(bitDepth) {
      case 8:
        return AudioFormat.ENCODING_PCM_8BIT;
      case 16:
        return AudioFormat.ENCODING_PCM_16BIT;
      case 24:
        return AudioFormat.ENCODING_PCM_24BIT_PACKED;
      case 32:
        return AudioFormat.ENCODING_PCM_FLOAT;
    }
    Log.d(TAG, "error bit depth to format conversion: " + bitDepth);
    return 0;
  }
  public WorkerThreadPair(MusicService musicService, String serverAddr,
      int serverPort, int sampleRate, int bitDepth, boolean stereo, int requestedBufferMs,
      boolean retry, boolean usePerformanceMode, boolean useMinBuffer) {
    this.musicService = musicService;
    this.useFloatAudio = bitDepth == 32;
    int channelMask =
        stereo ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;

    // Sanitize input, just in case
    if (sampleRate <= 0) {
      sampleRate = MusicService.DEFAULT_SAMPLE_RATE;
    }

    int audioTrackMinBuffer = AudioTrack
        .getMinBufferSize(sampleRate, channelMask,
            bitDepthToFormat(bitDepth));
    Log.d(TAG, "audioTrackMinBuffer:" + audioTrackMinBuffer);

    if (useMinBuffer) {
      bytesPerAudioPacket =
          calcMinBytesPerAudioPacket(stereo, audioTrackMinBuffer);
    } else {
      if (requestedBufferMs <= 5) {
        requestedBufferMs = MusicService.DEFAULT_BUFFER_MS;
      }
      bytesPerAudioPacket =
          calcBytesPerAudioPacket(sampleRate, bitDepth, stereo, requestedBufferMs);
    }
    Log.d(TAG, "useMinBuffer:" + useMinBuffer);

    // The agreement here is that audioTrack will be shutdown by the helper
    audioTrack = buildAudioTrack(sampleRate, bitDepth, channelMask, audioTrackMinBuffer,
        usePerformanceMode);
    Log.d(TAG, "usePerformanceMode:" + usePerformanceMode);

    audioThread = new BufferToAudioTrackThread(this,
        "audio:" + serverAddr + ":" + serverPort);
    networkThread = new NetworkReadThread(this, serverAddr, serverPort, retry,
        "net:" + serverAddr + ":" + serverPort);

    audioThread.start();
    networkThread.start();
  }

  static AudioTrack buildAudioTrack(int sampleRate, int bitDepth, int channelMask,
      int audioTrackMinBuffer, boolean usePerformanceMode) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      // PERFORMANCE_MODE_LOW_LATENCY was only added in O (API 26).
      // AudioManager.STREAM_MUSIC has been deprecated in favor of
      // AudioAttributes
      AudioTrack.Builder audioTrackBuilder = new AudioTrack.Builder()
          .setAudioAttributes(new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build())
          .setAudioFormat(new AudioFormat.Builder()
              .setEncoding(bitDepthToFormat(bitDepth))
              .setSampleRate(sampleRate)
              .setChannelMask(channelMask)
              .build())
          .setBufferSizeInBytes(audioTrackMinBuffer)
          .setTransferMode(AudioTrack.MODE_STREAM);
      if (usePerformanceMode) {
        audioTrackBuilder
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
      }
      return audioTrackBuilder.build();

    } else {
      return new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
          channelMask, bitDepthToFormat(bitDepth), audioTrackMinBuffer,
          AudioTrack.MODE_STREAM);
    }
  }

  static int calcBytesPerAudioPacket(int sampleRate, int bitDepth, boolean stereo,
      int requestedBufferMs) {

    int bytesPerSecond = sampleRate * (bitDepth / 8);
    if (stereo) {
      bytesPerSecond *= 2;
    }

    int result = (bytesPerSecond * requestedBufferMs) / 1000;

    if (stereo) {
      result = (result + 3) & ~0x3;
    } else {
      result = (result + 1) & ~0x1;
    }

    Log.d(TAG, "calcBytesPerAudioPacket:bytes / second:" + bytesPerSecond);
    Log.d(TAG, "calcBytesPerAudioPacket:" + result);

    return result;
  }

  static int calcMinBytesPerAudioPacket(boolean stereo,
      int audioTrackMinBuffer) {
    int bytesPerAudioPacket;

    if (stereo) {
      bytesPerAudioPacket = (audioTrackMinBuffer + 3) & ~0x3;
    } else {
      bytesPerAudioPacket = (audioTrackMinBuffer + 1) & ~0x1;
    }

    Log.d(TAG, "calcMinBytesPerAudioPacket:audioTrackMinBuffer:" +
        audioTrackMinBuffer);
    Log.d(TAG, "calcMinBytesPerAudioPacket:" + bytesPerAudioPacket);

    return bytesPerAudioPacket;
  }

  static public final int NUM_PACKETS = 3;

  // The amount of data to read from the network before sending to AudioTrack
  final int bytesPerAudioPacket;

  final ArrayBlockingQueue<byte[]> dataQueue =
      new ArrayBlockingQueue<>(NUM_PACKETS);

  public void stopAndInterrupt() {
    for (ThreadStoppable it : new ThreadStoppable[]{audioThread,
        networkThread}) {

      try {
        it.customStop();
        it.interrupt();

        // Do not join since this can take some time. The
        // workers should be able to shutdown independently.
        // t.join();
      } catch (Exception e) {
        Log.e(TAG, "join exception:" + e);
      }
    }
  }

  private final MusicService musicService;

  public void brokenShutdown() {
    // Broke out of loop unexpectedly. Shutdown.
    Handler h = new Handler(musicService.getMainLooper());
    Runnable r = () -> {
      Toast.makeText(musicService.getApplicationContext(),
          "Unable to stream", Toast.LENGTH_SHORT).show();
      musicService.processStopRequest();
    };
    h.post(r);
  }
}
