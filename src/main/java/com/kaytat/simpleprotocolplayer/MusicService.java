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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.util.ArrayList;

/**
 * Service that handles media playback. This is the Service through which we
 * perform all the media handling in our application.
 */
public class MusicService extends Service implements MusicFocusable {

  // The tag we put on debug messages
  static final String TAG = "SimpleProtocol";

  static final int DEFAULT_AUDIO_PORT = 12345;
  static final int DEFAULT_SAMPLE_RATE = 48000;
  static final int DEFAULT_BIT_DEPTH = 32;
  static final boolean DEFAULT_STEREO = true;
  static final int DEFAULT_BUFFER_MS = 50;
  static final boolean DEFAULT_RETRY = false;
  static final boolean DEFAULT_USE_RNDIS = false;
  static final boolean DEFAULT_USE_PERFORMANCE_MODE = false;
  static final boolean DEFAULT_USE_MIN_BUFFER = false;
  static final boolean DEFAULT_AUTOCONNECT_ON_START = false;

  // These are the Intent actions that we are prepared to handle. Notice
  // that the fact these constants exist in our class is a mere
  // convenience: what really defines the actions our service can handle
  // are the <action> tags in the <intent-filters> tag for our service in
  // AndroidManifest.xml.
  public static final String ACTION_PLAY =
      "com.kaytat.simpleprotocolplayer.action.PLAY";
  public static final String ACTION_STOP =
      "com.kaytat.simpleprotocolplayer.action.STOP";

  public static final String DATA_IP_ADDRESS = "ip_addr";
  public static final String DATA_AUDIO_PORT = "audio_port";
  public static final String DATA_SAMPLE_RATE = "sample_rate";
  public static final String DATA_BIT_DEPTH = "bit_depth";
  public static final String DATA_STEREO = "stereo";
  public static final String DATA_BUFFER_MS = "buffer_ms";
  public static final String DATA_RETRY = "retry";
  public static final String DATA_USE_PERFORMANCE_MODE = "use_performance_mode";
  public static final String DATA_USE_MIN_BUFFER = "use_min_buffer";
  public static final String DATA_USE_RNDIS = "use_rndis";
  public static final String DATA_AUTO_CONNECT_ON_START = "auto_connect_on_start";

  // The volume we set the media player to when we lose audio focus, but
  // are allowed to reduce the volume instead of stopping playback.
  public static final float DUCK_VOLUME = 0.1f;

  private final ArrayList<WorkerThreadPair> workers =
      new ArrayList<>();

  // our AudioFocusHelper object, if it's available (it's available on SDK
  // level >= 8) If not available, this will be null. Always check for null
  // before using!
  AudioFocusHelper mAudioFocusHelper = null;

  // indicates the state our service:
  enum State {
    Stopped, // media player is stopped and not prepared to play
    Playing  // playback active (media player ready!)
  }

  State mState = State.Stopped;

  // do we have audio focus?
  // do we have audio focus?
  enum AudioFocus {
    NoFocusNoDuck,    // we don't have audio focus, and can't duck
    NoFocusCanDuck,   // we don't have focus, but can play at a low
    // volume ("ducking")
    Focused           // we have full audio focus
  }

  AudioFocus mAudioFocus = AudioFocus.NoFocusNoDuck;

  // Wifi lock that we hold when streaming files from the internet, in
  // order to prevent the device from shutting off the Wifi radio
  WifiLock mWifiLock;

  // The ID we use for the notification (the onscreen alert that appears at
  // the notification area at the top of the screen as an icon -- and as
  // text as well if the user expands the notification area).
  final int NOTIFICATION_ID = 1;
  final String NOTIFICATION_CHANNEL_ID = "SimpleProtocolPlayer";

  Notification mNotification = null;

  @Override
  public void onCreate() {
    Log.i(TAG, "Creating service");

    // Create the Wifi lock (this does not acquire the lock, this just
    // creates it)
    mWifiLock = ((WifiManager)
        getApplicationContext().getSystemService(Context.WIFI_SERVICE))
        .createWifiLock(WifiManager.WIFI_MODE_FULL, "myLock");

    // create the Audio Focus Helper, if the Audio Focus feature is
    // available (SDK 8 or above)
    mAudioFocusHelper =
        new AudioFocusHelper(getApplicationContext(), this);
  }

  /**
   * Called when we receive an Intent. When we receive an intent sent to us
   * via startService(), this is the method that gets called. So here we
   * react appropriately depending on the Intent's action, which specifies
   * what is being requested of us.
   */
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent.getAction();
    if (action.equals(ACTION_PLAY)) {
      processPlayRequest(intent);
    } else if (action.equals(ACTION_STOP)) {
      processStopRequest();
    }

