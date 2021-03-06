package autocrawler;

import autocrawler.*;
import autocrawler.State.values;
import autocrawler.commport.Malg;

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

        new Thread(new Runnable() { public void run() {
            PlayerCommands dir = PlayerCommands.left;
            if (PlayerCommands.right.toString().equals(turndirection)) dir = PlayerCommands.right;

            if (state.getBoolean(values.calibratingrotation)) return;

            state.set(values.calibratingrotation, true);

            if (!state.getBoolean(values.dockcamon))
                app.driverCallServer(PlayerCommands.dockcam, Settings.ON);

            state.block(State.values.dockcamready, Settings.TRUE, 20000);

            // initial dock target seek, rotate
            int rot = 0;
            while (state.getBoolean(values.calibratingrotation)) {

                app.docker.checkIfDockLightRequired();

                Util.delay(AutoDock.DOCKGRABDELAY);

                if (state.getBoolean(values.dockfound)) break; // great, onwards

                if (rot == 25) { // failure give up
                    app.driverCallServer(PlayerCommands.messageclients, "Calibrate.calibrateRotation() failed to find dock");
                    state.set(values.calibratingrotation, false);
                    return;
                }

                // rotate a bit (non odometry)
                app.driverCallServer(dir, "20");
                Util.delay(100);
                long start = System.currentTimeMillis();
                while(!state.get(values.direction).equals(Malg.direction.stop.toString())
                        && System.currentTimeMillis() - start < 5000) { Util.delay(10); } // wait
                Util.delay(Malg.TURNING_STOP_DELAY);
                rot ++;

            }

            if (!state.getBoolean(values.calibratingrotation)) return;


            // 1st target found, so start gyro recording
            boolean odometrywasrunning = state.getBoolean(values.odometry);
            state.set(values.odometrybroadcast, Malg.ODOMBROADCASTDEFAULT); // TODO: is default now, not required
            app.driverCallServer(PlayerCommands.odometrystart, null);
            cumulativeangle = 0; // negative because cam reversed

            double firstangledegrees = Double.parseDouble(state.get(values.dockmetrics).split(" ")[1]);

            // turn 180 without target seek to speed things up, assume default setting are pretty good
            app.driverCallServer(dir, Integer.toString(180));
            Util.delay((long) ((180) / state.getDouble(values.odomturndpms.toString())));
            long start = System.currentTimeMillis();
            while(!state.get(values.direction).equals(Malg.direction.stop.toString())
                    && System.currentTimeMillis() - start < 15000) { Util.delay(10); } // wait
            Util.delay(Malg.TURNING_STOP_DELAY);
            rot = 0;

            // 2nd dock target seek
            while (state.getBoolean(values.calibratingrotation)) {

                Util.delay(AutoDock.DOCKGRABDELAY);

                if (state.getBoolean(values.dockfound)) break; // great, onwards

                if (rot == 25) { // failure give up
                    app.driverCallServer(PlayerCommands.messageclients, "Calibrate.calibrateRotation() failed to find dock");
                    state.set(values.calibratingrotation, false);
                    break;
                }

                app.driverCallServer(dir, "20");
                start = System.currentTimeMillis();
                while(!state.get(values.direction).equals(Malg.direction.stop.toString())
                        && System.currentTimeMillis() - start < 5000) { Util.delay(10); } // wait
                Util.delay(Malg.TURNING_STOP_DELAY);

                rot ++;

            }

            if (!state.getBoolean(values.calibratingrotation)) {
                if (!odometrywasrunning) app.driverCallServer(PlayerCommands.odometrystop, null);
                return;
            }


            // done
            double finalangledegrees = Double.parseDouble(state.get(values.dockmetrics).split(" ")[1]);

            double cameraoffset = firstangledegrees - finalangledegrees;
            if (dir.equals(PlayerCommands.right)) cameraoffset *= -1;

            String msg = "1st cam angle: "+String.format("%.3f",firstangledegrees);
            msg += "<br>2nd cam angle: "+String.format("%.3f", finalangledegrees);
            msg += "<br>cumulative angle reported by gyro: "+String.format("%.3f",cumulativeangle);
            msg += "<br>actual angle moved: "+String.format("%.3f", (360+cameraoffset));
            msg += "<br>original gyrocomp setting: "+Double.toString(settings.getDouble(ManualSettings.gyrocomp));

            double newgyrocomp = (360+cameraoffset)/(cumulativeangle/settings.getDouble(ManualSettings.gyrocomp));
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
