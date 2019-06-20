package developer;

import developer.depth.Stereo;
import oculusPrime.*;
import oculusPrime.State.values;
import oculusPrime.commport.ArduinoPower;
import oculusPrime.commport.ArduinoPrime;

/**
 * Created by colin on 8/3/2016.
 */
public class Calibrate implements Observer{
    private Settings settings = Settings.getReference();;
    private Application app = null;
    static State state = null;
    private double cumulativeangle = 0;

    /** Constructor */
    public Calibrate(Application a) {
        app = a;

        state = State.getReference();
        state.addObserver(this);
    }

    /**
     * rotate until find dock
     * once dockfound, start logging cumulative angle from gyro
     * keep rotating until dock found again
     * use nominal camera FOV angle and dockmetrics to calculate gyro comp
     */
    public void calibrateRotation(final String turndirection) {

        final int REVOLUTIONS = 0; // >0 allows extra time for trackturnrate() to dial in for floor time
                                    // TODO: full rev should be done before odometry turned on, in case turn rate too fast
        new Thread(new Runnable() { public void run() {
            PlayerCommands dir = PlayerCommands.left;
            if (PlayerCommands.right.toString().equals(turndirection)) dir = PlayerCommands.right;

            if (state.getBoolean(values.calibratingrotation)) return;

            state.set(values.calibratingrotation, true);

            if (!state.getBoolean(values.dockcamon))
                app.driverCallServer(PlayerCommands.dockcam, Settings.ON);

            app.driverCallServer(PlayerCommands.spotlight, "0");
//            app.driverCallServer(PlayerCommands.floodlight, Integer.toString(AutoDock.FLHIGH));

            // initial dock target seek, rotate
            int rot = 0;
            while (state.getBoolean(values.calibratingrotation)) {
                SystemWatchdog.waitForCpu();

                long start = System.currentTimeMillis();
                while (!state.exists(values.dockfound.toString()) && System.currentTimeMillis() - start < Util.ONE_MINUTE)
                    Util.delay(10);  // wait

                if (state.getBoolean(values.dockfound)) break; // great, onwards
                else { // rotate a bit (non odometry)
                    app.driverCallServer(dir, "20");
                    Util.delay(100);
                    start = System.currentTimeMillis();
                    while(!state.get(values.direction).equals(ArduinoPrime.direction.stop.toString())
                            && System.currentTimeMillis() - start < 5000) { Util.delay(10); } // wait
                    Util.delay(ArduinoPrime.TURNING_STOP_DELAY);
                }
                rot ++;

                if (rot == 25) { // failure give up
                    app.driverCallServer(PlayerCommands.messageclients, "Calibrate.calibrateRotation() failed to find dock");
                    state.set(values.calibratingrotation, false);
                    return;
                }
            }

            if (!state.getBoolean(values.calibratingrotation)) return;

            // target found, center dock target if necessary, reduce distortion error
//            double compangledegrees = Double.parseDouble(state.get(values.dockmetrics).split(" ")[2]);
//            if (Math.abs(compangledegrees) > 3) {
//                app.driverCallServer(PlayerCommands.rotate, Double.toString(compangledegrees));
//                Util.delay(1000);
//            }
//
//            if (!state.getBoolean(values.dockfound)) {
//                app.driverCallServer(PlayerCommands.messageclients, "Calibrate.calibrateRotation() error, dock target lost");
//                return;
//            }

            //assumed target found, calculate target ctr angle from bot center, turn on gyro
            Util.delay(1000); // allow for latest dock grab to come in
            double firstangledegrees = Double.parseDouble(state.get(values.dockmetrics).split(" ")[1]);

            // start gyro recording
            boolean odometrywasrunning = state.getBoolean(values.odometry);
            state.set(values.odometrybroadcast, ArduinoPrime.ODOMBROADCASTDEFAULT); // TODO: is default now, not required
            app.driverCallServer(PlayerCommands.odometrystart, null);
            cumulativeangle = 0; // negative because cam reversed

            // 2nd dock target seek
            Util.delay(1000); // getting incomplete turns?
            SystemWatchdog.waitForCpu();

            app.driverCallServer(dir, Integer.toString(360*REVOLUTIONS+180)); // assume default settings are pretty good, to speed things up..?
            Util.delay((long) ((360*REVOLUTIONS+180) / state.getDouble(values.odomturndpms.toString())));
            long start = System.currentTimeMillis();
            while(!state.get(values.direction).equals(ArduinoPrime.direction.stop.toString())
                    && System.currentTimeMillis() - start < 15000) { Util.delay(10); } // wait
            Util.delay(ArduinoPrime.TURNING_STOP_DELAY);
            rot = 0;

            while (state.getBoolean(values.calibratingrotation)) {
                SystemWatchdog.waitForCpu();

                if (state.getBoolean(values.dockfound)) break; // great, onwards
                else { // rotate a bit
                    app.driverCallServer(dir, "20");
                    start = System.currentTimeMillis();
                    while(!state.get(values.direction).equals(ArduinoPrime.direction.stop.toString())
                            && System.currentTimeMillis() - start < 5000) { Util.delay(10); } // wait
                    Util.delay(ArduinoPrime.TURNING_STOP_DELAY);
                }
                rot ++;

                if (rot == 25) { // failure give up
                    app.driverCallServer(PlayerCommands.messageclients, "Calibrate.calibrateRotation() failed to find dock");
                    state.set(values.calibratingrotation, false);
                    break;
                }
            }

            if (!state.getBoolean(values.calibratingrotation)) {
                if (!odometrywasrunning) app.driverCallServer(PlayerCommands.odometrystop, null);
                return;
            }

            // center dock target if necessary, reduce distortion error
//            compangledegrees = Double.parseDouble(state.get(values.dockmetrics).split(" ")[2]);
//
//            if (Math.abs(compangledegrees) > 5) {
//                app.driverCallServer(PlayerCommands.rotate, Double.toString(compangledegrees));
//                Util.delay(1000);
//            }
//
//            if (!state.getBoolean(values.dockfound)) {
//                app.driverCallServer(PlayerCommands.messageclients, "Calibrate.calibrateRotation() error, dock target lost");
//                return;
//            }

            // done
            Util.delay(1000); // allow for latest dock grab to come in
            double finalangledegrees = Double.parseDouble(state.get(values.dockmetrics).split(" ")[1]);

            double cameraoffset = firstangledegrees - finalangledegrees;
            if (dir.equals(PlayerCommands.right)) cameraoffset *= -1;

            String msg = "1st cam angle: "+String.format("%.3f",firstangledegrees);
            msg += "<br>2nd cam angle: "+String.format("%.3f", finalangledegrees);
            msg += "<br>cumulative angle reported by gyro: "+String.format("%.3f",cumulativeangle);
            msg += "<br>actual angle moved: "+String.format("%.3f", (360*REVOLUTIONS+360+cameraoffset));
            msg += "<br>original gyrocomp setting: "+Double.toString(settings.getDouble(ManualSettings.gyrocomp));

            double newgyrocomp = (360*REVOLUTIONS+360+cameraoffset)/(cumulativeangle/settings.getDouble(ManualSettings.gyrocomp));
            newgyrocomp = Math.abs(newgyrocomp); // in case of dir = right

            settings.writeSettings(ManualSettings.gyrocomp, String.format("%.4f", newgyrocomp));
            msg += "<br>new gyrocomp setting: "+String.format("%.4f", newgyrocomp);
            app.driverCallServer(PlayerCommands.messageclients, msg); // TODO: debug

            if (!odometrywasrunning) app.driverCallServer(PlayerCommands.odometrystop, null);
            state.set(values.calibratingrotation, false);

        } }).start();

    }

    @Override
    public void updated(String key) {
        if (key.equals(State.values.distanceangle.name()) && state.exists(key) )  { // used by calibrateRotation()
            cumulativeangle -= Double.parseDouble(state.get(values.distanceangle).split(" ")[1]); // negative because cam reversed
        }
    }
}
