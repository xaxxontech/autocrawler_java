package autocrawler;


import developer.Ros;
import org.red5.server.api.IConnection;
import org.red5.server.api.service.IServiceCapableConnection;
import org.red5.server.stream.ClientBroadcastStream;

import autocrawler.State.values;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;


@SuppressWarnings("MagicConstant")
public class Video {

    private static State state = State.getReference();
    private Application app = null;
    private String port;
    private int dockcamdevicenum = 3;
    private int adevicenum = 1;
    public static final int defaultwidth=640;
    private static final int defaultheight=480;
    public static final int lowreswidth=320;
    private static final int lowresheight=240;
    private static final String PATH="/dev/shm/rosimgframes/";
    private static final String EXT=".bmp";
    private volatile long lastframegrab = 0;
    public int lastwidth=0;
    public int lastheight=0;
    public int lastfps=0;
    public long lastbitrate = 0;
    private Application.streamstate lastmode = Application.streamstate.stop;
    private String avprog = "avconv"; // avconv or ffmpeg
    public static long STREAM_CONNECT_DELAY = 2000;
    private static int dumpfps = 15;
    private static final String STREAMSPATH= "/autocrawler/streams/";
    public static final String FMTEXT = ".flv";
    public static final String AUDIO = "_audio";
    private static final String VIDEO = "_video";
    private static final String STREAM1 = "stream1";
    private static final String STREAM2 = "stream2";
    private static String ubuntuVersion;
    public String realsensepstring = null;
    private String webrtcpstring = null;
    private volatile long lastvideocommand = 0;

    public Video(Application a) {
        app = a;
        port = Settings.getReference().readRed5Setting("rtmp.port");
        ubuntuVersion = Util.getUbuntuVersion();
        setAudioDevice();
        setDockCamVideoDevice();

        Settings settings = Settings.getReference();
        String vals[] = settings.readSetting(settings.readSetting(GUISettings.vset)).split("_");
        lastwidth = Integer.parseInt(vals[0]);
        lastheight = Integer.parseInt(vals[1]);
        lastfps = Integer.parseInt(vals[2]);
        lastbitrate = Long.parseLong(vals[3]);
    }

    public void initAvconv() {
        state.set(State.values.stream, Application.streamstate.stop.toString());
        if (state.get(State.values.osarch).equals(Application.ARM)) {
//            avprog = FFMPEG;
//            dumpfps = 8;
//            STREAM_CONNECT_DELAY=3000;
        }
    }

    private void setAudioDevice() {
        try {
            String cmd[] = new String[]{"arecord", "--list-devices"}; // arecord --list-devices
            Process proc = Runtime.getRuntime().exec(cmd);
            proc.waitFor();
//            proc.waitFor();

            String line = null;
            BufferedReader procReader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            while ((line = procReader.readLine()) != null) {
                if(line.startsWith("card") && line.contains("Camera")) {
                    adevicenum = Integer.parseInt(line.substring(5,6));      // "card 0"
                    Util.debug(line+ " adevicenum="+adevicenum, this);
                }
            }

        } catch (Exception e) { Util.printError(e); }
    }

    private void setDockCamVideoDevice() {
        try {
            String cmd[] = new String[]{"v4l2-ctl", "--list-devices"}; // v4l2-ctl --list-devices
            Process proc = Runtime.getRuntime().exec(cmd);
            proc.waitFor();

            String line = null;
            BufferedReader procReader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            while ((line = procReader.readLine()) != null) {
                if(line.contains("Camera")) {
                    line = procReader.readLine().trim();
                    dockcamdevicenum = Integer.parseInt(line.substring(line.length() - 1));
                    Util.debug(line+ " dockcamdevicenum="+dockcamdevicenum, this);
                }
            }

        } catch (Exception e) { Util.printError(e);}
    }

