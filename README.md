# Simple Protocol Player

## Links:
#### Play store
https://play.google.com/store/apps/details?id=com.kaytat.simpleprotocolplayer&hl=en

#### Server source
https://github.com/kaytat/SimpleProtocolServer

#### More info
http://kaytat.com/blog/

## Details
This media player plays uncompressed PCM data from a server over your local network.  This is meant to be used for streaming audio from a PC to your Android phone or tablet.  The focus has been latency and so all the options are intended for the user to be able to find a compromise between latency and quality.

As a rule of thumb, a low sample rate + mono + 50 ms or less of buffer time are the settings that are generally used.

This project is based on an old version of this Android example: https://github.com/googlesamples/android-MediaBrowserService

#### Streaming from Ubuntu (or anything running PulseAudio)
The following web page describes how to configure PulseAudio for use with this player.  http://kaytat.com/blog/?page_id=301

If you want to use Simple Protocol Player with PulseAudio, you have to edit /etc/pulse/default.pa and add or update the following lines:
```
load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1;192.168.2.0/24;192.168.11.0/24 auth-anonymous=1
load-module module-simple-protocol-tcp source=1 record=true port=12345 rate=48000 channels=2

# Only needed if you want auto discovery.
load-module module-cli exit_on_eof=0
load-module module-cli-protocol-tcp
load-module module-zeroconf-publish
```

#### Streaming from Windows
Download the server from the github link above and run it locally.  The server has some options also to help tune the performance.

#### Test your latency:
https://www.youtube.com/watch?v=KWh9YLtbbws
