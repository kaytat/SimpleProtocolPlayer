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
 *
 * Code for NoFilter related from here:
 * http://stackoverflow.com/questions/8512762/autocompletetextview-disable
 * -filtering
 */

package com.kaytat.simpleprotocolplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.apache.commons.validator.routines.DomainValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Main activity: shows media player buttons. This activity shows the media
 * player buttons and lets the user click them. No media handling is done
 * here -- everything is done by passing Intents to our {@link MusicService}.
 */
public class MainActivity extends AppCompatActivity implements OnClickListener {
  private static final String TAG = "MainActivity";

  AutoCompleteTextView ipAddrText;
  ArrayList<String> ipAddrList;
  ArrayAdapter<String> ipAddrAdapter;

  AutoCompleteTextView audioPortText;
  ArrayList<String> audioPortList;
  ArrayAdapter<String> audioPortAdapter;

  int sampleRate;
  boolean stereo;
  int bufferMs;
  boolean retry;

  Button playButton;
  Button stopButton;

  private enum NetworkConnection {
    NOT_CONNECTED,
    WIFI_CONNECTED,
    NON_WIFI_CONNECTED
  }

  /**
   * Called when the activity is first created. Here, we simply set the
   * event listeners and start the background service ({@link MusicService})
   * that will handle the actual media playback.
   */
  @SuppressLint("ClickableViewAccessibility")
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);

    ipAddrText = findViewById(R.id.editTextIpAddr);
    audioPortText = findViewById(R.id.editTextAudioPort);

    playButton = findViewById(R.id.playButton);
    stopButton = findViewById(R.id.stopButton);

    playButton.setOnClickListener(this);
    stopButton.setOnClickListener(this);

    // Allow full list to be shown on first focus
    ipAddrText.setOnTouchListener((v, event) -> {
      ipAddrText.showDropDown();
      return false;
    });
    ipAddrText.setOnFocusChangeListener((v, hasFocus) -> {
      if (hasFocus && ipAddrText.getAdapter() != null) {
        ipAddrText.showDropDown();
      }

    });
    audioPortText.setOnTouchListener((v, event) -> {
      audioPortText.showDropDown();
      return false;
    });
    audioPortText
        .setOnFocusChangeListener((v, hasFocus) -> {
          if (hasFocus && ipAddrText.getAdapter() != null) {
            audioPortText.showDropDown();
          }

        });
  }

  /**
   * The two different approaches here is an attempt to support both an old
   * preferences and new preferences.  The newer version saved to JSON while
   * the old version just saved one string.
   */
  static final String IP_PREF = "IP_PREF";
  static final String PORT_PREF = "PORT_PREF";

  static final String IP_JSON_PREF = "IP_JSON_PREF";
  static final String PORT_JSON_PREF = "PORT_JSON_PREF";

  static final String RATE_PREF = "RATE";
  static final String STEREO_PREF = "STEREO";
  static final String BUFFER_MS_PREF = "BUFFER_MS";
  static final String RETRY_PREF = "RETRY";

  ArrayList<String> getListFromPrefs(SharedPreferences prefs, String keyJson,
      String keySingle) {
    // Retrieve the values from the shared preferences
    String jsonString = prefs.getString(keyJson, null);
    ArrayList<String> arrayList = new ArrayList<>();

    if (jsonString == null || jsonString.length() == 0) {
      // Try to fill with the original key used
      String single = prefs.getString(keySingle, null);
      if (single != null && single.length() != 0) {
        arrayList.add(single);
      }
    } else {
      try {
        JSONObject jsonObject = new JSONObject(jsonString);

        // Note that the array is hard-coded as the element labelled
        // as 'list'
        JSONArray jsonArray = jsonObject.getJSONArray("list");
        for (int i = 0; i < jsonArray.length(); i++) {
          String s = (String) jsonArray.get(i);
          if (s != null && s.length() != 0) {
            arrayList.add(s);
          }
        }
      } catch (JSONException jsonException) {
        Log.i(TAG, jsonException.toString());
      }
    }

    return arrayList;
  }

  private ArrayList<String> getUpdatedArrayList(SharedPreferences prefs,
      AutoCompleteTextView view, String keyJson, String keySingle) {
    // Retrieve the values from the shared preferences
    ArrayList<String> arrayList = getListFromPrefs(prefs, keyJson, keySingle);

    // Make sure the most recent IP is on top
    arrayList.remove(view.getText().toString());
    arrayList.add(0, view.getText().toString());

    if (arrayList.size() >= 4) {
      arrayList.subList(4, arrayList.size()).clear();
    }

    return arrayList;
  }

  private JSONObject getJson(ArrayList<String> arrayList) {
    JSONArray jsonArray = new JSONArray(arrayList);
    JSONObject jsonObject = new JSONObject();
    try {
      jsonObject.put("list", jsonArray);
    } catch (JSONException jsonException) {
      Log.i(TAG, jsonException.toString());
    }

    return jsonObject;
  }

  private void savePrefs() {
    SharedPreferences myPrefs = getSharedPreferences("myPrefs", MODE_PRIVATE);
    SharedPreferences.Editor prefsEditor = myPrefs.edit();

    ipAddrList =
        getUpdatedArrayList(myPrefs, ipAddrText, IP_JSON_PREF, IP_PREF);
    audioPortList =
        getUpdatedArrayList(myPrefs, audioPortText, PORT_JSON_PREF, PORT_PREF);

    // Write out JSON object
    prefsEditor.putString(IP_JSON_PREF, getJson(ipAddrList).toString());
    prefsEditor.putString(PORT_JSON_PREF, getJson(audioPortList).toString());

    prefsEditor.putBoolean(STEREO_PREF, stereo);
    prefsEditor.putInt(RATE_PREF, sampleRate);
    prefsEditor.putInt(BUFFER_MS_PREF, bufferMs);
    prefsEditor.putBoolean(RETRY_PREF, retry);
    prefsEditor.apply();

    // Update adapters
    ipAddrAdapter.clear();
    ipAddrAdapter.addAll(ipAddrList);
    ipAddrAdapter.notifyDataSetChanged();
    audioPortAdapter.clear();
    audioPortAdapter.addAll(audioPortList);
    audioPortAdapter.notifyDataSetChanged();
  }

  @Override
  public void onPause() {
    super.onPause();
    savePrefs();
  }

  private static class NoFilterArrayAdapter<T> extends ArrayAdapter<T> {
    private final Filter filter = new NoFilter();
    public final List<T> items;

    @Override
    public Filter getFilter() {
      return filter;
    }

    public NoFilterArrayAdapter(Context context, int textViewResourceId,
        List<T> objects) {
      super(context, textViewResourceId, objects);
      Log.v(TAG, "Adapter created " + filter);
      items = objects;
    }

    private class NoFilter extends Filter {

      @Override
      protected Filter.FilterResults performFiltering(CharSequence arg0) {
        Filter.FilterResults result = new Filter.FilterResults();
        result.values = items;
        result.count = items.size();
        return result;
      }

      @Override
      protected void publishResults(CharSequence arg0,
          Filter.FilterResults arg1) {
        notifyDataSetChanged();
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    SharedPreferences myPrefs = getSharedPreferences("myPrefs", MODE_PRIVATE);

    ipAddrList = getListFromPrefs(myPrefs, IP_JSON_PREF, IP_PREF);
    ipAddrAdapter =
        new NoFilterArrayAdapter<>(this, android.R.layout.simple_list_item_1,
            ipAddrList);
    ipAddrText.setAdapter(ipAddrAdapter);
    ipAddrText.setThreshold(1);
    if (ipAddrList.size() != 0) {
      ipAddrText.setText(ipAddrList.get(0));
    }

    if (!isEmpty(ipAddrText)) {
      getWindow().setSoftInputMode(
          WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    audioPortList = getListFromPrefs(myPrefs, PORT_JSON_PREF, PORT_PREF);
    audioPortAdapter =
        new NoFilterArrayAdapter<>(this, android.R.layout.simple_list_item_1,
            audioPortList);
    audioPortText.setAdapter(audioPortAdapter);
    audioPortText.setThreshold(1);
    if (audioPortList.size() != 0) {
      audioPortText.setText(audioPortList.get(0));
    }

    // These hard-coded values should match the defaults in the strings array
    Resources res = getResources();

    sampleRate = myPrefs.getInt(RATE_PREF, MusicService.DEFAULT_SAMPLE_RATE);
    String rateString = Integer.toString(sampleRate);
    String[] sampleRateStrings = res.getStringArray(R.array.sampleRates);
    for (int i = 0; i < sampleRateStrings.length; i++) {
      if (sampleRateStrings[i].contains(rateString)) {
        Spinner sampleRateSpinner = findViewById(R.id.spinnerSampleRate);
        sampleRateSpinner.setSelection(i);
        break;
      }
    }

    stereo = myPrefs.getBoolean(STEREO_PREF, MusicService.DEFAULT_STEREO);
    String[] stereoStrings = res.getStringArray(R.array.stereo);
    Spinner stereoSpinner = findViewById(R.id.stereo);
    String stereoKey = getResources().getString(R.string.stereoKey);
    if (stereoStrings[0].contains(stereoKey) == stereo) {
      stereoSpinner.setSelection(0);
    } else {
      stereoSpinner.setSelection(1);
    }

    bufferMs = myPrefs.getInt(BUFFER_MS_PREF, MusicService.DEFAULT_BUFFER_MS);
    Log.d(TAG, "bufferMs:" + bufferMs);
    EditText e = findViewById(R.id.editTextBufferSize);
    e.setText(String.format(Locale.getDefault(), "%d", bufferMs));

    retry = myPrefs.getBoolean(RETRY_PREF, MusicService.DEFAULT_RETRY);
    Log.d(TAG, "retry:" + retry);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu items for use in the action bar
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.actions, menu);
    return super.onCreateOptionsMenu(menu);
  }

  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == R.id.notice_item) {
      Intent intent = new Intent(this, NoticeActivity.class);
      startActivity(intent);
      return true;
    } else {
      return super.onOptionsItemSelected(item);
    }
  }

  public void onClick(View target) {
    // Send the correct intent to the MusicService, according to the
    // button that was clicked
    if (target == playButton) {
      switch (getNetworkConnection()) {
      case NOT_CONNECTED:
        Toast.makeText(getApplicationContext(), "No network connectivity.",
            Toast.LENGTH_SHORT).show();
        return;
      case NON_WIFI_CONNECTED:
        Toast.makeText(getApplicationContext(), "WARNING: wifi not connected.",
            Toast.LENGTH_SHORT).show();
        break;
      default:
        break;
      }

      hideKb();

      // Get the IP address and port and put it in the intent
      Intent i = new Intent(MusicService.ACTION_PLAY);
      i.setPackage(getPackageName());
      String ipAddr = ipAddrText.getText().toString();
      String portStr = audioPortText.getText().toString();

      // Check address string against domain, IPv4, and IPv6
      DomainValidator domainValidator = DomainValidator.getInstance();
      InetAddressValidator inetAddressValidator =
          InetAddressValidator.getInstance();
      if (!domainValidator.isValid(ipAddr) &&
          !inetAddressValidator.isValidInet4Address(ipAddr) &&
          !inetAddressValidator.isValidInet6Address(ipAddr)) {
        Toast.makeText(getApplicationContext(), "Invalid address",
            Toast.LENGTH_SHORT).show();
        return;
      }

      if (portStr.equals("")) {
        Toast.makeText(getApplicationContext(), "Invalid port",
            Toast.LENGTH_SHORT).show();
        return;
      }
      Log.d(TAG, "ip:" + ipAddr);
      i.putExtra(MusicService.DATA_IP_ADDRESS, ipAddr);

      int audioPort;
      try {
        audioPort = Integer.parseInt(portStr);
      } catch (NumberFormatException nfe) {
        Log.e(TAG, "Invalid port:" + nfe);
        Toast.makeText(getApplicationContext(), "Invalid port",
            Toast.LENGTH_SHORT).show();
        return;
      }
      Log.d(TAG, "port:" + audioPort);
      i.putExtra(MusicService.DATA_AUDIO_PORT, audioPort);

      // Extract sample rate
      Spinner sampleRateSpinner = findViewById(R.id.spinnerSampleRate);
      String rateStr = String.valueOf(sampleRateSpinner.getSelectedItem());
      String[] rateSplit = rateStr.split(" ");
      if (rateSplit.length != 0) {
        try {
          sampleRate = Integer.parseInt(rateSplit[0]);
          Log.i(TAG, "rate:" + sampleRate);
          i.putExtra(MusicService.DATA_SAMPLE_RATE, sampleRate);
        } catch (NumberFormatException nfe) {
          // Ignore the error
          Log.i(TAG, "invalid sample rate:" + nfe);
        }
      }

      // Extract stereo/mono setting
      Spinner stereoSpinner = findViewById(R.id.stereo);
      String stereoSettingString =
          String.valueOf(stereoSpinner.getSelectedItem());
      String stereoKey = getResources().getString(R.string.stereoKey);
      stereo = stereoSettingString.contains(stereoKey);
      i.putExtra(MusicService.DATA_STEREO, stereo);
      Log.i(TAG, "stereo:" + stereo);

      // Get the latest buffer entry
      EditText e = findViewById(R.id.editTextBufferSize);
      String bufferMsString = e.getText().toString();
      if (bufferMsString.length() != 0) {
        try {
          bufferMs = Integer.parseInt(bufferMsString);
          Log.d(TAG, "buffer ms:" + bufferMs);
          i.putExtra(MusicService.DATA_BUFFER_MS, bufferMs);
        } catch (NumberFormatException nfe) {
          // Ignore the error
          Log.i(TAG, "invalid buffer size:" + nfe);
        }
      }

      // Get the retry checkbox
      retry = ((CheckBox) findViewById(R.id.checkBoxRetry)).isChecked();
      Log.d(TAG, "retry:" + retry);
      i.putExtra(MusicService.DATA_RETRY, retry);

      // Save current settings
      savePrefs();
      startService(i);
    } else if (target == stopButton) {
      hideKb();

      Intent i = new Intent(MusicService.ACTION_STOP);
      i.setPackage(getPackageName());
      startService(i);
    }
  }

  private void hideKb() {
    InputMethodManager inputManager =
        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

    View v = getCurrentFocus();
    if (v != null) {
      inputManager.hideSoftInputFromWindow(v.getWindowToken(),
          InputMethodManager.HIDE_NOT_ALWAYS);
    }
  }

  private boolean isEmpty(EditText etText) {
    return etText.getText().toString().trim().length() == 0;
  }

  private NetworkConnection getNetworkConnection() {
    ConnectivityManager connectivityManager =
        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    if (connectivityManager == null) {
      return NetworkConnection.NOT_CONNECTED;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      NetworkCapabilities capabilities = connectivityManager
          .getNetworkCapabilities(connectivityManager.getActiveNetwork());
      if (capabilities == null) {
        return NetworkConnection.NOT_CONNECTED;
      }
      if (!capabilities
          .hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        return NetworkConnection.NOT_CONNECTED;
      }
      if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        return NetworkConnection.WIFI_CONNECTED;
      } else {
        return NetworkConnection.NON_WIFI_CONNECTED;
      }
    } else {
      NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
      if (networkInfo == null) {
        return NetworkConnection.NOT_CONNECTED;
      }
      if (!networkInfo.isConnected()) {
        return NetworkConnection.NOT_CONNECTED;
      }
      if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
        return NetworkConnection.WIFI_CONNECTED;
      } else {
        return NetworkConnection.NON_WIFI_CONNECTED;
      }
    }
  }
}