    public void publish (final Application.streamstate mode, final int w, final int h, final int fps, final long bitrate) {
        // todo: disallow unsafe custom values (device can be corrupted?)

        if (System.currentTimeMillis() < lastvideocommand + STREAM_CONNECT_DELAY)
            return;

        lastvideocommand = System.currentTimeMillis();

        if ( (mode.equals(Application.streamstate.camera) || mode.equals(Application.streamstate.camandmic)) &&
                (state.get(values.stream).equals(Application.streamstate.camera.toString()) ||
                state.get(values.stream).equals(Application.streamstate.camandmic.toString())) &&
                !state.getBoolean(values.dockcamon) ) {
            app.driverCallServer(PlayerCommands.messageclients, "camera already running, stream: "+mode.toString()+" command dropped");
            return;
        }

        lastwidth = w;
        lastheight = h;
        lastfps = fps;
        lastbitrate = bitrate;
        lastmode = mode;
        final long id = System.currentTimeMillis();

        app.driverCallServer(PlayerCommands.streammode, mode.toString());

        new Thread(new Runnable() { public void run() {

            // TODO: ????
            if (state.getBoolean(values.dockcamon) &&
                    (mode.equals(Application.streamstate.camera) || mode.equals(Application.streamstate.camandmic)) ) {
                lastvideocommand = 0; // so next command not ignored
                app.driverCallServer(PlayerCommands.dockcam, Settings.OFF);
                app.driverCallServer(PlayerCommands.streammode, mode.toString());
                Util.delay(STREAM_CONNECT_DELAY);
            }

            switch (mode) {
                case camera:

                    if (app.player instanceof IServiceCapableConnection && realsensepstring == null) // flash client
                        realsensepstring = Ros.launch("rgbpublish");

                    else  {                                                                     // webrtc client
                        if (realsensepstring == null) {
                            realsensepstring = Ros.launch(new ArrayList<String>(Arrays.asList("realsensergb",
                                "color_width:="+lastwidth, "color_height:="+lastheight, "color_fps:="+lastfps)));
                        }

                        if (state.exists(values.driverclientid)) {
                            webrtcpstring = Ros.launch(new ArrayList<String>(Arrays.asList("rgbwebrtc",
                                "peerid:=" + state.get(values.driverclientid),
                                "videowidth:=" + lastwidth, "videoheight:=" + lastheight, "videobitrate:=" + lastbitrate)));
                        }
                    }


                    break;

                case camandmic:

                    if (app.player instanceof IServiceCapableConnection && realsensepstring == null) // flash client
                        return;

                    else {                                               // webrtc client
                        if (realsensepstring == null) {
                            realsensepstring = Ros.launch(new ArrayList<String>(Arrays.asList("realsensergb",
                                "color_width:="+lastwidth, "color_height:="+lastheight, "color_fps:="+lastfps)));
                        }

                        if (state.exists(values.driverclientid)) {
                            webrtcpstring = Ros.launch(new ArrayList<String>(Arrays.asList("rgbwebrtc",
                                "peerid:=" + state.get(values.driverclientid),
                                "audiodevice:=--audio-device=" + adevicenum,
                                "videowidth:=" + lastwidth, "videoheight:=" + lastheight, "videobitrate:=" + lastbitrate)));
                        }
                    }

//                    Util.delay(STREAM_CONNECT_DELAY);
                    break;

                /*
                case mic:
                    try {
                        Process p = new ProcessBuilder("sh", "-c",
                            avprog+" -re -f alsa -ac 1 -ar 22050 " +
                            "-i hw:" + adevicenum + " -f flv rtmp://" + host + ":" +
                            port + "/autocrawler/"+STREAM1+ " >/dev/null 2>&1").start();
                    } catch (Exception e){ Util.printError(e);}
                    // avconv -re -f alsa -ac 1 -ar 22050 -i hw:1 -f flv rtmp://127.0.0.1:1935/autocrawler/stream1
                    app.driverCallServer(PlayerCommands.streammode, mode.toString());
                    break;
                */

                case stop:
                    if (state.getBoolean(values.dockcamon)) {
                        lastvideocommand = 0; // so next command not ignored
                        app.driverCallServer(PlayerCommands.dockcam, Settings.OFF);
                        return;
                    }

                    forceShutdownFrameGrabs();

                    killrealsense();
                    killwebrtc();

                    break;

            }

        } }).start();

    }


    private void forceShutdownFrameGrabs() {
        if (state.exists(State.values.writingframegrabs)) {
            state.delete(State.values.writingframegrabs);
        }
    }

