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
import java.io.IOException;
import java.net.Socket;

import android.util.Log;

/**
 * Worker thread reads data from the network
 */
class NetworkReadThread extends ThreadStoppable {
    final String TAG;

    static final int RETRY_SLEEP_TIME = 20;
    static final int RETRY_COUNT = 20;

    static final int[][] RETRY_PARAMS = new int [][] {
        { 5, 12 },
        { 20, 6 },
        { 60, 2 }
    };

    WorkerThreadPair syncObject;
    String ipAddr;
    int port;
    boolean attemptConnectionRetry;
    byte[][] dataBuffer;
    int numBuffers;
    int bufferIndex;

    // socket timeout at 5 seconds
    static final int SOCKET_TIMEOUT = 5 * 1000;

    public NetworkReadThread(
            WorkerThreadPair syncObject,
            String ipAddr,
            int port,
            boolean attemptConnectionRetry,
            String debugTag) {
        this.TAG = debugTag;
        this.setName(debugTag);
        this.syncObject = syncObject;
        this.ipAddr = ipAddr;
        this.port = port;
        this.attemptConnectionRetry = attemptConnectionRetry;

        // since we use BlockingQueue to pass data
        // so at most we will use NUM_PKTS (in queue) + 1 (taken by
        // audioThread) +1 (read socket)
        // buffers .
        numBuffers = WorkerThreadPair.NUM_PKTS + 2;
        bufferIndex = 0;
        dataBuffer = new byte[numBuffers][];
        for (int i = 0; i < numBuffers; i++) {
            dataBuffer[i] = new byte[syncObject.packet_size];
        }
    }

    @Override
    public void run() {
        Log.i(TAG, "start");
        boolean retry = true;
        boolean connectionMade;
        int retryCount = 0;
        int retryParamIndex = 0;

        while (running) {
            connectionMade = runImpl();

            if (!running) {
                Log.i(TAG, "not running");
                break;
            }
            if (!attemptConnectionRetry) {
                Log.i(TAG, "no retries");
                break;
            }

            if (connectionMade) {
                retryCount = retryParamIndex = 0;
                continue;
            }

            // There was connection made.  Increment the counters.
            if (retryCount >= RETRY_PARAMS[retryParamIndex][1]) {
                retryCount = 0;
                retryParamIndex++;
                if (retryParamIndex >= RETRY_PARAMS.length) {
                    // Hit the limit.  Exit.
                    Log.i(TAG, "retry limit reached");
                    break;
                }
            }

            Log.d(TAG, "retryCount:" + retryCount + " retryParamIndex:" + retryParamIndex);

            try {
                Thread.sleep(RETRY_PARAMS[retryParamIndex][0] * 1000);
            } catch (Exception e) {

            }
            retryCount++;
        }

        // Determine if cleanup is necessary
        if (running) {
            syncObject.brokenShutdown();
        }
        Log.i(TAG, "done");
    }

    public boolean runImpl() {
        Socket socket = null;
        boolean connectionMade = false;

        try {
            // Create the TCP socket and setup some parameters
            socket = new Socket(ipAddr, port);
            DataInputStream is = new DataInputStream(socket.getInputStream());
            socket.setSoTimeout(SOCKET_TIMEOUT);
            socket.setTcpNoDelay(true);

            Log.i(TAG, "running");

            while (running) {
                // Get a buffer of audio data
                is.readFully(dataBuffer[bufferIndex]);
                connectionMade = true;

                boolean dataPassed = syncObject.dataQueue
                        .offer(dataBuffer[bufferIndex]);

                if (!dataPassed) {
                    // if current buffer not used to queue,
                    // will be used as next read buffer, should not update
                    // Filled up. Throw away everything that's in the network
                    // queue.
                    Log.w(TAG, "drop " + syncObject.packet_size + " bytes");
                    continue;
                }
                bufferIndex = (bufferIndex + 1) % numBuffers;
            }
        } catch (Exception e) {
            Log.i(TAG, "runImpl:exception:" + e);

            // Attempt to release resources
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException iex) {
                    Log.i(TAG, "exception while closing socket:" + iex);
                }
                socket = null;
            }
        }

        return connectionMade;
    }
}
