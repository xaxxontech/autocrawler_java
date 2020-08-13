package autocrawler;

import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import developer.Ros;
import autocrawler.commport.Power;
import autocrawler.commport.Malg;
import autocrawler.commport.PowerLogger;

public class AutoDock {

    public static final String UNDOCKED = "un-docked";
    public static final String DOCKED = "docked";
    public static final String DOCKING = "docking";
    public static final String UNKNOWN = "unknown";
    public static final String HIGHRES = "highres";
    public static final String LOWRES = "lowres";

    public enum autodockmodes {go, cancel}

    public enum dockgrabmodes {calibrate, start, find, test}

    private Settings settings = Settings.getReference();
    private State state = State.getReference();
    private boolean autodockingcamctr = false;
    private int lastcamctr = 0;
    private Malg comport = null;
    private int autodockctrattempts = 0;
    private Application app = null;
    private int rescomp; // (multiplier - javascript sends clicksteer based on 640x480, autodock uses 320x240 images)
    private int allowforClickSteer = 500;
    private int dockattempts = 0;
    private static final int MAXDOCKATTEMPTS = 3;
    private int imgwidth;
    private int imgheight;
    public boolean lowres = true; // TODO: nuke
    public static final int FLHIGH = 25;
    public static final int FLLOW = 7;
    private final int FLCALIBRATE = 2;
    private volatile boolean autodocknavrunning = false;
    private boolean odometrywasrunning = false;
    public static final long DOCKGRABDELAY = 400;

    public AutoDock(Application theapp, Malg com, Power powercom) {
        this.app = theapp;
        this.comport = com;
        state.set(State.values.autodocking, false);
        if (!settings.getBoolean(ManualSettings.useflash))
            allowforClickSteer = 1000; // may need to be higher for rpi... about 1000
//		if (state.get(State.values.osarch).equals(Application.ARM)) allowforClickSteer = 1000; // raspberry pi, other low power boards
    }

    public void autoDock(String mode) {
        if (mode.equalsIgnoreCase(autodockmodes.cancel.toString())) {
            autoDockCancel();
            return;
        }

        if (state.get(State.values.dockstatus).equals(AutoDock.DOCKED)) return;

        if (!state.getBoolean(State.values.motionenabled)) {
            app.message("motion disabled", "autodockcancelled", null);
            return;
        }

        if (state.getBoolean(State.values.autodocking) || state.getBoolean(State.values.docking)) {
            app.message("auto-dock already in progress", null, null);
            return;
        }

        if (state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString())) {
            app.driverCallServer(PlayerCommands.gotodock, null);
            return;
        }