    public void framegrab(final String res) {

        state.set(State.values.framegrabbusy.name(), true);

        lastframegrab = System.currentTimeMillis();

        new Thread(new Runnable() { public void run() {

            // resolution check: set to same as main stream params as default
            int width = lastwidth;
            // set lower resolution if required
            if (res.equals(AutoDock.LOWRES)) {
                width=lowreswidth;
            }

            if (!state.exists(values.writingframegrabs)) {
                dumpframegrabs(res);
                Util.delay(STREAM_CONNECT_DELAY);
            }
//            else if (state.getInteger(values.writingframegrabs) != width) {
//                forceShutdownFrameGrabs();
//                Util.delay(STREAM_CONNECT_DELAY);
//                dumpframegrabs(res);
//                Util.delay(STREAM_CONNECT_DELAY);
//            }

            // determine latest image file -- use second latest to resolve incomplete file issues
            int attempts = 0;
            while (attempts < 15) {
                File dir = new File(PATH);
                File imgfile = null;
                long start = System.currentTimeMillis();
                while (imgfile == null && System.currentTimeMillis() - start < 10000) {
                    int highest = 0;
                    int secondhighest = 0;
                    for (File file : dir.listFiles()) {
                        int i = Integer.parseInt(file.getName());
                        if (i > highest) {
                            highest = i;
                        }
                        if (i > secondhighest && i < highest) {
                            imgfile = file;
                            secondhighest = i;
                        }
                    }
                    Util.delay(1);
                }
                if (imgfile == null) {
                    Util.log("framegrab frame unavailable", this);
                    break;
                } else {
                    try {

                        FileInputStream fis = null;
                        byte[] bArray = new byte[(int) imgfile.length()];
                        fis = new FileInputStream(imgfile);
                        fis.read(bArray);
                        fis.close();

                        app.processedImage = new BufferedImage(lastwidth, lastheight, BufferedImage.TYPE_INT_RGB);

                        int i=0;
                        for (int y=0; y< lastheight; y++) {
                            for (int x=0; x<lastwidth; x++) {
                                int argb = (bArray[i]<<16) + (bArray[i+1]<<8) + bArray[i+2];
                                app.processedImage.setRGB(x, y, argb);
                                i += 3;
                            }
                        }

                        break;

                    } catch (Exception e) {
                        e.printStackTrace();
                        attempts++;
                    }
                }
            }

            state.set(values.framegrabbusy, false);

        } }).start();
    }

    private void dumpframegrabs(final String res) {

        // set to same as main stream params as default
        int width = lastwidth;
        int height = lastheight;

        // set lower resolution if required
        if (res.equals(AutoDock.LOWRES)) {
            width=lowreswidth;
            height=lowresheight;
        }

        state.set(State.values.writingframegrabs, width);

        // run ros node
        String topic = "camera/color/image_raw";
        if (state.getBoolean(values.dockcamon)) topic = "usb_cam/image_raw";
        Ros.roscommand("rosrun "+Ros.ROSPACKAGE+" image_to_shm.py _camera_topic:="+topic);

        new Thread(new Runnable() { public void run() {

            while(state.exists(State.values.writingframegrabs)
                    && System.currentTimeMillis() - lastframegrab < Util.ONE_MINUTE) {
                Util.delay(10);
            }

            state.delete(State.values.writingframegrabs);
            // kill ros node
            Ros.roscommand("rosnode kill /image_to_shm");

        } }).start();

    }

    public String record(String mode) { return record(mode, null); }

    // record to flv in webapps/autocrawler/streams/
    @SuppressWarnings("incomplete-switch")
	public String record(String mode, String optionalfilename) {
       
		Util.debug("record("+mode+", " + optionalfilename +"): called.. ", this);

    	IConnection conn = app.grabber;
    	if (conn == null) return null;
        
    	if (state.get(State.values.stream) == null) return null;
        if (state.get(State.values.record) == null) state.set(State.values.record, Application.streamstate.stop.toString());
        if (state.exists(State.values.sounddetect)) if (state.getBoolean(State.values.sounddetect)) return null;

        if (mode.toLowerCase().equals(Settings.TRUE)) {  // TRUE, start recording

            if (state.get(State.values.stream).equals(Application.streamstate.stop.toString())) {
                app.driverCallServer(PlayerCommands.messageclients, "no stream running, unable to record");
                return null;
            }

            if (!state.get(State.values.record).equals(Application.streamstate.stop.toString())) {
                app.driverCallServer(PlayerCommands.messageclients, "already recording, command dropped");
                return null;
            }

            // Get a reference to the current broadcast stream.
            ClientBroadcastStream stream = (ClientBroadcastStream) app.getBroadcastStream(conn.getScope(), STREAM1);

            // Save the stream to disk.
            try {

                String streamName = Util.getDateStamp(); 
                if(optionalfilename != null) streamName += "_" + optionalfilename; 	
                if(state.exists(values.roswaypoint) &&
                        state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString())
                            ) streamName += "_" + state.get(values.roswaypoint);
                streamName = streamName.replaceAll(" ", "_"); // no spaces in filenames 
                
                final String urlString = STREAMSPATH;

                state.set(State.values.record, state.get(State.values.stream));

                switch((Application.streamstate.valueOf(state.get(State.values.stream)))) {
                    case mic:
                        app.messageplayer("recording to: " + urlString+streamName + AUDIO + FMTEXT,
                                State.values.record.toString(), state.get(State.values.record));
                        stream.saveAs(streamName + AUDIO, false);
                        break;

                    case camandmic:
                        if (!Settings.getReference().getBoolean(ManualSettings.useflash)) {
                            ClientBroadcastStream audiostream = (ClientBroadcastStream) app.getBroadcastStream(conn.getScope(), STREAM2);
                            app.messageplayer("recording to: " + urlString+streamName + AUDIO + FMTEXT,
                                    State.values.record.toString(), state.get(State.values.record));
                            audiostream.saveAs(streamName+AUDIO, false);
                        }
                        // BREAK OMITTED ON PURPOSE

                    case camera:
                        app.messageplayer("recording to: " + urlString+streamName + VIDEO + FMTEXT,
                                State.values.record.toString(), state.get(State.values.record));
                        stream.saveAs(streamName + VIDEO, false);
                        break;
                }

                Util.log("recording: "+streamName,this);
                return urlString + streamName;

            } catch (Exception e) {
                Util.printError(e);
            }
        }

