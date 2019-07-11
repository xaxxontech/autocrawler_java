package oculusPrime;


import developer.Ros;
import org.red5.server.api.IConnection;
import org.red5.server.api.service.IServiceCapableConnection;
import org.red5.server.stream.ClientBroadcastStream;

import oculusPrime.State.values;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;


@SuppressWarnings("MagicConstant")
public class Video {

    private static State state = State.getReference();
    private Application app = null;
    private String port;
    private int dockcamdevicenum = 3;
    private int adevicenum = 1;
    private static final int defaultquality = 5;
    private static final int quality720p = 7;
    public static final int defaultwidth=640;
    private static final int defaultheight=480;
    public static final int lowreswidth=320;
    private static final int lowresheight=240;
    private static final String PATH="/dev/shm/avconvframes/";
    private static final String EXT=".bmp";
    private volatile long lastframegrab = 0;
    private int lastwidth=0;
    private int lastheight=0;
    private int lastfps=0;
    private int lastquality = 0;
    private Application.streamstate lastmode = Application.streamstate.stop;
    private long publishid = 0;
    private String avprog = "avconv"; // avconv or ffmpeg
    public static long STREAM_CONNECT_DELAY = 4000;
    private static int dumpfps = 15;
    private static final String STREAMSPATH="/oculusPrime/streams/";
    public static final String FMTEXT = ".flv";
    public static final String AUDIO = "_audio";
    private static final String VIDEO = "_video";
    private static final String STREAM1 = "stream1";
    private static final String STREAM2 = "stream2";
    private static String ubuntuVersion;

    public Video(Application a) {
        app = a;
        port = Settings.getReference().readRed5Setting("rtmp.port");
        ubuntuVersion = Util.getUbuntuVersion();
        setAudioDevice();
        setDockCamVideoDevice();
    }

