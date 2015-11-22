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

import java.util.concurrent.ArrayBlockingQueue;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

/**
 * group everything belongs a stream together,makes multi stream easier
 * including NetworkReadThread BufferToAudioTrackThread and AudioTrack
 * 
 */
public class WorkerThreadPair {

    private static final String TAG = WorkerThreadPair.class.getSimpleName();

    private BufferToAudioTrackThread audioThread;
    private NetworkReadThread networkThread;
    AudioTrack mTrack;

    public WorkerThreadPair(
            MusicService musicService,
            String serverAddr,
            int serverPort,
            int sample_rate,
            boolean stereo,
            int buffer_ms,
            boolean retry) {
        this.musicService = musicService;
        int format = stereo ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;

        // Sanitize input, just in case
        if (sample_rate <= 0) {
            sample_rate = MusicService.DEFAULT_SAMPLE_RATE;
        }

        if (buffer_ms <= 5) {
            buffer_ms = MusicService.DEFAULT_BUFFER_MS;
        }

        int minBuf = AudioTrack.getMinBufferSize(sample_rate, format,
                AudioFormat.ENCODING_PCM_16BIT);

        packet_size = calcPacketSize(sample_rate, stereo, minBuf, buffer_ms);

        // The agreement here is that mTrack will be shutdown by the helper
        mTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sample_rate, format,
                AudioFormat.ENCODING_PCM_16BIT, minBuf, AudioTrack.MODE_STREAM);

        audioThread = new BufferToAudioTrackThread(this, "audio:"
                + serverAddr + ":" + serverPort);
        networkThread = new NetworkReadThread(this, serverAddr, serverPort, retry,
                "net:" + serverAddr + ":" + serverPort);

        audioThread.start();
        networkThread.start();
    }

    static int calcPacketSize(int sample_rate, boolean stereo, int minBuf,
            int buffer_ms) {

        // Assume 16 bits per sample
        int bytesPerSecond = sample_rate * 2;
        if (stereo) {
            bytesPerSecond *= 2;
        }

        int result = (bytesPerSecond * buffer_ms) / 1000;

        if ((result & 1) != 0) {
            result++;
        }

        Log.d(TAG, "initNetworkData:bytes / second:" + (bytesPerSecond));
        Log.d(TAG, "initNetworkData:minBuf:" + minBuf);
        Log.d(TAG, "initNetworkData:packet_size:" + result);

        return result;
    }

    static public final int NUM_PKTS = 3;

    // The amount of data to read from the network before sending to AudioTrack
    int packet_size;

    final ArrayBlockingQueue<byte[]> dataQueue = new ArrayBlockingQueue<byte[]>(
            NUM_PKTS);

    public void stopAndInterrupt() {
        for (ThreadStoppable it : new ThreadStoppable[] { audioThread,
                networkThread }) {

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

    private MusicService musicService;

    public void brokenShutdown() {
        // Broke out of loop unexpectedly. Shutdown.
        Handler h = new Handler(musicService.getMainLooper());
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Toast.makeText(musicService.getApplicationContext(),
                        "Unable to stream", Toast.LENGTH_SHORT).show();
                musicService.processStopRequest();
            }
        };
        h.post(r);
    }
}