        else { // FALSE, stop recording

            if (state.get(State.values.record).equals(Application.streamstate.stop.toString())) {
                app.driverCallServer(PlayerCommands.messageclients, "not recording, command dropped");
                return null;
            }

            ClientBroadcastStream stream = (ClientBroadcastStream) app.getBroadcastStream(conn.getScope(), STREAM1);
            if (stream == null) return null; // if page reload

            state.set(State.values.record, Application.streamstate.stop.toString());

            switch((Application.streamstate.valueOf(state.get(State.values.stream)))) {

                case camandmic:
                    if (!Settings.getReference().getBoolean(ManualSettings.useflash)) {
                        ClientBroadcastStream audiostream = (ClientBroadcastStream) app.getBroadcastStream(conn.getScope(), STREAM2);
//                        app.driverCallServer(PlayerCommands.messageclients, "2nd audio recording stopped");
                        audiostream.stopRecording();
                    }
                    // BREAK OMITTED ON PURPOSE

                case mic:
                    // BREAK OMITTED ON PURPOSE

                case camera:
                    stream.stopRecording();
                    Util.log("recording stopped", this);
                    app.messageplayer("recording stopped", State.values.record.toString(), state.get(State.values.record));
                    break;
            }


        }
        return null;
    }


    public void sounddetect(String mode) {
        if (!state.exists(State.values.sounddetect)) state.set(State.values.sounddetect, false);

        // mode = false
        if (mode.toLowerCase().equals(Settings.FALSE)) {
            if (!state.getBoolean(State.values.sounddetect)) {
                app.driverCallServer(PlayerCommands.messageclients, "sound detection not running, command dropped");
            } else {
                state.set(State.values.sounddetect, false);
                app.driverCallServer(PlayerCommands.messageclients, "sound detection cancelled");
            }
            return;
        }

        // mode = true

        if (!state.get(State.values.stream).equals(Application.streamstate.camandmic.toString()) &&
                !state.get(State.values.stream).equals(Application.streamstate.mic.toString())  ) {
            app.driverCallServer(PlayerCommands.messageclients, "no mic stream, unable to detect sound");
            return;
        }

        if (state.getBoolean(State.values.sounddetect)) {
            app.driverCallServer(PlayerCommands.messageclients, "sound detection already running, command dropped");
            return;
        }

        if (state.get(State.values.record) == null)
            state.set(State.values.record, Application.streamstate.stop.toString());
        if (!state.get(State.values.record).equals(Application.streamstate.stop.toString())) {
            app.driverCallServer(PlayerCommands.messageclients, "record already running, sound detection command dropped");
            return;
        }

        final String filename = "temp";
        final String fullpath = Settings.streamsfolder+Util.sep+filename+AUDIO+FMTEXT;

        new Thread(new Runnable() { public void run() {

            // wait for grabber just in case video just started
            if (app.grabber == null)  {
                long grabbertimeout = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < grabbertimeout) Util.delay(1);
            }
            if (app.grabber == null) { Util.log("error, grabber null", this); return; }

            String streamname = STREAM1;
            if (state.get(State.values.stream).equals(Application.streamstate.camandmic.toString())) streamname = STREAM2;
            ClientBroadcastStream stream = (ClientBroadcastStream) app.getBroadcastStream(app.grabber.getScope(), streamname);

            state.set(State.values.sounddetect, true);
            state.delete(State.values.streamactivity);

            long timeout = System.currentTimeMillis() + Util.ONE_HOUR;
            while (state.getBoolean(State.values.sounddetect) && System.currentTimeMillis() < timeout) {

                double voldB = -99;
                try {

                    // start recording
                    stream.saveAs(filename + AUDIO, false);

                    // wait
                    long cliplength = System.currentTimeMillis() + 5000;
                    while (System.currentTimeMillis() < cliplength && state.getBoolean(State.values.sounddetect))
                        Util.delay(1);

                    // stop recording
                    stream.stopRecording();

                    if (!state.getBoolean(State.values.sounddetect)) { // cancelled during clip
                        new File(fullpath).delete();
                        return;
                    }

                    Process proc = Runtime.getRuntime().exec("ffmpeg -i "+fullpath+" -af volumedetect -f null -");
                    // ffmpeg -i webapps/autocrawler/temp_audio.flv -af volumedetect -f null -

                    String line;
                    BufferedReader procReader = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
                    while ((line = procReader.readLine()) != null) {

                        if (line.contains("max_volume:")) {
                            String[] s = line.split(" ");
                            voldB = Double.parseDouble(s[s.length - 2]);
                            break;
                        }
                    }

                } catch (Exception e) {
                    Util.printError(e);
                    state.set(State.values.sounddetect, false);
                    new File(fullpath).delete();
                    return;
                }

                if (voldB > Settings.getReference().getDouble(ManualSettings.soundthresholdalt) && state.getBoolean(State.values.sounddetect)) {
                    state.set(State.values.streamactivity, "audio " + voldB+"dB");
                    state.set(State.values.sounddetect, false);
                    app.driverCallServer(PlayerCommands.messageclients, "sound detected: "+ voldB+"dB");
                }

                new File(fullpath).delete();
            }

            if (state.getBoolean(State.values.sounddetect)) {
                Util.log("sound detect timed out", this);
                state.set(State.values.sounddetect, false);
            }

        } }).start();


    }

    public void dockcam(String str) {

        if (System.currentTimeMillis() < lastvideocommand + STREAM_CONNECT_DELAY)
            return;

        lastvideocommand = System.currentTimeMillis();

        if ((str.equalsIgnoreCase(Settings.ON) || str.equalsIgnoreCase(Settings.ENABLED))
                && !state.getBoolean(values.dockcamon)) { // turn on

            state.set(values.dockcamon, true);
            state.delete(values.dockcamready); // this is set to true when ready by ros node

            lastwidth = 640;
            lastheight = 480;

            new Thread(new Runnable() { public void run() {

                // nuke currently running cams if any
                if (state.get(values.stream).equals(Application.streamstate.camera.toString()) ||
                        state.get(values.stream).equals(Application.streamstate.camandmic.toString())) {

                    app.driverCallServer(PlayerCommands.streammode, Application.streamstate.stop.toString()); // force GUI video resize
                    app.driverCallServer(PlayerCommands.streammode, Application.streamstate.camera.toString()); // and set state stream
                    killrealsense();
                    killwebrtc();
                    Util.delay(STREAM_CONNECT_DELAY);
                }
                else
                    app.driverCallServer(PlayerCommands.streammode, Application.streamstate.camera.toString());

                if (app.player instanceof IServiceCapableConnection) {// flash client
                    webrtcpstring = Ros.launch(new ArrayList<String>(Arrays.asList("dockcam",
                        "dockdevice:=/dev/video" + dockcamdevicenum)));
                }
                else { // webrtc
                    String peerid = "";
                    if (state.exists(values.driverclientid))
                        peerid = state.get(values.driverclientid);

                    webrtcpstring = Ros.launch(new ArrayList<String>(Arrays.asList("dockwebrtc",
                        "peerid:="+peerid,
                        "dockdevice:=/dev/video" + dockcamdevicenum)));

                }

                state.set(State.values.controlsinverted, true);


            } }).start();

        }
        else if ( (str.equalsIgnoreCase(Settings.OFF) || str.equalsIgnoreCase(Settings.DISABLED)) &&
                state.getBoolean(values.dockcamon)) { // turn off

            state.set(values.dockcamon, false);
            state.delete(values.dockcamready);

            state.set(State.values.controlsinverted, false);
            app.driverCallServer(PlayerCommands.streammode, Application.streamstate.stop.toString());
            killwebrtc();

        }
    }

    public void killrealsense() {
        if (realsensepstring != null && !state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString())) {
            Ros.killlaunch(realsensepstring);
            realsensepstring = null;
        }
    }

    private void killwebrtc() {
        if (webrtcpstring != null) {
            Ros.killlaunch(webrtcpstring);
            webrtcpstring = null;
        }
    }

}