package autocrawler;


import autocrawler.navigation.Ros;

import autocrawler.State.values;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


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
    public static long STREAM_CONNECT_DELAY = 3000; // 5000 for ros2...
    private static int dumpfps = 15;
    private static final String STREAMSLINK = "/autocrawler/streams/";
    public static final String FMTEXT = ".3gp";
    public static final String AUDIO = "_audio";
    private static final String VIDEO = "_video";
    private static final String STREAM1 = "stream1";
    private static final String STREAM2 = "stream2";
    private static String ubuntuVersion;
    public String realsensepstring = null;
    private String webrtcpstring = null;
    private ArrayList<String> webrtccmdarray = new ArrayList<>();;
    private volatile long lastvideocommand = 0;
    private Settings settings = Settings.getReference();
    public final static String TURNLOG = Settings.tomcathome + "/log/turnserver.log"; // not working
    public final static String MICWEBRTCPSTRING = "micwebrtc";
    public static final String MICWEBRTC = Settings.tomcathome +"/"+Settings.appsubdir+"/"+MICWEBRTCPSTRING; // gstreamer webrtc microphone c binary
    public static final String SOUNDDETECT = Settings.tomcathome +"/"+Settings.appsubdir+"/sounddetect";
    private String signallingserverpstring = "python3 ./simple-server.py";


    public Video(Application a) {
        app = a;
        ubuntuVersion = Util.getUbuntuVersion();
        setAudioDevice();
        setDockCamVideoDevice();
        launchTURNserver();
        launchSignallingServer();

        String vals[] = settings.readSetting(settings.readSetting(GUISettings.vset)).split("_");
        lastwidth = Integer.parseInt(vals[0]);
        lastheight = Integer.parseInt(vals[1]);
        lastfps = Integer.parseInt(vals[2]);
        lastbitrate = Long.parseLong(vals[3]);

        state.set(State.values.stream, Application.streamstate.stop.toString());
//        if (settings.getBoolean(ManualSettings.ros2))
//            STREAM_CONNECT_DELAY = 5000;

        File theDir = new File(Settings.streamfolder);
        if (!theDir.exists()){
            Util.log("creating folder: "+Settings.streamfolder, this);
            theDir.mkdirs();
        }
    }

    private void setAudioDevice() {
        try {
            String cmd[] = new String[]{"arecord", "--list-devices"}; // arecord --list-devices
            Process proc = Runtime.getRuntime().exec(cmd);
            proc.waitFor();

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

    private void launchTURNserver() {
        killTURNserver();

        // turnserver --user=auto:robot  --realm=xaxxon.com --no-stun --listening-port=3478
        String cmd = "turnserver --user="+settings.readSetting(ManualSettings.turnserverlogin) +
                " --realm=xaxxon.com --no-stun --listening-port=" +
                settings.readSetting(ManualSettings.turnserverport);

        Util.systemCall(cmd);
    }

    protected static void killTURNserver() {
        // kill running instances
        String cmd = "pkill turnserver";
        Util.systemCallBlocking(cmd);
    }

    private void launchSignallingServer() {
//        killSignallingServer();

        String cmd = Settings.tomcathome+Util.sep+"signalling"+Util.sep+"run";
        String portarg = " --port "+settings.readSetting(ManualSettings.webrtcport);
        signallingserverpstring += portarg;
        Util.systemCall(cmd+portarg);
    }

    protected void killSignallingServer() {

        ProcessBuilder processBuilder = new ProcessBuilder("pkill", "-f", signallingserverpstring);

        try {
            Process proc = processBuilder.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void publish (final Application.streamstate mode, final int w, final int h, final int fps, final long bitrate) {
        // todo: disallow unsafe custom values (device can be corrupted?)

        if (System.currentTimeMillis() <= lastvideocommand + STREAM_CONNECT_DELAY) {
            app.driverCallServer(PlayerCommands.messageclients, "video command received too soon after last, dropped");
            return;
        }

        lastvideocommand = System.currentTimeMillis();

        if ( (mode.equals(Application.streamstate.camera) || mode.equals(Application.streamstate.camandmic)) &&
                (state.get(values.stream).equals(Application.streamstate.camera.toString()) ||
                state.get(values.stream).equals(Application.streamstate.camandmic.toString())) &&
                !state.getBoolean(values.dockcamon)
                && !(state.exists(values.navigationroute) && !state.exists(values.nextroutetime))  //route running
                ) {
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

            if (state.getBoolean(values.dockcamon) &&
                    (mode.equals(Application.streamstate.camera) || mode.equals(Application.streamstate.camandmic)) ) {
                lastvideocommand = 0; // so next command not ignored
                app.driverCallServer(PlayerCommands.dockcam, Settings.OFF);
                app.driverCallServer(PlayerCommands.streammode, mode.toString());
                Util.delay(STREAM_CONNECT_DELAY);
            }

            switch (mode) {
                case camera:

                    if (realsensepstring == null) {
                        realsensepstring = Ros.launch(new ArrayList<String>(Arrays.asList(Ros.REALSENSE,
                            "color_width:="+lastwidth, "color_height:="+lastheight, "color_fps:="+lastfps,
                            "enable_depth:=false", "initial_reset:=false" )));
                    }

                    if (state.exists(values.driverclientid)) {

                        webrtccmdarray = new ArrayList<String>(Arrays.asList(Ros.RGBWEBRTC,
                                "peerid:=--peer-id=" + state.get(values.driverclientid),
                                "webrtcserver:=--server=wss://"+settings.readSetting(ManualSettings.webrtcserver)+":"
                                        +settings.readSetting(ManualSettings.webrtcport),
                                "videowidth:=--video-width=" + lastwidth, "videoheight:=--video-height=" + lastheight,
                                "videobitrate:=--video-bitrate=" + lastbitrate,
                                "turnserverport:=--turnserver-port="+settings.readSetting(ManualSettings.turnserverport),
                                "turnserverlogin:=--turnserver-login="+settings.readSetting(ManualSettings.turnserverlogin)
                        ));

                        webrtcpstring = Ros.launch(webrtccmdarray);
                    }

                    break;

                case camandmic:
                                                              // webrtc client
                    if (realsensepstring == null) {
                        realsensepstring = Ros.launch(new ArrayList<String>(Arrays.asList(Ros.REALSENSE,
                            "color_width:="+lastwidth, "color_height:="+lastheight, "color_fps:="+lastfps,
                            "enable_depth:=false", "initial_reset:=false")));
                    }

                    if (state.exists(values.driverclientid)) {

                        webrtccmdarray = new ArrayList<String>(Arrays.asList(Ros.RGBWEBRTC,
                                "peerid:=--peer-id=" + state.get(values.driverclientid),
                                "webrtcserver:=--server=wss://"+settings.readSetting(ManualSettings.webrtcserver)+":"
                                        +settings.readSetting(ManualSettings.webrtcport),
                                "audiodevice:=--audio-device=" + adevicenum,
                                "videowidth:=--video-width=" + lastwidth, "videoheight:=--video-height=" + lastheight,
                                "videobitrate:=--video-bitrate=" + lastbitrate,
                                "turnserverport:=--turnserver-port="+settings.readSetting(ManualSettings.turnserverport),
                                "turnserverlogin:=--turnserver-login="+settings.readSetting(ManualSettings.turnserverlogin)
                        ));

//                        webrtcStatusListener(webrtccmdarray, mode.toString());
                        webrtcpstring = Ros.launch(webrtccmdarray);
                    }

                    break;


                case mic:
                    if (state.exists(values.driverclientid)) {

                        ProcessBuilder processBuilder = new ProcessBuilder();

//                        webrtcpstring = "micwebrtc";
                        webrtcpstring = MICWEBRTC+
                            " --peer-id=" + state.get(values.driverclientid)+
                            " --audio-device=" + adevicenum+
                            " --server=wss://"+settings.readSetting(ManualSettings.webrtcserver)+":"
                                +settings.readSetting(ManualSettings.webrtcport)+
                            " --turnserver-port="+settings.readSetting(ManualSettings.turnserverport)+
                            " --turnserver-login="+settings.readSetting(ManualSettings.turnserverlogin)
                            ;

                        webrtccmdarray = new ArrayList<>();
                        String[] array = webrtcpstring.split(" ");
                        for (String t : array) webrtccmdarray.add(t);
                        processBuilder.command(webrtccmdarray);
                        Process proc = null;

                        try{
                            int attempts = 0;
                            while (attempts<10) {
                                Util.debug("mic webrtc signalling server attempt #"+attempts, this);
                                proc = processBuilder.start();
                                int exitcode = (proc.waitFor());
                                if (exitcode == 0) break;
                                else {
                                    Util.debug("micwebrtc exit code: "+exitcode, this);
                                    if (!state.get(values.stream).equals(Application.streamstate.mic.toString()))
                                        break;
                                }
                                attempts ++;
                            }
                            if (attempts == 10)
                                app.driverCallServer(PlayerCommands.messageclients, "mic webrtc failed to start");

                        } catch (Exception e) { e.printStackTrace(); }

                    }

                    break;


                case stop:
                    if (state.getBoolean(values.dockcamon)) {
                        lastvideocommand = 0; // so next command not ignored
                        app.driverCallServer(PlayerCommands.dockcam, Settings.OFF);
                        return;
                    }

                    forceShutdownFrameGrabs();

                    killrealsense();
                    killwebrtc();
                    Util.systemCall("pkill "+MICWEBRTCPSTRING);

                    break;

            }

        } }).start();

    }

    // checks if webrtcstatus == 'connected' after short time (successful connect to singnalling server)
    // if not, kill roslaunch, relaunch
    // ros2 only
    private void webrtcStatusListener(final ArrayList<String> strarray, final String mode) {

        if (!settings.getBoolean(ManualSettings.ros2)) return;

        state.delete(values.webrtcstatus); // required because ros2 launch files don't send SIGINT to processes on shutdown

        new Thread(new Runnable() { public void run() {

            int attempts = 0;
            while (mode.equals(state.get(values.stream)) && attempts < 5) {
                if (state.block(values.webrtcstatus, "connected", 5000)) return;

                if (!mode.equals(state.get(values.stream))) return;

                Util.log("!connected, relaunching webrtcpstring", this);
                killwebrtc();
                Util.delay(5500);
                state.delete(values.webrtcstatus);
                Ros.launch(strarray); // this only works once! because launch modifies it in mem

                attempts ++;
            }

            if (attempts >= 5) Util.log("webrtc signalling server connection attempt max reached, giving up", this);

        } }).start();

    }

    // TODO: UNUSED, never really helped, check js then nuke
    // restart webrtc connection, called by javascript for periodic webkit connect failure
    public void webrtcRestart() {
        new Thread(new Runnable() { public void run() {
            String w = webrtcpstring;
            killwebrtc();
            webrtcpstring = w;
            Util.delay(1000);
            Ros.launch(webrtccmdarray);
        } }).start();
    }

    private void forceShutdownFrameGrabs() {
        if (state.exists(State.values.writingframegrabs))
            state.delete(State.values.writingframegrabs);
    }

    public void framegrab() {

        state.set(State.values.framegrabbusy, true);

        lastframegrab = System.currentTimeMillis();

        new Thread(new Runnable() { public void run() {

            if (!state.exists(values.writingframegrabs))
                dumpframegrabs();

            File dir = new File(PATH);
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000) {
                if (dir.isDirectory()) break;
                Util.delay(100);
            }

            if (!dir.isDirectory()) {
                Util.log(PATH+" unavailable", this);
                state.set(values.framegrabbusy, false);
                return;
            }

            // determine latest autocrawler.image file -- use second latest to resolve incomplete file issues
            int attempts = 0;
            while (attempts < 15) {

                File imgfile = null;
                start = System.currentTimeMillis();
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
                                int argb;
                                argb = (bArray[i]<<16) + (bArray[i+1]<<8) + bArray[i+2];

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

    public void dumpframegrabs() {

        Util.debug("dumpframegrabs()", this);

        new Thread(new Runnable() { public void run() {

            if (state.exists(values.writingframegrabs)) {
                forceShutdownFrameGrabs();
                Util.delay(STREAM_CONNECT_DELAY); // allow ros node time to exit
            }

            if (state.exists(values.writingframegrabs)) return; // just in case

            state.set(State.values.writingframegrabs, true);

            // ros1 realsense: /camera/color/image_raw
            // ros1 dockcam:  /usb_cam/image_raw
            // ros2 realsese: /color/image_raw
            // ros2 dockcam: /image_raw

            if (settings.getBoolean(ManualSettings.ros2))
                Ros.roscommand("ros2 run "+ Ros.ROSPACKAGE + " "+Ros.IMAGE_TO_SHM);
            else {
                String topic = "camera/color/image_raw";
                if (state.getBoolean(values.dockcamon)) topic = "usb_cam/image_raw";
                Ros.roscommand("rosrun " + Ros.ROSPACKAGE + " " + Ros.IMAGE_TO_SHM + " _camera_topic:=" + topic);
            }

            while(state.exists(State.values.writingframegrabs)
                    && System.currentTimeMillis() - lastframegrab < Util.ONE_MINUTE) {
                Util.delay(10);
            }

            state.delete(State.values.writingframegrabs);

            // kill ros node
            if (settings.getBoolean(ManualSettings.ros2))
                Ros.ros2kill(Ros.IMAGE_TO_SHM);
            else
                Ros.roscommand("rosnode kill /image_to_shm");

        } }).start();

    }

    public String record(String mode) { return record(mode, null); }

    // record to video file in webapps/autocarwler/streams/
    public String record(String mode, String optionalfilename) {

        Util.debug("record("+mode+", " + optionalfilename +"): called.. ", this);

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

            // Save the stream to disk.
            try {

                String streamName = Util.getDateStamp();
                if(optionalfilename != null) streamName += "_" + optionalfilename;
                if(state.exists(values.roswaypoint) &&
                        state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString())
                ) streamName += "_" + state.get(values.roswaypoint);
                streamName = streamName.replaceAll(" ", "_") + FMTEXT; // no spaces in filenames

                state.set(State.values.record, state.get(State.values.stream));

                app.messageplayer("recording to: " + STREAMSLINK +streamName,
                        State.values.record.toString(), state.get(State.values.record));

//                recordvideopstring = Ros.launch(new ArrayList<String>(Arrays.asList(Ros.RECORDVIDEO,
//                        "audiodevice:=--audio-device=" + adevicenum,
//                        "videowidth:=--video-width=" + lastwidth, "videoheight:=--video-height=" + lastheight,
//                        "videobitrate:=--video-bitrate=" + lastbitrate,
//                        "recordpath:=--record-path=" + Settings.streamfolder+streamName
//                        )));

                String cmd = "rosrun "+Ros.ROSPACKAGE+" "+ Ros.RECORDVIDEO +
                        " --audio-device=" + adevicenum +
                        " --video-width=" + lastwidth + " --video-height=" + lastheight +
                        " --video-bitrate=" + lastbitrate +
                        " --record-path=" + Settings.streamfolder+streamName +
                        " image_raw:=/camera/color/image_raw";
                Ros.roscommand(cmd);

                Util.log("recording: "+streamName,this);
                return STREAMSLINK + streamName;

            } catch (Exception e) {
                Util.printError(e);
            }
        }

        else { // FALSE, stop recording

            if (state.get(State.values.record).equals(Application.streamstate.stop.toString())) {
                app.driverCallServer(PlayerCommands.messageclients, "not recording, command dropped");
                return null;
            }

            state.set(State.values.record, Application.streamstate.stop.toString());

            Ros.roscommand("rosnode kill /"+Ros.RECORDVIDEO);

            Util.log("recording stopped", this);
            app.messageplayer("recording stopped", State.values.record.toString(), state.get(State.values.record));

        }
        return null;
    }

    public void sounddetectgst(String mode) {

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

        if (state.get(values.stream).equals(Application.streamstate.camandmic.toString())  ||
                state.get(values.stream).equals(Application.streamstate.mic.toString()) ) {
            app.driverCallServer(PlayerCommands.messageclients, "mic already running, command dropped");
            return;
        }

        app.driverCallServer(PlayerCommands.messageclients, "sound detection enabled");

        state.set(State.values.sounddetect, true);
        state.delete(State.values.streamactivity);

        new Thread(new Runnable() { public void run() {

            long timeout = System.currentTimeMillis() + Util.ONE_HOUR;

            ProcessBuilder processBuilder = new ProcessBuilder();

            String cmd = SOUNDDETECT+" "+ adevicenum;

            List <String> args = new ArrayList<>();;
            String[] array = cmd.split(" ");
            for (String t : array) args.add(t);
            processBuilder.command(args);

            try {
                Process proc = processBuilder.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));

                String line = reader.readLine(); // skip 1st line
                while ((line = reader.readLine()) != null && state.getBoolean(State.values.sounddetect)) {
                    double voldB = Double.parseDouble(line);
                    Util.debug("soundlevel: "+voldB, this);
                    if (Double.parseDouble(line) > settings.getDouble(ManualSettings.soundthreshold)) {
                        state.set(State.values.streamactivity, "audio " + voldB+"dB");
                        app.driverCallServer(PlayerCommands.messageclients, "sound detected: "+ voldB+" dB");
                        break;
                    }
                }

                state.set(State.values.sounddetect, false);
                proc.destroy();
                app.driverCallServer(PlayerCommands.messageclients, "sound detection disabled");

            } catch (Exception e) { e.printStackTrace(); }

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

                String peerid = "";
                if (state.exists(values.driverclientid))
                    peerid = state.get(values.driverclientid);

//                webrtcpstring = Ros.launch(new ArrayList<String>(Arrays.asList(Ros.DOCKWEBRTC,
//                        "peerid:="+peerid,
//                        "webrtcserver:=wss://"+settings.readSetting(ManualSettings.webrtcserver)+":"
//                                +settings.readSetting(ManualSettings.webrtcport),
//                        "dockdevice:=/dev/video" + dockcamdevicenum,
//                        "turnserverport:="+settings.readSetting(ManualSettings.turnserverport),
//                        "turnserverlogin:="+settings.readSetting(ManualSettings.turnserverlogin),
//                        "dockoffset:="+settings.readSetting(ManualSettings.dockoffset)
//                )));


                webrtcpstring = Ros.launch(new ArrayList<String>(Arrays.asList(Ros.DOCKWEBRTC,
                        "peerid:=--peer-id=" + peerid,
                        "webrtcserver:=--server=wss://"+settings.readSetting(ManualSettings.webrtcserver)+":"
                                +settings.readSetting(ManualSettings.webrtcport),
                        "turnserverport:=--turnserver-port="+settings.readSetting(ManualSettings.turnserverport),
                        "turnserverlogin:=--turnserver-login="+settings.readSetting(ManualSettings.turnserverlogin),
                        "dockdevice:=/dev/video" + dockcamdevicenum
                )));


                state.set(State.values.controlsinverted, true);

//                if (state.exists(State.values.driver))
//                    app.driverCallServer(PlayerCommands.clientjs, "depthView dock");

            } }).start();

        }

        else if ( (str.equalsIgnoreCase(Settings.OFF) || str.equalsIgnoreCase(Settings.DISABLED)) &&
                state.getBoolean(values.dockcamon)) { // turn off

            if (state.exists(State.values.driver))
                app.driverCallServer(PlayerCommands.clientjs, "dockview off");

            killwebrtc();

            state.set(values.dockcamon, false);
            state.delete(values.dockcamready);

            state.set(State.values.controlsinverted, false);
            app.driverCallServer(PlayerCommands.streammode, Application.streamstate.stop.toString());

            forceShutdownFrameGrabs();

        }
    }


    public void killrealsense() {
        if (realsensepstring != null && !state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString())) {
            if (settings.getBoolean(ManualSettings.ros2))
                Ros.ros2kill(Ros.REALSENSE);
            else
                Ros.kill(realsensepstring);
            realsensepstring = null;
        }
    }


    private void killwebrtc() {
        if (webrtcpstring != null) {
            if (settings.getBoolean(ManualSettings.ros2)) {
                if (state.getBoolean(values.dockcamon))
                    Ros.ros2kill(Ros.DOCKWEBRTC);
                else
                    Ros.ros2kill(Ros.RGBWEBRTC);
            }
            else
                Ros.kill(webrtcpstring);
            webrtcpstring = null;
        }
    }

}