        if (!state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.stopped.toString()))
            app.driverCallServer(PlayerCommands.stopnav, null);

        if (!state.getBoolean(State.values.dockcamon))
            app.driverCallServer(PlayerCommands.dockcam, Settings.ON);

        state.set(State.values.autodocking, true);

        app.message("auto-dock in progress", "motion", "moving");
        Util.log("autodock go", this);
        PowerLogger.append("autodock go", this);

        app.driverCallServer(PlayerCommands.spotlight, "0");
        app.driverCallServer(PlayerCommands.floodlight, "0");

        odometrywasrunning = state.getBoolean(State.values.odometry);
        app.driverCallServer(PlayerCommands.odometrystart, null);

        new Thread(new Runnable() {
            public void run() {

                state.block(State.values.dockcamready, Settings.TRUE, 20000);

                // the loop
                while (state.getBoolean(State.values.autodocking)) {

                    SystemWatchdog.waitForCpu();

                    if (!state.getBoolean(State.values.dockfound))
                        if (!dockSearch()) break; // target lost even after rotate search

                    // parse dock geometry
                    String[] dockmetrics = state.get(State.values.dockmetrics).split(" ");
                    double baselinkdistance = Double.parseDouble(dockmetrics[0]);
                    double baselinkangle = Double.parseDouble(dockmetrics[1]);
                    double targetpitch = Double.parseDouble(dockmetrics[2]) - 2.0; // favor approach from robots right

                    // far away, turn towards dock then move forward
                    if (baselinkdistance > 1) {
                        autoDockRotate(baselinkangle);
                        autoDockForward(baselinkdistance - 0.9);
                    }

                    // closer: turn towards dock then move forward
                    else if (baselinkdistance > 0.7) {

                    	if (state.getInteger(State.values.floodlightlevel) > FLLOW)
                    		app.driverCallServer(PlayerCommands.floodlight, Integer.toString(FLLOW));

                        autoDockRotate(baselinkangle);
                        autoDockForward(baselinkdistance - 0.6);
                    }

                    // even closer: rotate, forward, then rotate to face dock
                    else if (baselinkdistance > 0.55) {

						if (state.getInteger(State.values.floodlightlevel) > FLLOW)
							app.driverCallServer(PlayerCommands.floodlight, Integer.toString(FLLOW));

						Util.debug("autodock 0.7-0.55, dockfacingmove", this);
                        double[] dockfacingmove = getDockFacingMove(0.5, baselinkdistance, baselinkangle, targetpitch);
                        autoDockRotate(dockfacingmove[0]);
                        autoDockForward(dockfacingmove[1]);
                        autoDockRotate(dockfacingmove[2]);
//                    }
                    }

                    // right in close
                    else if (baselinkdistance <= 0.55) { // min detectable distance is ~0.42 (incl. base_link/cam offset)

						if (state.getInteger(State.values.floodlightlevel) > FLLOW)
							app.driverCallServer(PlayerCommands.floodlight, Integer.toString(FLLOW));

						if (Math.abs(baselinkangle) >= 1.5) { // need to ctr dock in view TODO: sometimes hangs here, trying 1.5 (was 1.3)
                            Util.debug("0.55-0, rotate", this);
                            autoDockRotate(baselinkangle);
                        } else {
                            if (Math.abs(targetpitch) < 6) { // go for final dock
                                Util.debug("go dock", this);
                                autoDockForward(baselinkdistance - 0.25);
                                dock();
                                // check if success
                                return;
                            } else { // backup try again
                                Util.debug("backup", this);
                                double distance = 0.6 - baselinkdistance;
                                comport.movedistance(Malg.direction.backward, distance);
                                Util.delay((long) (distance / state.getDouble(State.values.odomlinearmpms.toString())));
                                long start = System.currentTimeMillis();
                                while (!state.get(State.values.direction).equals(Malg.direction.stop.toString())
                                        && System.currentTimeMillis() - start < 10000) {
                                    Util.delay(10);
                                } // wait
                            }
                        }
                    }


                    Util.delay(500 + DOCKGRABDELAY);
                }

                autoDockCancel();

            }
        }).start();
    }

    public void autoDockCancel() {

		if (state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString())) {
			app.driverCallServer(PlayerCommands.move, Malg.direction.stop.toString());
			return;
		}

        if (!state.getBoolean(State.values.autodocking)) return;

        state.set(State.values.autodocking, false);
        if (state.exists(State.values.driver)) {
            app.message("auto-dock ended", "multiple", "autodockcancelled blank motion stopped");
        }
        state.set(State.values.docking, false);
        app.driverCallServer(PlayerCommands.floodlight, "0");

        if (!odometrywasrunning) app.driverCallServer(PlayerCommands.odometrystop, null);

        app.driverCallServer(PlayerCommands.floodlight, Integer.toString(0)); // light on

