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

import android.util.Log;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.NetworkInterface;
import java.util.Enumeration;
import androidx.annotation.Nullable;
import android.widget.Toast;
import java.net.InterfaceAddress;
import java.net.InetAddress;

/**
 * Worker thread reads data from the network
 */
class NetworkReadThread extends ThreadStoppable {
  final String TAG;

  static final int[][] RETRY_PARAMS = new int[][]{
      {5, 12},
      {20, 6},
      {60, 2}
  };

  final WorkerThreadPair syncObject;
  String ipAddr;
  final int port;
  final boolean useRndis;
  final boolean attemptConnectionRetry;
  final byte[][] dataBuffer;
  final int numBuffers;
  int bufferIndex;
  MusicService musicService;

  // socket timeout at 5 seconds
  static final int SOCKET_TIMEOUT = 5 * 1000;

  public NetworkReadThread(
      WorkerThreadPair syncObject,
      String ipAddr,
      int port,
      boolean attemptConnectionRetry,
      String debugTag,
      boolean useRndis,
      MusicService musicService) {
    this.TAG = debugTag;
    this.setName(debugTag);
    this.syncObject = syncObject;
    this.ipAddr = ipAddr;
    this.useRndis = useRndis;
    this.port = port;
    this.musicService = musicService;
    this.attemptConnectionRetry = attemptConnectionRetry;

    // since we use BlockingQueue to pass data
    // so at most we will use NUM_PACKETS (in queue) + 1 (taken by
    // audioThread) +1 (read socket)
    // buffers .
    numBuffers = WorkerThreadPair.NUM_PACKETS + 2;
    bufferIndex = 0;
    dataBuffer = new byte[numBuffers][];
    for (int i = 0; i < numBuffers; i++) {
      dataBuffer[i] = new byte[syncObject.bytesPerAudioPacket];
    }
  }

  @Nullable
  public String checkIPRange(String ip) {
    String[] parts = ip.split("\\.");
    String baseIP = parts[0] + "." + parts[1] + "." + parts[2] + "."; 
    int end = Integer.parseInt(parts[3]);
        for (int i = 1; i <= 254; i++) {
            String ipAddress = baseIP + i;
            if(end != i){
              try {
                  InetAddress inet = InetAddress.getByName(ipAddress);
                  if (inet.isReachable(1)) {
                      return ipAddress;
                  }
              } catch (IOException e) {
                Toast.makeText(musicService.getApplicationContext(), "Error while checking IP: " + ipAddress + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
              }
            }
        }
    return null;
  }

  @Nullable
  public String getTetheredGatewayIp() {
    try {
      Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
      while (networkInterfaces.hasMoreElements()) {
          NetworkInterface nic = networkInterfaces.nextElement();
          if (nic.isUp() && nic.getName().contains("rndis")) {
            for ( InterfaceAddress interfaceAddress : nic.getInterfaceAddresses()){
              String ip = interfaceAddress.getAddress().getHostAddress();
              if(ip.indexOf('%') == -1){ // Skips IPv6 IP
                return checkIPRange(ip);
              }
            }
          }
      }
      }
     catch (Exception e) {
      e.printStackTrace();
      Toast.makeText(musicService.getApplicationContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
    }
      return null;
  }

  @Override
  public void run() {
    Log.i(TAG, "start");
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

      Log.d(TAG, "retryCount:" + retryCount + " retryParamIndex:" +
          retryParamIndex);

      try {
        //noinspection BusyWait
        Thread.sleep((long)RETRY_PARAMS[retryParamIndex][0] * 1000);
      } catch (Exception e) {
        // Ignore.
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
      if(useRndis){
        String tetherIp = getTetheredGatewayIp();
        if(tetherIp != null){
          ipAddr = tetherIp;
        }
      }
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
          Log.w(TAG, "drop " + syncObject.bytesPerAudioPacket + " bytes");
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
      }
    }

    return connectionMade;
  }
}
