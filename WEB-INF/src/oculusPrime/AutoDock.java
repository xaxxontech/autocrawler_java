package oculusPrime;

import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import oculusPrime.commport.Power;
import oculusPrime.commport.Malg;
import oculusPrime.commport.PowerLogger;

public class AutoDock { 
	
	public static final String UNDOCKED = "un-docked";
	public static final String DOCKED = "docked";
	public static final String DOCKING = "docking";
	public static final String UNKNOWN = "unknown";
	public static final String HIGHRES = "highres";
	public static final String LOWRES = "lowres";
	public enum autodockmodes{ go, dockgrabbed, calibrate, cancel}
	public enum dockgrabmodes{ calibrate, start, find, test }

	private Settings settings = Settings.getReference();
	private String docktarget = settings.readSetting(GUISettings.docktarget);
	private State state = State.getReference();
	private boolean autodockingcamctr = false;
	private int lastcamctr = 0;
	private Malg comport = null;
	private int autodockctrattempts = 0;
	private Application app = null;
	private OculusImage oculusImage = new OculusImage();
	private int rescomp; // (multiplier - javascript sends clicksteer based on 640x480, autodock uses 320x240 images)
	private int allowforClickSteer = 500;
	private int dockattempts = 0;
	private static final int MAXDOCKATTEMPTS = 5;
	private int imgwidth;
	private int imgheight;
	public boolean lowres = true; // TODO: nuke
	public static final int FLHIGH = 25;
	public static final int FLLOW = 7;
	private final int FLCALIBRATE = 2;
	private volatile boolean autodocknavrunning = false;
	private boolean odometrywasrunning = false;
	public static final long DOCKGRABDELAY = 200;

