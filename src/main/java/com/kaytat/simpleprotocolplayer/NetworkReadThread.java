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

import java.io.DataInputStream;
import java.net.Socket;

import android.util.Log;

/**
 * Worker thread reads data from the network
 */
class NetworkReadThread extends ThreadStoppable {
    final String TAG;

    WorkerThreadPair syncObject;
    String ipAddr;
    int port;
    Socket socket;

    // socket timeout at 5 seconds
    static final int SOCKET_TIMEOUT = 5 * 1000;

    public NetworkReadThread(WorkerThreadPair syncObject, String ipAddr,
            int port, String debugTag) {
        this.TAG = debugTag;
        this.setName(debugTag);
        this.syncObject = syncObject;
        this.ipAddr = ipAddr;
        this.port = port;
    }

    @Override
    public void run() {
        Log.i(TAG, "start");

        try {
            // Create the TCP socket and setup some parameters
            socket = new Socket(ipAddr, port);
            DataInputStream is = new DataInputStream(socket.getInputStream());
            try {
                socket.setSoTimeout(SOCKET_TIMEOUT);
                socket.setTcpNoDelay(true);
            } catch (Exception e) {
                Log.i(TAG, "exception:" + e);
                return;
            }

            Log.i(TAG, "running");

            // use static buffer to avoid GC
            // since we use BlockingQueue to pass data
            // so at most we will use NUM_PKTS (in queue) + 1 (taken by
            // audioThread) +1 (read socket)
            // buffers .
            int tempSize = WorkerThreadPair.NUM_PKTS + 2;
            byte[][] staticBuffer = new byte[tempSize][];

            for (int i = 0; i < tempSize; i++) {
                staticBuffer[i] = new byte[syncObject.packet_size];
            }

            int unusedIndex = 0;

            while (running) {

                // Get a packet
                is.readFully(staticBuffer[unusedIndex]);

                boolean dataPassed = syncObject.dataQueue
                        .offer(staticBuffer[unusedIndex]);

                if (!dataPassed) {
                    // if current buffer not used to queue,
                    // will be used as next read buffer, should not update
                    // Filled up. Throw away everything that's in the network
                    // queue.
                    Log.w(TAG, "drop " + syncObject.packet_size + " bytes");
                    continue;
                }
                unusedIndex = (unusedIndex + 1) % tempSize;
            }
        } catch (Exception e) {
            Log.i(TAG, "exception:" + e);
        }

        try {
            if (socket != null) {
                socket.close();
            }
            if (running) {

                syncObject.brokenShutdown();
            }
        } catch (Exception e) {
            Log.i(TAG, "exception while closing:" + e);
        }

        Log.i(TAG, "done");
    }
}