    public void initAvconv() {
        state.set(State.values.stream, Application.streamstate.stop.toString());
        if (state.get(State.values.osarch).equals(Application.ARM)) {
//            avprog = FFMPEG;
//            dumpfps = 8;
//            STREAM_CONNECT_DELAY=3000;
        }
        File dir=new File(PATH);
        dir.mkdirs(); // setup shared mem folder
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

    public void publish (final Application.streamstate mode, final int w, final int h, final int fps) {
        // todo: determine video device (in constructor)
        // todo: disallow unsafe custom values (device can be corrupted?)

//        if (w==lastwidth && h==lastheight && fps==lastfps && mode.equals(lastmode)) {
//            Util.log("identical stream already running, dropped", this);
//            return;
//        }

        lastwidth = w;
        lastheight = h;
        lastfps = fps;
        lastmode = mode;
        final long id = System.currentTimeMillis();
        publishid = id;

        lastquality = defaultquality;
        if (w > defaultwidth) lastquality = quality720p;
        final int q = lastquality;

        new Thread(new Runnable() { public void run() {

            String host = "127.0.0.1";
            if (state.exists(State.values.relayserver))
                host = state.get(State.values.relayserver);

            // nuke currently running avconv if any
//            if (!state.get(State.values.stream).equals(Application.streamstate.stop.toString()) &&
//                    !mode.equals(Application.streamstate.stop.toString())) {
//                forceShutdownFrameGrabs();
//                Util.systemCallBlocking("pkill "+avprog);
//                Util.delay(STREAM_CONNECT_DELAY);
//            }

            if (state.getBoolean(values.dockcamon) && !mode.equals(Application.streamstate.stop.toString())) {
                app.driverCallServer(PlayerCommands.dockcam, Settings.OFF);
                Util.delay(STREAM_CONNECT_DELAY);
            }

            switch (mode) {
                case camera:
                    if (app.player instanceof IServiceCapableConnection) // flash client
                        Ros.launch("rgbpublish");
                    else
                        Ros.launch("rgbwebrtc");

                    Util.delay(STREAM_CONNECT_DELAY);
                    app.driverCallServer(PlayerCommands.streammode, mode.toString());
                    break;

                /*
                case mic:
                    try {
                        Process p = new ProcessBuilder("sh", "-c",
                            avprog+" -re -f alsa -ac 1 -ar 22050 " +
                            "-i hw:" + adevicenum + " -f flv rtmp://" + host + ":" +
                            port + "/oculusPrime/"+STREAM1+ " >/dev/null 2>&1").start();
                    } catch (Exception e){ Util.printError(e);}
                    // avconv -re -f alsa -ac 1 -ar 22050 -i hw:1 -f flv rtmp://127.0.0.1:1935/oculusPrime/stream1
                    app.driverCallServer(PlayerCommands.streammode, mode.toString());
                    break;
                case camandmic:
                    try {
                        new ProcessBuilder("sh", "-c",
                            avprog+" -re -f alsa -ac 1 -ar 22050 " +
                            "-i hw:" + adevicenum + " -f flv rtmp://" + host + ":" +
                            port + "/oculusPrime/"+STREAM2+ " >/dev/null 2>&1").start();

                        new ProcessBuilder("sh", "-c",
                            avprog+" -f video4linux2 -s " + w + "x" + h + " -r " + fps +
                            " -i /dev/video" + devicenum + " -f flv -q " + q + " rtmp://" + host + ":" +
                            port + "/oculusPrime/"+STREAM1+ " >/dev/null 2>&1").start();

                    } catch (Exception e){ Util.printError(e);}
                    // avconv -re -f alsa -ac 1 -ar 22050 -i hw:1 -f flv rtmp://127.0.0.1:1935/oculusPrime/stream2


                    app.driverCallServer(PlayerCommands.streammode, mode.toString());

                    break;
                */
                case stop:
                    if (state.getBoolean(values.dockcamon)) {
                        app.driverCallServer(PlayerCommands.dockcam, Settings.OFF);
                        return;
                    }

                    forceShutdownFrameGrabs();
//                    Util.systemCall("pkill "+avprog);
                    Ros.roscommand("rosnode kill /camera/realsense2_camera_manager");
//                    Ros.roscommand("rosnode kill /camera/realsense2_camera /gst_video_server");
//                    Ros.roscommand("rosnode kill /realsense2_camera");
//                    Ros.roscommand("rosnode kill /gst_video_server");

                    app.driverCallServer(PlayerCommands.streammode, mode.toString());
                    break;

            }

        } }).start();

    }


    private void forceShutdownFrameGrabs() {
        if (state.exists(State.values.writingframegrabs)) {
            state.delete(State.values.writingframegrabs);
//            Util.delay(STREAM_CONNECT_DELAY);
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
            else if (state.getInteger(values.writingframegrabs) != width) {
//                Util.log("dumpframegrabs() not using width: "+width, this);
                forceShutdownFrameGrabs();
                Util.delay(STREAM_CONNECT_DELAY);
                dumpframegrabs(res);
                Util.delay(STREAM_CONNECT_DELAY);
            }

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
                        int i = Integer.parseInt(file.getName().split("\\.")[0]);
                        if (i > highest) {
//                            imgfile = file;
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
                    Util.log(avprog + " frame unavailable", this);
                    break;
                } else {
                    try {
                        // 640x480 = 921654 bytes (640*480*3 + 54)
                        // 320x240 = 230454  bytes (320*240*3 + 54)
//                        long size = imgfile.length();
//                        if (size != 230454 && size != 921654) {  // doesn't allow for max res, or any other res
//                       if (size <= 54) { // image must be bigger than bitmap header size!
//                       if (size != imgsizebytes) { // image must be correct size
//                            Util.log("wrong size ("+size+" bytes) image file, trying again, attempt "+(attempts+1), this);
//                            attempts++;
//                            continue;
//                        }
                        app.processedImage = ImageIO.read(imgfile);
                        break;
                    } catch (IOException e) {
                        Util.printError(e);
                        attempts++;
                    }
                }
            }

            state.set(values.framegrabbusy, false);

        } }).start();
    }

    private void dumpframegrabs(final String res) {

        File dir=new File(PATH);
        for(File file: dir.listFiles()) file.delete(); // nuke any existing files

        // set to same as main stream params as default
        int width = lastwidth;
        int height = lastheight;
        int q = lastquality;

        // set lower resolution if required
        if (res.equals(AutoDock.LOWRES)) {
            width=lowreswidth;
            height=lowresheight;
        }

        state.set(State.values.writingframegrabs, width);

        String host = "127.0.0.1";
        if (state.exists(State.values.relayserver))
            host = state.get(State.values.relayserver);

        try {
            if ( ! Application.UBUNTU1604.equals(ubuntuVersion) ) { // 14.04 and lower
                Runtime.getRuntime().exec(new String[]{avprog, "-analyzeduration", "0", "-i",
                        "rtmp://" + host + ":" + port + "/oculusPrime/" + STREAM1 + " live=1", "-s", width + "x" + height,
                        "-r", Integer.toString(dumpfps), "-q", Integer.toString(q), PATH + "%d" + EXT});
                // avconv -analyzeduration 0 -i "rtmp://127.0.0.1:1935/oculusPrime/stream1 live=1" -s 640x480 -r 15 -q 5 /dev/shm/avconvframes/%d.bmp
            } else {
                Runtime.getRuntime().exec(new String[]{avprog, "-analyzeduration", "0", "-rtmp_live", "live", "-i",
                        "rtmp://" + host + ":" + port + "/oculusPrime/" + STREAM1, "-s", width + "x" + height,
                        "-r", Integer.toString(dumpfps), "-q", Integer.toString(q), PATH + "%d" + EXT});
                // avconv -analyzeduration 0 -rtmp_live live -i rtmp://127.0.0.1:1935/oculusPrime/stream1 -s 640x480 -r 15 -q 5 /dev/shm/avconvframes/%d.bmp
            }
        }catch (Exception e) { Util.printError(e); }

        
        new Thread(new Runnable() { public void run() {

            Util.delay(500); // required?

            // continually clean all but the latest few files, prevent mem overload
            int i=1;
            while(state.exists(State.values.writingframegrabs)
                    && System.currentTimeMillis() - lastframegrab < Util.ONE_MINUTE) {
                File file = new File(PATH+i+EXT);
                if (file.exists() && new File(PATH+(i+32)+EXT).exists()) {
                    file.delete();
                    i++;
                }
                Util.delay(50);
            }

            state.delete(State.values.writingframegrabs);
            Util.systemCall("pkill -n " + avprog); // kills newest only
            File dir=new File(PATH);
            for(File file: dir.listFiles()) file.delete(); // clean up (gets most files)

        } }).start();

    }

    public String record(String mode) { return record(mode, null); }

    // record to flv in webapps/oculusPrime/streams/
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
                    // ffmpeg -i webapps/oculusPrime/temp_audio.flv -af volumedetect -f null -

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
        if ((str.equalsIgnoreCase(Settings.ON) || str.equalsIgnoreCase(Settings.ENABLED)) && !state.getBoolean(values.dockcamon)) { // turn on

            state.delete(values.dockcamready); // this is set to true when ready by ros node

            new Thread(new Runnable() { public void run() {

                // nuke currently running ros cam if any
                if (!state.get(State.values.stream).equals(Application.streamstate.stop.toString())) {
                    app.driverCallServer(PlayerCommands.publish,Application.streamstate.stop.toString() );
                    Util.delay(STREAM_CONNECT_DELAY);
                }

                state.set(values.dockcamon, true);
                state.set(State.values.controlsinverted, true);

                Ros.roscommand("roslaunch df dockcam.launch dockdevice:=/dev/video"+dockcamdevicenum);
                Util.delay(STREAM_CONNECT_DELAY);
                app.driverCallServer(PlayerCommands.streammode, Application.streamstate.camera.toString());

            } }).start();

        }
        else if ((str.equalsIgnoreCase(Settings.OFF) || str.equalsIgnoreCase(Settings.DISABLED)) && state.getBoolean(values.dockcamon)) { // turn off

            state.set(values.dockcamon, false);
            state.delete(values.dockcamready);

            state.set(State.values.controlsinverted, false);
//            Ros.roscommand("rosnode kill /gst_video_server /usb_cam");
            Ros.roscommand("rosnode kill /usb_cam");
            app.driverCallServer(PlayerCommands.streammode, Application.streamstate.stop.toString());
        }
    }

}