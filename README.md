# Simple Protocol Player

## Links:

#### Play store

https://play.google.com/store/apps/details?id=com.kaytat.simpleprotocolplayer&hl=en

#### Server source

https://github.com/kaytat/SimpleProtocolServer

#### More info

http://kaytat.com/blog/

## Details

This media player plays uncompressed PCM data from a server over your local network. This is meant
to be used for streaming audio from a PC to your Android phone or tablet. The focus has been latency
and so all the options are intended for the user to be able to find a compromise between latency and
quality.

As a rule of thumb, a low sample rate + mono + 50 ms or less of buffer time are the settings that
are generally used.

This project is based on an old version of this Android
example: https://github.com/googlesamples/android-MediaBrowserService

### Changes for 0.5.9.22 (version 22)

Revert all changes after 0.5.9.0 (version 15). Then update minSdk to 26 and target and compile SDK
to 36. Since this forces edge-to-edge support on the latest Android versions, hack the app a bit so
that it doesn't look terrible.

Also create a release branch dedicated for just Play Store updates.

### Changes for 0.5.7.0 (version 13)

* Allow
  [PERFORMANCE_MODE_LOW_LATENCY](https://developer.android.com/reference/android/media/AudioTrack#PERFORMANCE_MODE_LOW_LATENCY)
  for Android O and above
* Allow use of
  [minimal buffer size](https://developer.android.com/reference/android/media/AudioTrack#getMinBufferSize(int,%20int,%20int))
  as reported by AudioTrack.
  * The idea here is to allow Android to dictate the buffer size and so the user don't have to
    tweak the ms delay setting.

#### Streaming from Ubuntu (or anything running PulseAudio)

The following web page describes how to configure PulseAudio for use with this
player.  http://kaytat.com/blog/?page_id=301

#### Streaming with Pipewire

* On demand with enabled pipewire-pulse using `pactl` from pulseaudio-utils:
  `pactl load-module module-simple-protocol-tcp rate=44100 format=s16le channels=2 record=true port=4711`

* Permanently through config file `/etc/pipewire/pipewire.conf.d/simple-protocol.conf`:

```
context.modules = [
{   name = libpipewire-module-protocol-simple
    args = {
        capture = true
        audio.rate = 44100
        audio.format = S16LE
        audio.channels = 2
        audio.position = [ FL FR ]
        server.address = [
            "tcp:4711"
        ]
    }
}
]
```

(https://docs.pipewire.org/page_module_protocol_simple.html for reference)

#### Streaming from Windows

Download the server from the github link above and run it locally. The server has some options also
to help tune the performance.

#### Test your latency:

https://www.youtube.com/watch?v=KWh9YLtbbws