//		if (!state.exists(State.values.driver))
//			app.driverCallServer(PlayerCommands.publish, Application.streamstate.stop.toString());

    }

    private Boolean dockSearch() {
        Util.debug("docksearch", this);
        // TODO: wait second or two for docktrue(????), then look side to side, then rotate 20deg full rev

        checkIfDockLightRequired();
        if (state.getBoolean(State.values.dockfound)) return true;
        autoDockRotate(15);
        if (state.getBoolean(State.values.dockfound)) return true;
        autoDockRotate(-30);
        if (state.getBoolean(State.values.dockfound)) return true;

        int rot = 0;
        while (rot < 20 && state.getBoolean(State.values.autodocking)) {
            checkIfDockLightRequired();
            if (state.getBoolean(State.values.dockfound)) return true;
            autoDockTurn(Malg.direction.right, 20);
            rot++;
        }

        return false;
    }

    public void checkIfDockLightRequired() {

        // returns if already on
        if(state.getInteger(State.values.floodlightlevel) != 0)
            return;

        state.delete(State.values.lightlevel); // so docklight only turned on once
        app.driverCallServer(PlayerCommands.getlightlevel, null);
        long timeout = System.currentTimeMillis() + 5000;
        while (!state.exists(State.values.lightlevel) && System.currentTimeMillis() < timeout)
            Util.delay(10);

        if (state.exists(State.values.lightlevel)) {
            if (state.getInteger(State.values.lightlevel) < 20) { // was 25, bit too bright, tried 15, bit too dark
                    app.driverCallServer(PlayerCommands.floodlight, Integer.toString(FLHIGH)); // light on
                Util.delay(2000);  // initial wait for dock found
            }
        }

    }

	private void autoDockTurn(Malg.direction dir, double angle) { // blocking, non-progressive turn
        if (!state.getBoolean(State.values.odometry))
            app.driverCallServer(PlayerCommands.odometrystart, null);

        comport.rotate(dir, angle);
		Util.delay(100);
		state.block(State.values.direction, Malg.direction.stop.toString(), 10000);
		Util.delay(Malg.TURNING_STOP_DELAY+DOCKGRABDELAY);
	}

	private void autoDockRotate(double angle) { // blocking, progressive turn
        Util.debug("autoDockRotate(): "+angle, this);

	    if (Math.abs(angle) <1.0) return;

        if (!state.getBoolean(State.values.odometry))
            app.driverCallServer(PlayerCommands.odometrystart, null);

        state.set(State.values.odomrotating, Settings.FALSE);
        comport.rotate(angle);
        state.block(State.values.odomrotating, Settings.FALSE, 10000);
        Util.delay(DOCKGRABDELAY);
    }

    private void autoDockForward(double distance) { // blocking
		Util.debug("autoDockForward(): "+distance, this);

        if (!state.getBoolean(State.values.odometry))
            app.driverCallServer(PlayerCommands.odometrystart, null);

        comport.movedistance(Malg.direction.forward,  distance);
        Util.delay((long) (distance / state.getDouble(State.values.odomlinearmpms.toString())));
        state.block(State.values.direction, Malg.direction.stop.toString(), 10000);
        Util.delay(DOCKGRABDELAY);
    }

    // https://www.triangle-calculator.com/?what=&q=a%3D0.4+C%3D60+b%3D0.3&submit=Solve
    private double[] getDockFacingMove(double facingdistance, double baselinkdistance, double baselinkangle, double targetpitch) {

	    double gamma = baselinkangle + targetpitch;
        double b = facingdistance;
        double a = baselinkdistance;

        double c = Math.sqrt(a*a + b*b - 2 * a * b * Math.cos(Math.toRadians(gamma)));
        double beta = Math.toDegrees(Math.acos((a*a + c*c - b*b) / (2*a*c)));
        double alpha = 180 - Math.abs(gamma) - beta;

        double turntwo;
        double turnone;

		if (gamma > 0) {
			turnone = baselinkangle + beta;
			turntwo = -(180 - alpha);
		} else {
			turnone = baselinkangle - beta;
			turntwo = 180 - alpha;
		}

        Util.debug("beta, c, alpha: "+beta+" "+c+" "+alpha, this);
        return new double[] { turnone, c, turntwo };
    }

	// final drive the bot in to charger watching for battery change with a blocking thread
	public void dock() {
		
		if (state.getBoolean(State.values.docking))  return;
 
		if (!state.getBoolean(State.values.motionenabled)) {
			app.message("motion disabled", null, null);
			return;
		}

		app.message("docking initiated", "multiple", "speed slow motion moving dock docking");
		state.set(State.values.docking, true);
		state.set(State.values.dockstatus, DOCKING);

//		comport.speedset(Malg.speeds.slow.toString());
		int s = comport.speedslow;
		app.driverCallServer(PlayerCommands.odometrystop, null);

		state.set(State.values.motorspeed, Integer.toString(s));
		state.set(State.values.movingforward, false);

		Util.log("docking initiated", this);
		PowerLogger.append("docking initiated", this);
		
		new Thread(new Runnable() {	
			public void run() {
				comport.checkisConnectedBlocking();
//				comport.goForward();
//				Util.delay((long) comport.voltsComp(300));
//				comport.stopGoing();
				int inchforward = 0;
				while (inchforward < 9 && !state.getBoolean(State.values.wallpower) && // was inchforward < 12
						state.getBoolean(State.values.docking)) {

					// pause in case of pcb reset while docking(fairly common)
					app.powerport.checkisConnectedBlocking();

					comport.goForward();
					Util.delay((long) comport.voltsComp(150)); // was 150
					comport.stopGoing();

					state.block(autocrawler.State.values.wallpower, "true", 750); // was 400 then 750, not quite long enough for wallpower
					inchforward ++;
				}
				
				if(state.getBoolean(State.values.wallpower)) { // dock maybe successful
					comport.checkisConnectedBlocking();
					app.powerport.checkisConnectedBlocking();

					comport.goForward(30); // one more nudge
					Util.delay((long) 50); // comport.voltsComp(150)); // voltscomp not needed, on wallpower
					comport.stopGoing();

					// allow time for charger to get up to voltage
				     // and wait to see if came-undocked immediately (fairly commmon)
					Util.delay(5000);
				}
				
				if(state.get(State.values.dockstatus).equals(DOCKED)) { // dock successful
					
					state.set(State.values.docking, false);
					comport.speedset(Malg.speeds.fast.toString());

					String str = "";

//					if (state.getBoolean(State.values.autodocking)) {
//						state.set(State.values.autodocking, false);
//						str += "cameratilt "+state.get(State.values.cameratilt)+" speed fast autodockcancelled blank";
//						if (!state.get(State.values.stream).equals(Application.streamstate.stop.toString()) &&
//								state.get(State.values.driver)==null) {
//							app.publish(Application.streamstate.stop);
//						}
//						app.driverCallServer(PlayerCommands.floodlight, "0");
//						app.driverCallServer(PlayerCommands.cameracommand, Malg.cameramove.horiz.toString());
//					}

					app.message("docked successfully", "multiple", str);
					Util.log(state.get(State.values.driver) + " docked successfully", this);
					PowerLogger.append(state.get(State.values.driver) + " docked successfully", this);

					autoDockCancel();

                    if (state.getBoolean(State.values.dockcamon))
                        app.driverCallServer(PlayerCommands.dockcam, Settings.OFF);

                    dockattempts = 0;

                } else { // dock fail
					
					if (state.getBoolean(State.values.docking)) {
						state.set(State.values.docking, false); 

						app.message("docking timed out", null, null);
						Util.log("dock(): " + state.get(State.values.driver) + " docking timed out", this);
						PowerLogger.append("dock(): " + state.get(State.values.driver) + " docking timed out", this);

						// back up and retry
						if (dockattempts < MAXDOCKATTEMPTS && state.getBoolean(State.values.autodocking)) {
							dockattempts ++;

							// backup a bit
//							comport.speedset(Malg.speeds.med.toString());
							comport.goBackward();
							comport.delayWithVoltsComp(400);
							comport.stopGoing();
							Util.delay(Malg.LINEAR_STOP_DELAY); // let deaccelerate

							// turn slightly! // TODO: direction should be determined by last slope
							comport.speedset(Malg.speeds.fast.toString());
							comport.clickNudge((imgwidth / 4) * rescomp, true); // true=firmware timed
							comport.delayWithVoltsComp(allowforClickSteer);

							// backup some more
							comport.goBackward();
							comport.delayWithVoltsComp(500);
							comport.stopGoing();
							Util.delay(Malg.LINEAR_STOP_DELAY); // let deaccelerate

                            autoDockCancel();
                            app.message("trying autodock again", null, null);
                            autoDock(autodockmodes.go.toString());
						}
						else { // give up
							state.set(State.values.autodocking, false);
							if (!state.get(State.values.stream).equals(Application.streamstate.stop.toString()) && 
										state.get(State.values.driver)==null) { 
								app.publish(Application.streamstate.stop); 
							}
							
							// back away from dock to avoid sketchy contact
							Util.log("autodock failure, disengaging from dock", this);
							comport.speedset(Malg.speeds.med.toString());
							comport.goBackward();
							Util.delay(400);
							comport.stopGoing();

//							comport.floodLight(0);
//
//							String str = "motion disabled dock "+UNDOCKED+" battery draining cameratilt "
//									+state.get(State.values.cameratilt)+" autodockcancelled blank";
							app.message("dock unsuccessful", null, null);
							autoDockCancel();
						}
					}
						
				}
			}
		}).start();
	}


	public void getLightLevel() {

		if (!app.frameGrab()) return;

		new Thread(new Runnable() {
			public void run() {
				try {
					int n = 0;
					while (state.getBoolean(State.values.framegrabbusy)) {
						Util.delay(5);
						n++;
						if (n > 2000) { // give up after 10 seconds
							state.set(State.values.framegrabbusy, false);
							break;
						}
					}
					
					BufferedImage img = null;
//					if (Application.framegrabimg != null) {
//
//						// convert bytes to image
//						ByteArrayInputStream in = new ByteArrayInputStream(Application.framegrabimg);
//						img = ImageIO.read(in);
//						in.close();
//
//					}
						
//					else
                    if (Application.processedImage != null) {
						img = Application.processedImage;
					}
					
					else { Util.log("dockgrab failure", this); return; }

					n = 0;
					int avg = 0;
					for (int y = 0; y < img.getHeight(); y++) {
						for (int x = 0; x < img.getWidth(); x++) {
							int rgb = img.getRGB(x, y);
							int red = (rgb & 0x00ff0000) >> 16;
							int green = (rgb & 0x0000ff00) >> 8;
							int blue = rgb & 0x000000ff;
							avg += red * 0.3 + green * 0.59 + blue * 0.11; // grey
																			// using
																			// 39-59-11
																			// rule
							n++;
						}
					}
					avg = avg / n;
                    app.driverCallServer(PlayerCommands.messageclients, "state lightlevel: " + Integer.toString(avg));
					state.set(State.values.lightlevel, avg);
					
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}).start();
	}

}