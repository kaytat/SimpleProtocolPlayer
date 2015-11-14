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

    private WorkerThreadPair syncObject;

    public BufferToAudioTrackThread(WorkerThreadPair syncObject, String debugTag) {
        this.setName(debugTag);
        TAG = debugTag;
        this.mTrack = syncObject.mTrack;
        this.syncObject = syncObject;
    }

    // Media track
    private AudioTrack mTrack = null;

    @Override
    public void run() {
        Log.i(TAG, "start");

        mTrack.play();

        try {
            while (running) {
                mTrack.write(syncObject.dataQueue.take(), 0,
                        syncObject.packet_size);
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