	public AutoDock(Application theapp, Malg com, Power powercom) {
		this.app = theapp;
		this.comport = com;
		oculusImage.dockSettings(docktarget);
		state.set(State.values.autodocking, false);
		if (!settings.getBoolean(ManualSettings.useflash)) allowforClickSteer = 1000; // may need to be higher for rpi... about 1000
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

		if (state.getBoolean(State.values.autodocking) || state.getBoolean(State.values.docking)){
			app.message("auto-dock already in progress", null, null);
			return;
		}

		if (!state.getBoolean(State.values.dockcamon))
			app.driverCallServer(PlayerCommands.dockcam, Settings.ON);

		state.set(State.values.autodocking, true);

		app.message("auto-dock in progress", "motion", "moving");
		Util.log("autodock go", this);
		PowerLogger.append("autodock go", this);

		app.driverCallServer(PlayerCommands.spotlight, "0");

		odometrywasrunning = state.getBoolean(State.values.odometry);
		app.driverCallServer(PlayerCommands.odometrystart, null);

		new Thread(new Runnable() { public void run() {

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
                else if (baselinkdistance <=1 && baselinkdistance > 0.7){
                    autoDockRotate(baselinkangle);
                    autoDockForward(baselinkdistance - 0.6);
                }

                // even closer: rotate, forward, then rotate to face dock
                else if (baselinkdistance <=0.7 && baselinkdistance > 0.55){
//                    if (Math.abs(baselinkangle) < 2 && Math.abs(targetpitch) < 9) {
//                        Util.debug("autodock 0.7-0.55, forward only", this);
//                        autoDockForward(baselinkdistance - 0.5);
//                    }
//                    else { // rotate, forward to 0.7 in front of dock, rotate to point at dock
                        Util.debug("autodock 0.7-0.55, dockfacingmove", this);
                        double[] dockfacingmove = getDockFacingMove(0.5, baselinkdistance, baselinkangle, targetpitch);
                        autoDockRotate(dockfacingmove[0]);
                        autoDockForward(dockfacingmove[1]);
                        autoDockRotate(dockfacingmove[2]);
//                    }
                }

                // right in close
				else if (baselinkdistance <= 0.55) { // min detectable distance is ~0.42 (incl. base_link/cam offset)

					if(Math.abs(baselinkangle) >= 1.3) { // need to ctr dock in view
					    Util.debug("0.55-0, rotate", this);
						autoDockRotate(baselinkangle);
					}

					else {
						if (Math.abs(targetpitch) < 6) { // go for final dock
                            Util.debug("go dock", this);
                            autoDockForward(baselinkdistance - 0.30);
							dock();
							// check if success
							return;
						}
						else { // backup try again
						    Util.debug("backup", this);
						    double distance = 0.6 - baselinkdistance;
							comport.movedistance(Malg.direction.backward, distance);
							Util.delay((long) (distance / state.getDouble(State.values.odomlinearmpms.toString())));
							long start = System.currentTimeMillis();
							while(!state.get(State.values.direction).equals(Malg.direction.stop.toString())
									&& System.currentTimeMillis() - start < 10000) { Util.delay(10); } // wait
                        }
					}
				}


				Util.delay(500+DOCKGRABDELAY);
			}

			autoDockCancel();

			}	}).start();
	}

	public void autoDockCancel() {
		if (!state.getBoolean(State.values.autodocking)) return;

		state.set(State.values.autodocking, false);
		if (state.exists(State.values.driver)) {
			app.message("auto-dock ended", "multiple", "autodockcancelled blank motion stopped");
		}
		state.set(State.values.docking, false);
		app.driverCallServer(PlayerCommands.floodlight, "0");

		if (!odometrywasrunning) app.driverCallServer(PlayerCommands.odometrystop, null);

//		if (!state.exists(State.values.driver))
//			app.driverCallServer(PlayerCommands.publish, Application.streamstate.stop.toString());

	}

	private Boolean dockSearch() {
		Util.debug("docksearch", this);
		// TODO: wait second or two for docktrue(????), then look side to side, then rotate 20deg full rev

		autoDockRotate(15);
		if (state.getBoolean(State.values.dockfound)) return true;
		autoDockRotate(-30);
		if (state.getBoolean(State.values.dockfound)) return true;

		int rot = 0;
		while (rot < 20 && state.getBoolean(State.values.autodocking)) {
			autoDockTurn(Malg.direction.right, 20);
			if (state.getBoolean(State.values.dockfound)) return true;
			rot++;
		}

		return false;
	}

	private void autoDockTurn(Malg.direction dir, double angle) { // blocking, non-progressive turn
		comport.rotate(dir, angle);
		Util.delay(100);
		state.block(State.values.direction, Malg.direction.stop.toString(), 10000);
		Util.delay(Malg.TURNING_STOP_DELAY+DOCKGRABDELAY);
	}

	private void autoDockRotate(double angle) { // blocking, progressive turn
	    if (Math.abs(angle) <1) return;
	    
		Util.debug("autoDockRotate(): "+angle, this);
        state.set(State.values.odomrotating, Settings.FALSE);
        comport.rotate(angle);
        state.block(State.values.odomrotating, Settings.FALSE, 10000);
        Util.delay(DOCKGRABDELAY);
    }

    private void autoDockForward(double distance) { // blocking
		Util.debug("autoDockForward(): "+distance, this);
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

					state.block(oculusPrime.State.values.wallpower, "true", 750); // was 400 then 750, not quite long enough for wallpower
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

							dockGrab(dockgrabmodes.start, 0, 0);
							state.set(State.values.autodocking, true);
							autodockingcamctr = false;
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


	/*
	 * notes
	 * 
	 * boolean autodocking = false; String docktarget; // calibration values s[]
	 * = 0 lastBlobRatio,1 lastTopRatio,2 lastMidRatio,3 lastBottomRatio,4 x,5
	 * y,6 width,7 height,8 slope UP CLOSE 85x70
	 * 1.2143_0.23563_0.16605_0.22992_124_126_85_70_0.00000 FAR AWAY 18x16
	 * 1.125_0.22917_0.19792_0.28819_144_124_18_16_0.00000
	 * 
	 * 
	 * 
	 * 1st go click: dockgrab_findfromxy MODE1 if autodocking = true: if size <=
	 * S1, if not centered: clicksteer to center, dockgrab_find [BREAK] else go
	 * forward CONST time, dockgrab_find [BREAK] if size > S1 && size <=S2
	 * determine N based on slope and blobsize magnitude if not centered +- N:
	 * clicksteer to center +/- N, dockgrab_find [BREAK] go forward N time if
	 * size > S2 if slope and XY not within target: backup, dockgrab_find else :
	 * dock END MODE1
	 * 
	 * events: dockgrabbed_find => enter MODE1 dockgrabbed_findfromxy => enter
	 * MODE1
	 */
	private void autoDockNav(final int fx, final int fy, final int w, final int h, final float slope) {
		if (autodocknavrunning) {
			Util.log("error, autodocknavrunning", this);
			return;
		}
		autodocknavrunning = true;

		new Thread(new Runnable() { public void run() {

			comport.checkisConnectedBlocking();

			int x = fx;
			x = x + (w / 2); // convert to center from upper left
			String s[] = docktarget.split("_");

			int dockw = (int) (Integer.parseInt(s[6])/(rescomp/2f));
			int dockh = (int) (Integer.parseInt(s[7])/(rescomp/2f));
			int dockx = (int) (Integer.parseInt(s[4])/(rescomp/2f)) + dockw / 2;
			float dockslope = new Float(s[8]);
			float slopedeg = (float) ((180 / Math.PI) * Math.atan(slope));
			float dockslopedeg = (float) ((180 / Math.PI) * Math.atan(dockslope));

			// relative-to-calibration target sizes for modes, constants
			final int s1 = (int) (dockw * dockh * 0.07  * w / h);  // (area) medium range start
			final int s2 = (int) (dockw * dockh * 0.40 * w / h); // (area) close range start
			final double s2slopetolerance = 1.2;
			final double s1slopetolerance = 1.3;

			final int s1FWDmilliseconds = (int) comport.voltsComp(500); // 400
			final int s2FWDmilliseconds = (int) comport.voltsComp(250); // 100

			final int hardStopPreDelay = 400;
			final int hardStopPostDelay = 500;

			comport.speedset(Malg.speeds.fast.toString());

			SystemWatchdog.waitForCpu();

			if (w * h < s1) { // mode: quite far away yet, approach only

				if (state.getInteger(State.values.spotlightbrightness) > 0)  comport.setSpotLightBrightness(0);
				if (state.getInteger(State.values.floodlightlevel) == 0) comport.floodLight(FLHIGH);

				if (Math.abs(x - imgwidth/2) > (int) (imgwidth*0.07) )  { // clicksteer
					comport.clickNudge((x - imgwidth / 2) * rescomp, true); // true=firmware timed
					comport.delayWithVoltsComp(allowforClickSteer);
				}

				// go linear
				comport.goForward(s1FWDmilliseconds);
				Util.delay(s1FWDmilliseconds);
				comport.stopGoing();
//				Util.delay(Malg.LINEAR_STOP_DELAY);
				Util.delay(hardStopPreDelay);
				comport.hardStop();
				Util.delay(hardStopPostDelay);

				autodocknavrunning = false;
				dockGrab(dockgrabmodes.find, 0, 0);
				return;

			} // end of S1 check

			else if (w * h >= s1 && w * h < s2) { // medium distance, detect slope when centered and approach

				if (state.getInteger(State.values.spotlightbrightness) > 0)  comport.setSpotLightBrightness(0);
				int fl = state.getInteger(State.values.floodlightlevel);
				if (fl > 0 && fl != 15) comport.floodLight(FLLOW);

				if (autodockingcamctr) { // if cam centered do check and comps below
					autodockingcamctr = false;
					int autodockcompdir = 0;

					final double slopeDiffMax = 7.0;
					double slopeDiff = Math.abs(slopedeg - dockslopedeg);
					if (slopeDiff > slopeDiffMax)   slopeDiff = slopeDiffMax;

					if (slopeDiff > s1slopetolerance) {
						final double magicRatioMin = 0.04;
						final double magicRatioMax = 0.22;
						double magicRatio = magicRatioMax - (slopeDiff/slopeDiffMax)*(magicRatioMax-magicRatioMin);
						autodockcompdir = (int) (imgwidth/2 - w - (int) (imgwidth*magicRatio) -
									Math.abs(imgwidth/2 - x)); // was 160 - w - 25 -Math.abs(160-x)
					}

					if (slope > dockslope) {
						autodockcompdir *= -1;
					} // approaching from left
					autodockcompdir += x + (dockx - imgwidth/2);

					lastcamctr = 0;
					if (Math.abs(autodockcompdir - dockx) > (int) (imgwidth*0.03125)) { // steer and go
						lastcamctr = (autodockcompdir - dockx) * rescomp;

						comport.clickNudge(lastcamctr, true);
						comport.delayWithVoltsComp(allowforClickSteer);

						comport.goForward(s2FWDmilliseconds);
						Util.delay(s2FWDmilliseconds);
						comport.stopGoing();
//						Util.delay(Malg.LINEAR_STOP_DELAY);
						Util.delay(hardStopPreDelay);
						comport.hardStop();
						Util.delay(hardStopPostDelay);

						if (Math.abs(lastcamctr) > imgwidth/4) { // correct in case dock occluded by frame after large move
							comport.clickNudge(-lastcamctr, true);
							comport.delayWithVoltsComp(allowforClickSteer);
						}

						autodocknavrunning = false;
						dockGrab(dockgrabmodes.find, 0, 0);
						return;

					} else { // go only

						comport.goForward(s2FWDmilliseconds);
						Util.delay(s2FWDmilliseconds);
						comport.stopGoing();
//						Util.delay(Malg.LINEAR_STOP_DELAY);
						Util.delay(hardStopPreDelay);
						comport.hardStop();
						Util.delay(hardStopPostDelay);

						autodocknavrunning = false;
						dockGrab(dockgrabmodes.find, 0, 0);
						return;
					}
				} else { // !autodockingcamctr
					autodockingcamctr = true;
					if (Math.abs(x - dockx) > (int) (0.03125*imgwidth) ) {

						comport.clickNudge((x - dockx) * rescomp, true);
						comport.delayWithVoltsComp(allowforClickSteer);

						autodocknavrunning = false;
						dockGrab(dockgrabmodes.find, 0, 0);
						return;

					} else { // centered, onward!
						autodockingcamctr = false;

						comport.goForward(s2FWDmilliseconds);
						Util.delay(s2FWDmilliseconds);
						comport.stopGoing();
//						Util.delay(Malg.LINEAR_STOP_DELAY);
						Util.delay(hardStopPreDelay);
						comport.hardStop();
						Util.delay(hardStopPostDelay);

						dockGrab(dockgrabmodes.find, 0, 0);
						autodocknavrunning = false;
						return;

					}
				}
			}
			else if (w * h >= s2) { // right in close, centering camera only, backup and try again if position wrong

				if ((Math.abs(x - dockx) > 3) && autodockctrattempts <= 7) { // TODO: limit was 10
					autodockctrattempts++;

					int minimum_clicksteerMovement = (int) (0.035*imgwidth); //pixels out of 320 //TODO: this will vary with floor type, make settable
					int movex = (x - dockx);
					if (Math.abs(movex) < minimum_clicksteerMovement) {
						if (movex > 0) { movex = minimum_clicksteerMovement; }
						else { movex = -minimum_clicksteerMovement; }
					}
					comport.clickNudge(movex * rescomp, true);
					comport.delayWithVoltsComp(allowforClickSteer);

					autodocknavrunning = false;
					dockGrab(dockgrabmodes.find, 0, 0);
					return;

				} else {
					if (Math.abs(slopedeg - dockslopedeg) > s2slopetolerance
							|| autodockctrattempts > 10) { // rotate a bit, then backup and try again

						Util.log("autodock backup", this);
						PowerLogger.append("autodock backup", this);
						autodockctrattempts = 0;
						int comp = imgwidth/4;
						if (slope < dockslope) {
							comp = -comp;
						}
						x += comp;

						comport.clickNudge((x - dockx) * rescomp, true);
						comport.delayWithVoltsComp(allowforClickSteer);

						comport.goBackward();
						comport.delayWithVoltsComp(s1FWDmilliseconds);
						comport.stopGoing();
//						Util.delay(Malg.LINEAR_STOP_DELAY); // let deaccelerate
						Util.delay(hardStopPreDelay);
						comport.hardStop();
						Util.delay(hardStopPostDelay);

						autodocknavrunning = false;
						dockGrab(dockgrabmodes.find, 0, 0);
						return;

					} else { // all good, let er rip

						Util.delay(100);
						dock();
						autodocknavrunning = false;
						return;

					}
				}
			}

		} }).start();
	}

	public void getLightLevel() {

		if (!app.frameGrab(LOWRES)) return;

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
					if (Application.framegrabimg != null) {

						// convert bytes to image
						ByteArrayInputStream in = new ByteArrayInputStream(Application.framegrabimg);
						img = ImageIO.read(in);
						in.close();
						
					}
						
					else if (Application.processedImage != null) {
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
					app.message("getlightlevel: " + Integer.toString(avg), null, null);
					state.set(State.values.lightlevel, avg);
					
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}).start();
	}

	public void dockGrab(final dockgrabmodes mode, final int x, final int y) {

		if (state.getBoolean(State.values.dockgrabbusy)) {
			Util.log("dockGrab() error, dockgrabbusy", this);
			return;
		}

		state.delete(oculusPrime.State.values.dockfound);
		state.delete(oculusPrime.State.values.dockmetrics);

		if (state.getBoolean(State.values.framegrabbusy)) {
			app.message("framegrab busy", null, null);
			Util.log("error, framegrab busy", this);
			state.delete(State.values.framegrabbusy); // TODO: testing
			return;
		}

		state.set(oculusPrime.State.values.dockgrabbusy, true);

		String res=HIGHRES;
		if (lowres) res=LOWRES;

		if (!app.frameGrab(res)) {
			state.set(oculusPrime.State.values.dockgrabbusy, false);
			return; // performs stream availability check
		}

		new Thread(new Runnable() {
			public void run() {
				int n = 0;
				while (state.getBoolean(State.values.framegrabbusy)) {
					Util.delay(5);
					n++;
					if (n > 2000) { // give up after 10 seconds
						Util.log("error, frame grab timed out", this);
						state.set(State.values.framegrabbusy, false);
						break;
					}
				}

				BufferedImage img = null;
				if (Application.framegrabimg != null) { // TODO: unused?

					// convert bytes to image
					ByteArrayInputStream in = new ByteArrayInputStream(Application.framegrabimg);

					try {
						img = ImageIO.read(in);
						in.close();
					} catch (IOException e) {
						e.printStackTrace();
					}

				}

				else if (Application.processedImage != null) {
					img = Application.processedImage;
				}

				else { Util.log("dockgrab() framegrab failure", this); return; }

				imgwidth= img.getWidth();
				imgheight= img.getHeight();
				rescomp = 640/imgwidth; // for clicksteer gui 640 window

				float[] matrix = { 0.111f, 0.111f, 0.111f, 0.111f,
						0.111f, 0.111f, 0.111f, 0.111f, 0.111f, };

				BufferedImageOp op = new ConvolveOp(new Kernel(3, 3, matrix));
				img = op.filter(img, new BufferedImage(imgwidth, imgheight, BufferedImage.TYPE_INT_ARGB));

				int[] argb = img.getRGB(0, 0, imgwidth, imgheight, null, 0, imgwidth);

				String[] results;
				String str;

				switch (mode) {
					case calibrate:
						results = oculusImage.findBlobStart(x, y, img.getWidth(), img.getHeight(), argb);
						autoDock(autodockmodes.dockgrabbed.toString() + " " + dockgrabmodes.calibrate.toString() + " " + results[0]
								+ " " + results[1] + " " + results[2] + " "
								+ results[3] + " " + results[4] + " "
								+ results[5] + " " + results[6] + " "
								+ results[7] + " " + results[8]);
						break;

					case start:
						oculusImage.lastThreshhold = -1;
						// break; purposefully omitted

					case find:
						results = oculusImage.findBlobs(argb, imgwidth, imgheight);
						str = results[0] + " " + results[1] + " " + results[2] + " " +
								results[3] + " " + results[4];
						// results = x,y,width,height,slope
						int width = Integer.parseInt(results[2]);

						state.set(State.values.dockgrabbusy.name(), false); // also here because nav timer relys on dockfound

						// interpret results
						if (width < (int) (0.02*imgwidth) || width > (int) (0.875*imgwidth) || results[3].equals("0"))
							state.set(State.values.dockfound, false); // failed to find target! unrealistic widths
						else {
							state.set(State.values.dockfound, true); // success!
							state.set(State.values.dockmetrics, str);
						}

						if (state.getBoolean(State.values.autodocking))
							autoDock(autodockmodes.dockgrabbed.toString()+" "+dockgrabmodes.find.toString()+" "+str);

						break;

					case test:
						oculusImage.lastThreshhold = -1;
						results = oculusImage.findBlobs(argb, imgwidth, imgheight);
						int guix = Integer.parseInt(results[0])/(2/rescomp);
						int guiy = Integer.parseInt(results[1])/(2/rescomp);
						int guiw = Integer.parseInt(results[2])/(2/rescomp);
						int guih = Integer.parseInt(results[3])/(2/rescomp);
						str = guix + " " + guiy + " " + guiw + " " + guih + " " + results[4];
						// results = x,y,width,height,slope

						app.message(str, "autodocklock", str);
						break;
				}

				state.set(State.values.dockgrabbusy, false);

			}
		}).start();
	}

}