    return START_NOT_STICKY; // Means we started the service, but don't
    // want it to
    // restart in case it's killed.
  }

  void processPlayRequest(Intent i) {
    if (mState == State.Stopped) {
      tryToGetAudioFocus();
    } else {
      stopWorkers();
    }

    playStream(
        i.getStringExtra(DATA_IP_ADDRESS),
        i.getIntExtra(DATA_AUDIO_PORT, DEFAULT_AUDIO_PORT),
        i.getIntExtra(DATA_SAMPLE_RATE, DEFAULT_SAMPLE_RATE),
        i.getIntExtra(DATA_BIT_DEPTH, DEFAULT_BIT_DEPTH),
        i.getBooleanExtra(DATA_STEREO, DEFAULT_STEREO),
        i.getIntExtra(DATA_BUFFER_MS, DEFAULT_BUFFER_MS),
        i.getBooleanExtra(DATA_RETRY, DEFAULT_RETRY),
        i.getBooleanExtra(DATA_USE_PERFORMANCE_MODE, DEFAULT_USE_PERFORMANCE_MODE),
        i.getBooleanExtra(DATA_USE_MIN_BUFFER, DEFAULT_USE_MIN_BUFFER),
        i.getBooleanExtra(DATA_USE_RNDIS, DEFAULT_USE_RNDIS));
  }

  void processStopRequest() {
    if (mState == State.Playing) {
      mState = State.Stopped;

      // let go of all resources...
      relaxResources();
      giveUpAudioFocus();

      // service is no longer necessary. Will be started again if needed.
      stopSelf();
    }
  }

  /**
   * Releases resources used by the service for playback. This includes the
   * "foreground service" status and notification, the wake locks and the
   * AudioTrack
   */
  void relaxResources() {
    // stop being a foreground service
    stopForeground(true);

    // we can also release the Wifi lock, if we're holding it
    if (mWifiLock.isHeld()) {
      mWifiLock.release();
    }

    // Wait for worker thread to stop if running

    stopWorkers();
  }

  void stopWorkers() {
    for (WorkerThreadPair worker : workers) {
      worker.stopAndInterrupt();
    }

    workers.clear();
  }

  void tryToGetAudioFocus() {
    if (mAudioFocus != AudioFocus.Focused && mAudioFocusHelper != null
        && mAudioFocusHelper.requestFocus()) {
      mAudioFocus = AudioFocus.Focused;
    }
  }

  void giveUpAudioFocus() {
    if (mAudioFocus == AudioFocus.Focused && mAudioFocusHelper != null
        && mAudioFocusHelper.abandonFocus()) {
      mAudioFocus = AudioFocus.NoFocusNoDuck;
    }
  }

  /**
   * Reconfigures AudioTrack according to audio focus settings and
   * starts/restarts it.
   */
  void configVolume() {
    if (mAudioFocus == AudioFocus.NoFocusNoDuck) {
      // If we don't have audio focus and can't duck, we have to pause,
      // even if mState is State.Playing. But we stay in the Playing
      // state so that we know we have to resume playback once we get
      // the focus back.
      if (mState == State.Playing) {
        processStopRequest();
      }

      return;
    }

    for (WorkerThreadPair it : workers) {
      if (mAudioFocus == AudioFocus.NoFocusCanDuck) {
        it.audioTrack.setStereoVolume(DUCK_VOLUME, DUCK_VOLUME); // we'll be
        // relatively
        // quiet
      } else {
        it.audioTrack.setStereoVolume(1.0f, 1.0f); // we can be loud
      }
    }
  }

  /**
   * Play the stream using the given IP address and port
   */
  void playStream(
      String serverAddr,
      int serverPort,
      int sample_rate,
      int bitDepth,
      boolean stereo,
      int buffer_ms,
      boolean retry,
      boolean usePerformanceMode,
      boolean useMinBuffer,
      boolean useRndis) {

    mState = State.Stopped;
    relaxResources();

    workers.add(new WorkerThreadPair(this, serverAddr, serverPort,
        sample_rate, bitDepth, stereo, buffer_ms, retry, usePerformanceMode, useMinBuffer, useRndis));

    mWifiLock.acquire();

    mState = State.Playing;
    configVolume();

    setUpAsForeground("Streaming from " + serverAddr);
  }

  /**
   * Configures service as a foreground service. A foreground service is a
   * service that's doing something the user is actively aware of (such as
   * playing music), and must appear to the user as a notification. That's
   * why we create the notification here.
   */
  void setUpAsForeground(String text) {
    createNotificationChannel();

    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      flags |= PendingIntent.FLAG_IMMUTABLE;
    }
    PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), 0,
        new Intent(getApplicationContext(), MainActivity.class),
        flags);

    mNotification =
        new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_playing)
            .setOngoing(true)
            .setContentTitle(NOTIFICATION_CHANNEL_ID)
            .setContentText(text)
            .setContentIntent(pi).build();
    startForeground(NOTIFICATION_ID, mNotification);
  }

  private void createNotificationChannel() {
    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      int importance = NotificationManager.IMPORTANCE_DEFAULT;
      NotificationChannel channel =
          new NotificationChannel(NOTIFICATION_CHANNEL_ID,
              NOTIFICATION_CHANNEL_ID,
              importance);
      channel.setSound(null, null);
      // Register the channel with the system; you can't change the
      // importance or other notification behaviors after this
      NotificationManager notificationManager =
          getSystemService(NotificationManager.class);
      notificationManager.createNotificationChannel(channel);
    }
  }

  @Override
  public void onGainedAudioFocus() {
    Log.i(TAG, "Gained audio focus");
    mAudioFocus = AudioFocus.Focused;

    // restart media player with new focus settings
    if (mState == State.Playing) {
      configVolume();
    }
  }

  @Override
  public void onLostAudioFocus(boolean canDuck) {
    Log.i(TAG, "Lost audio focus: canDuck:" + canDuck);
    mAudioFocus =
        canDuck ? AudioFocus.NoFocusCanDuck : AudioFocus.NoFocusNoDuck;

    // start/restart/pause media player with new focus settings
    if (mState == State.Playing) {
      configVolume();
    }
  }

  @Override
  public void onDestroy() {
    // Service is being killed, so make sure we release our resources
    mState = State.Stopped;
    relaxResources();
    giveUpAudioFocus();
  }

  @Override
  public IBinder onBind(Intent arg0) {
    return null;
  }
}
