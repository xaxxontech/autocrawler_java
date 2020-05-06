package developer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;

import autocrawler.*;
import autocrawler.commport.Malg;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import developer.image.OpenCVObjectDetect;
import autocrawler.AutoDock.autodockmodes;
import autocrawler.State.values;
import autocrawler.servlet.FrameGrabHTTP;

public class Navigation implements Observer {

    public enum lidarstate { enabled, disabled }
    protected static Application app = null;
	private static State state = State.getReference();
	public static final String DOCK = "dock"; // waypoint name
	private static final String redhome = System.getenv("RED5_HOME");
	public static final File navroutesfile = new File(redhome+"/conf/navigationroutes.xml");
	public static final long WAYPOINTTIMEOUT = Util.TEN_MINUTES;
	public static final long NAVSTARTTIMEOUT = Util.TWO_MINUTES;
	public static final int RESTARTAFTERCONSECUTIVEROUTES = 15; // testing, was 15
	private final static Settings settings = Settings.getReference();
	public volatile boolean navdockactive = false;
	public static int consecutiveroute = 1;
	public static long routemillimeters = 0;
	public static long routestarttime = 0;
	public NavigationLog navlog;
	int batteryskips = 0;
    private String navpstring = null;


    /** Constructor */
	public Navigation(Application a) {
		Ros.loadwaypoints();
		Ros.rospackagedir = Ros.getRosPackageDir(); // required for map saving
		navlog = new NavigationLog();
		state.addObserver(this);
		app = a;
		state.set(values.lidar, true); // or any non null
	}	
	
	@Override
	public void updated(String key) {
		if(key.equals(values.distanceangle.name())){
			try {
				int mm = Integer.parseInt(state.get(values.distanceangle).split(" ")[0]);
				if(mm > 0) routemillimeters += mm;
			} catch (Exception e){}
		}
	}

	public static String getRouteMeters() {
		return Util.formatFloat(routemillimeters / 1000, 0);
	}
	
	public void gotoWaypoint(final String str) {
		if (state.getBoolean(State.values.autodocking)) {
			app.driverCallServer(PlayerCommands.messageclients, "command dropped, autodocking");
			return;
		}

		if (state.get(State.values.dockstatus).equals(AutoDock.UNDOCKED) &&
				!state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString()))  {
			app.driverCallServer(PlayerCommands.messageclients, "Can't navigate, location unknown");
			return;
		}
		
		new Thread(new Runnable() { public void run() {

			if (!waitForNavSystem()) return;
			
			// undock if necessary
			if (!state.get(State.values.dockstatus).equals(AutoDock.UNDOCKED)) {
				undockandlocalize();
			}

			if (!Ros.setWaypointAsGoal(str))
				app.driverCallServer(PlayerCommands.messageclients, "unable to set waypoint");

		
		}  }).start();
	}
	
	private boolean waitForNavSystem() { // blocking

		if (state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.mapping.toString()) ||
				state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.stopping.toString())) {
			app.driverCallServer(PlayerCommands.messageclients, "Navigation.waitForNavSystem(): can't start navigation");
			return false;
		}

		startNavigation();

		long start = System.currentTimeMillis();
		while (!state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString())
				&& System.currentTimeMillis() - start < NAVSTARTTIMEOUT*3) { Util.delay(50);  } // wait

		if (!state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString())) {
			app.driverCallServer(PlayerCommands.messageclients, "Navigation.waitForNavSystem(): navigation start failure");
			return false;
		}

		return true;
	}


	public void startMapping(String str) {
		if (!state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.stopped.toString())) {
			app.driverCallServer(PlayerCommands.messageclients, "Navigation.startMapping(): unable to start mapping, system already running");
			return;
		}

        new Thread(new Runnable() { public void run() {

            if (str.equals("gmapping")) navpstring = Ros.launch(Ros.MAKE_MAP_GMAPPING);
            else navpstring = Ros.launch(Ros.MAKE_MAP);

            app.driverCallServer(PlayerCommands.messageclients, "starting mapping, please wait");
            state.set(State.values.navsystemstatus, Ros.navsystemstate.starting.toString()); // set running by ROS node when ready

        }  }).start();

	}


	public void startNavigation() {
		if (!state.equals(State.values.navsystemstatus, Ros.navsystemstate.stopped)) {
            app.driverCallServer(PlayerCommands.messageclients, "navigation already running");
            return;
        }

		new Thread(new Runnable() { public void run() {
			app.driverCallServer(PlayerCommands.messageclients, "starting navigation, please wait");

            app.driverCallServer(PlayerCommands.cameracommand, Malg.cameramove.horiz.toString());

            // stop any current video
            String currentstream = videoRestartRequiredBlocking();

            // launch remote_nav.launch
            navpstring = Ros.launch(Ros.REMOTE_NAV);

            // launch realsense with RGB and depth
            String vals[] = settings.readSetting(settings.readSetting(GUISettings.vset)).split("_");
            app.video.realsensepstring = Ros.launch(new ArrayList<String>(Arrays.asList(Ros.REALSENSE,
                    "color_width:="+vals[0], "color_height:="+vals[1], "color_fps:="+vals[2],
                    "enable_depth:=true", "initial_reset:=true" )));

            // re-launch stream-to-client if there was current video
            if (currentstream != null)  app.driverCallServer(PlayerCommands.publish, currentstream);

            state.set(State.values.navsystemstatus, Ros.navsystemstate.starting.toString()); // set to 'running' by ROS node when ready

			state.set(values.lidar, lidarstate.enabled.toString());

			// wait
			long start = System.currentTimeMillis();
			while (!state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString())
					&& System.currentTimeMillis() - start < NAVSTARTTIMEOUT) { Util.delay(50);  } // wait

			if (state.equals(State.values.navsystemstatus, Ros.navsystemstate.running)) {
				if (!state.get(State.values.dockstatus).equals(AutoDock.UNDOCKED))
					state.set(State.values.rosinitialpose, "0_0_0");
				Util.log("navigation running", this);
				return; // success
			}

			else  {
				stopNavigation(); // failure
			}

		}  }).start();
	}


	public void stopNavigation() {

        if (state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.stopped.toString()) ||
                state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.stopping.toString()) )
            return;

        if (!state.get(values.navsystemstatus).equals(Ros.navsystemstate.mapping.toString())) {
            Ros.killlaunch(app.video.realsensepstring);
            app.video.realsensepstring = null;
        }

        if (!state.get(values.stream).equals(Application.streamstate.stop.toString())
                && !state.get(values.navsystemstatus).equals(Ros.navsystemstate.mapping.toString()))
            app.driverCallServer(PlayerCommands.publish, Application.streamstate.stop.toString());

        state.set(State.values.navsystemstatus, Ros.navsystemstate.stopped.toString());
        Util.log("stopping navigation", this);
        app.driverCallServer(PlayerCommands.messageclients, "navigation stopped");

        if (navpstring !=null) Ros.killlaunch(navpstring);
        navpstring = null;
        app.video.realsensepstring = null; // TODO: required?

//        Ros.roscommand("rosnode kill /remote_nav");
//        Ros.roscommand("rosnode kill /map_remote");

    }


    // restart stream if necessary
    private String videoRestartRequiredBlocking() {


        if (state.equals(values.stream, Application.streamstate.camera.toString()) ||
                state.equals(values.stream, Application.streamstate.camandmic.toString())) {

            String currentstream = state.get(values.stream);

            app.driverCallServer(PlayerCommands.publish, Application.streamstate.stop.toString());
            Util.delay(Video.STREAM_CONNECT_DELAY);
            app.driverCallServer(PlayerCommands.messageclients, "restarting video");

            return currentstream;
        }

        return null;
    }


	public void dock() {
		if (state.getBoolean(State.values.autodocking)  ) {
			app.driverCallServer(PlayerCommands.messageclients, "autodocking in progress, command dropped");
			return;
		}
		else if (state.get(State.values.dockstatus).equals(AutoDock.DOCKED)) {
			app.driverCallServer(PlayerCommands.messageclients, "already docked, command dropped");
			return;
		}
		else if (!state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString())) {
			return;
		}

		SystemWatchdog.waitForCpu();

		Ros.setWaypointAsGoal(DOCK);
		state.set(State.values.roscurrentgoal, "pending");

		new Thread(new Runnable() { public void run() {

			long start = System.currentTimeMillis();

			// store goal coords
			while (state.get(State.values.roscurrentgoal).equals("pending") && System.currentTimeMillis() - start < 1000) Util.delay(10);
			if (!state.exists(State.values.roscurrentgoal)) return; // avoid null pointer
			String goalcoords = state.get(State.values.roscurrentgoal);

			// wait to reach waypoint
			start = System.currentTimeMillis();
			while (System.currentTimeMillis() - start < WAYPOINTTIMEOUT && state.exists(State.values.roscurrentgoal)) {
				try {
					if (!state.get(State.values.roscurrentgoal).equals(goalcoords))
						return; // waypoint changed while waiting
				} catch (Exception e) {}
				Util.delay(10);
			}

			if ( !state.exists(State.values.rosgoalstatus)) { //this is (harmlessly) thrown normally nav goal cancelled (by driver stop command?)
				Util.log("error, rosgoalstatus null, setting to empty string", this); // TODO: testing
				state.set(State.values.rosgoalstatus, "");
			}

			if (!state.get(State.values.rosgoalstatus).equals(Ros.ROSGOALSTATUS_SUCCEEDED)) {
				app.driverCallServer(PlayerCommands.messageclients, "Navigation.dock() failed to reach dock");
				return;
			}

			navdockactive = true;
			Util.delay(1000);

			// success, should be pointing at dock, shut down nav
			stopNavigation();
            app.driverCallServer(PlayerCommands.dockcam, Settings.ON);

//			Util.delay(Ros.ROSSHUTDOWNDELAY/2); // 5000 too low, massive cpu sometimes here

			Util.delay(5000); // 5000 too low, massive cpu sometimes here

			if (!navdockactive) return;

			SystemWatchdog.waitForCpu();
			app.comport.checkisConnectedBlocking(); // just in case

			//start gyro again
			state.set(values.odometrybroadcast, Malg.ODOMBROADCASTDEFAULT);
			state.set(values.rotatetolerance, Malg.ROTATETOLERANCE);
			app.driverCallServer(PlayerCommands.odometrystart, null);

			app.driverCallServer(PlayerCommands.spotlight, "0");

			// do 180 deg turn
			app.driverCallServer(PlayerCommands.rotate, "180"); // odom controlled
			long timeout = System.currentTimeMillis() + 5000;
			while(!state.get(values.odomrotating).equals(Settings.FALSE) && System.currentTimeMillis() < timeout)
				Util.delay(1);

			Util.debug("navdock: onwards", this);

			if (!navdockactive) return;

			app.driverCallServer(PlayerCommands.autodock, autodockmodes.go.toString());

			// wait while autodocking does its thing
			start = System.currentTimeMillis();
			while (state.getBoolean(State.values.autodocking) &&
					System.currentTimeMillis() - start < SystemWatchdog.AUTODOCKTIMEOUT)
				Util.delay(100);

			if (!navdockactive) return;

			if (!state.get(State.values.dockstatus).equals(AutoDock.DOCKED))
                    Util.log("dock() - unable to dock", this);


		}  }).start();

		navdockactive = false;
	}

	// dock detect, rotate if necessary
	private boolean finddock(String resolution, boolean rotate) {
		int rot = 0;

		while (navdockactive) {
			SystemWatchdog.waitForCpu();

			app.driverCallServer(PlayerCommands.dockgrab, resolution);
			long start = System.currentTimeMillis();
			while (!state.exists(State.values.dockfound.toString()) && System.currentTimeMillis() - start < Util.ONE_MINUTE)
				Util.delay(10);  // wait

			if (state.getBoolean(State.values.dockfound)) break; // great, onwards
			else if (!rotate) return false;
			else { // rotate a bit
				app.comport.checkisConnectedBlocking(); // just in case
				app.driverCallServer(PlayerCommands.right, "25");
				Util.delay(10); // thread safe

				start = System.currentTimeMillis();
				while(!state.get(State.values.direction).equals(Malg.direction.stop.toString())
						&& System.currentTimeMillis() - start < 5000) { Util.delay(10); } // wait
				Util.delay(Malg.TURNING_STOP_DELAY);
			}
			rot ++;

			if (rot == 1) Util.log("error, rotation required", this);

			if (rot == 21) { // failure give up
//					callForHelp(subject, body);
				app.driverCallServer(PlayerCommands.publish, Application.streamstate.stop.toString());
				app.driverCallServer(PlayerCommands.floodlight, "0");
				app.driverCallServer(PlayerCommands.messageclients, "Navigation.finddock() failed to find dock");
				return false;
			}
		}
		if (!navdockactive) return false;
		return true;
	}

	public static void goalCancel() {
		state.set(State.values.rosgoalcancel, true); // pass info to ros node
		state.delete(State.values.roswaypoint);
	}

	public static String routesLoad() {
		String result = "";

		BufferedReader reader;
		try {
			reader = new BufferedReader(new FileReader(navroutesfile));
			String line = "";
			while ((line = reader.readLine()) != null) 	result += line;
			reader.close();

		} catch (FileNotFoundException e) {
			return "<routeslist></routeslist>";
		} catch (IOException e) {
			return "<routeslist></routeslist>";
		}

		return result;
	}

	public void saveRoute(String str) {
		try {
			FileWriter fw = new FileWriter(navroutesfile);
			fw.append(str);
			fw.close();
		} catch (IOException e) {
			Util.printError(e);
		}
	}

	public void runAnyActiveRoute() {
		Document document = Util.loadXMLFromString(routesLoad());
		NodeList routes = document.getDocumentElement().getChildNodes();
		for (int i = 0; i< routes.getLength(); i++) {
			String rname = ((Element) routes.item(i)).getElementsByTagName("rname").item(0).getTextContent();
			String isactive = ((Element) routes.item(i)).getElementsByTagName("active").item(0).getTextContent();
			if (isactive.equals("true")) {
				runRoute(rname);
				Util.log("Auto-starting nav route: "+rname, this);
				break;
			}
		}
	}
	
	/** only used before starting a route, ignored if un-docked */
	public static boolean batteryTooLow() {

		if (state.get(values.batterylife).matches(".*\\d+.*")) {  // make sure batterylife != 'TIMEOUT', throws error
			if ( Integer.parseInt(state.get(State.values.batterylife).replaceAll("[^0-9]", ""))
							< settings.getInteger(ManualSettings.lowbattery) &&
					state.get(State.values.dockstatus).equals(AutoDock.DOCKED))
			{
                app.driverCallServer(PlayerCommands.messageclients, "skipping route, battery too low");
                return true;
			}
		}

        return false;
    }
	
	public void runRoute(final String name) {

		// build error checking into this (ignore duplicate waypoints, etc)
		// assumes goto dock at the end, whether or not dock is a waypoint

		if (state.getBoolean(State.values.autodocking)) {
			app.driverCallServer(PlayerCommands.messageclients, "command dropped, autodocking");
			return;
		}

		if (state.get(State.values.dockstatus).equals(AutoDock.UNDOCKED) &&
				!state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString()))  {
			app.driverCallServer(PlayerCommands.messageclients,
					"Can't start route, location unknown, command dropped");
			cancelAllRoutes();
			return;
		}

		if (state.exists(State.values.navigationroute))  cancelAllRoutes(); // if another route running
		if (state.exists(values.roscurrentgoal))   goalCancel();  // override any active goal

		// check for route name
		Document document = Util.loadXMLFromString(routesLoad());
		NodeList routes = document.getDocumentElement().getChildNodes();
		Element route = null;
		for (int i = 0; i< routes.getLength(); i++) {
    		String rname = ((Element) routes.item(i)).getElementsByTagName("rname").item(0).getTextContent();
			// unflag any currently active routes. New active route gets flagged just below:
			((Element) routes.item(i)).getElementsByTagName("active").item(0).setTextContent("false");
			if (rname.equals(name)) {
    			route = (Element) routes.item(i);
    			break;
    		}
		}

		if (route == null) { // name not found
			app.driverCallServer(PlayerCommands.messageclients, "route: "+name+" not found");
			return;
		}

		// start route
		final Element navroute = route;
		state.set(State.values.navigationroute, name);
		final String id = String.valueOf(System.nanoTime());
		state.set(State.values.navigationrouteid, id);

		// flag route active, save to xml file
		route.getElementsByTagName("active").item(0).setTextContent("true");
		String xmlstring = Util.XMLtoString(document);
		saveRoute(xmlstring);

		app.driverCallServer(PlayerCommands.messageclients, "activating route: " + name);

		new Thread(new Runnable() { public void run() {

			// get schedule info, map days to numbers
			NodeList days = navroute.getElementsByTagName("day");
			if (days.getLength() == 0) {
				app.driverCallServer(PlayerCommands.messageclients, "Can't schedule route, no days specified");
				cancelRoute(id);
				return;
			}
			int[] daynums = new int[days.getLength()];
			String[] availabledays = new String[]{"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
			for (int d=0; d<days.getLength(); d++) {
				for (int ad = 0; ad<availabledays.length; ad++) {
					if (days.item(d).getTextContent().equalsIgnoreCase(availabledays[ad]))   daynums[d]=ad+1;
				}
			}
			// more schedule info
			int starthour = Integer.parseInt(navroute.getElementsByTagName("starthour").item(0).getTextContent());
			int startmin = Integer.parseInt(navroute.getElementsByTagName("startmin").item(0).getTextContent());
			int routedurationhours = Integer.parseInt(navroute.getElementsByTagName("routeduration").item(0).getTextContent());

			// repeat route schedule forever until cancelled
			while (true) {

				state.delete(State.values.nextroutetime);

				// determine next scheduled route time, wait if necessary
				while (state.exists(State.values.navigationroute)) {
					if (!state.get(State.values.navigationrouteid).equals(id)) return;

					// add xml: starthour, startmin, routeduration, day
					Calendar calendarnow = Calendar.getInstance();
					calendarnow.setTime(new Date());
					int daynow = calendarnow.get(Calendar.DAY_OF_WEEK); // 1-7 (friday is 6)

					// parse new xml: starthour, startmin, routeduration (hours), day (1-7)
					// determine if now is within day + range, if not determine time to next range and wait

					boolean startroute = false;
					int nextdayindex = 99;

					for (int i=0; i<daynums.length; i++) {

						// check if need to start run right away
						if (daynums[i] == daynow -1 || daynums[i] == daynow || (daynums[i]==7 && daynow == 1)) { // yesterday or today
							Calendar testday = Calendar.getInstance();
							if (daynums[i] == daynow -1 || (daynums[i]==7 && daynow == 1)) { // yesterday
								testday.set(calendarnow.get(Calendar.YEAR), calendarnow.get(Calendar.MONTH),
										calendarnow.get(Calendar.DATE) - 1, starthour, startmin);
							}
							else { // today
								testday.set(calendarnow.get(Calendar.YEAR), calendarnow.get(Calendar.MONTH),
										calendarnow.get(Calendar.DATE), starthour, startmin);
							}
							if (calendarnow.getTimeInMillis() >= testday.getTimeInMillis() && calendarnow.getTimeInMillis() <
									testday.getTimeInMillis() + (routedurationhours * 60 * 60 * 1000)) {
								startroute = true;
								break;
							}

						}

						if (daynow == daynums[i]) nextdayindex = i;
						else if (daynow > daynums[i]) nextdayindex = i+1;
					}

					if (startroute) break;

					// determine seconds to next route
					if (!state.exists(State.values.nextroutetime)) { // only set once

						int adddays = 0;
						if (nextdayindex >= daynums.length ) { //wrap around
							nextdayindex = 0;
							adddays = 7-daynow + daynums[0];
						}
						else adddays = daynums[nextdayindex] - daynow;

						Calendar testday = Calendar.getInstance();
						testday.set(calendarnow.get(Calendar.YEAR), calendarnow.get(Calendar.MONTH),
								calendarnow.get(Calendar.DATE) + adddays, starthour, startmin);

						if (testday.getTimeInMillis() < System.currentTimeMillis()) { // same day, past route
							nextdayindex ++;
							if (nextdayindex >= daynums.length ) { //wrap around
								adddays = 7-daynow + daynums[0];
							}
							else  adddays = daynums[nextdayindex] - daynow;
							testday.set(calendarnow.get(Calendar.YEAR), calendarnow.get(Calendar.MONTH),
									calendarnow.get(Calendar.DATE) + adddays, starthour, startmin);
						}
						else if (testday.getTimeInMillis() - System.currentTimeMillis() > Util.ONE_DAY*7) //wrap
							testday.setTimeInMillis(testday.getTimeInMillis()-Util.ONE_DAY*7);

						state.set(State.values.nextroutetime, testday.getTimeInMillis());
					}

					Util.delay(1000);
				}

				// check if cancelled while waiting
				if (!state.exists(State.values.navigationroute)) return;
				if (!state.get(State.values.navigationrouteid).equals(id)) return;

				if (state.get(State.values.dockstatus).equals(AutoDock.UNDOCKED) &&
						!state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString())) {
					app.driverCallServer(PlayerCommands.messageclients, "Can't navigate route, location unknown");
					cancelRoute(id);
					return;
				}

				// skip route if battery low (settings.txt)  
				if(batteryTooLow()){			
					batteryskips++;
					Util.log("battery too low: " + state.get(values.batterylife) + " skips: " + batteryskips, this);
					if(batteryskips == 1){	// only log once !
						navlog.newItem(NavigationLog.ALERTSTATUS, "Battery too low to start: " + state.get(values.batterylife), 0, null, name, consecutiveroute, 0);	
					} else {
						if( ! state.get(values.batterylife).contains("_charging")) {
							Util.log("batteryTooLow(): not charging, powerreset: "+ state.get(values.batterylife), "Navigation.runRoute()");
							app.driverCallServer(PlayerCommands.powerreset, null);
						}
					}
					if( ! delayToNextRoute(navroute, name, id)) return; 
					continue;
				} else { batteryskips = 0; }

                //setup realsense cam TODO: change cam/mic/resolution depending on all actions in route
//                app.driverCallServer(PlayerCommands.streamsettingsset, Application.camquality.med.toString()); // reduce crashes
                app.driverCallServer(PlayerCommands.publish, Application.streamstate.camera.toString());
                Util.delay(Video.STREAM_CONNECT_DELAY);

                // start ros nav system
				if (!waitForNavSystem()) {
					// check if cancelled while waiting
					if (!state.exists(State.values.navigationroute)) return;
					if (!state.get(State.values.navigationrouteid).equals(id)) return;

					navlog.newItem(NavigationLog.ERRORSTATUS, "unable to start navigation system", routestarttime, null, name, consecutiveroute, 0);

					if (state.getUpTime() > Util.TEN_MINUTES) {
						app.driverCallServer(PlayerCommands.reboot, null);
						return;
					}

					if (!delayToNextRoute(navroute, name, id)) return;
					continue;
				}

				// check if cancelled while waiting
				if (!state.exists(State.values.navigationroute)) return;
				if (!state.get(State.values.navigationrouteid).equals(id)) return;


				// GO - start route!
				routestarttime = System.currentTimeMillis();
				state.set(State.values.lastusercommand, routestarttime);  // avoid watchdog abandoned
				routemillimeters = 0l;
				
				// undock if necessary
				if (!state.get(State.values.dockstatus).equals(AutoDock.UNDOCKED)) {
					SystemWatchdog.waitForCpu();
					undockandlocalize();
				}

		    	// go to each waypoint
		    	NodeList waypoints = navroute.getElementsByTagName("waypoint");	    	
		    	int wpnum = 0;
		    	while (wpnum < waypoints.getLength()) {

					state.set(State.values.lastusercommand, System.currentTimeMillis()); // avoid watchdog abandoned

					// check if cancelled
			    	if (!state.exists(State.values.navigationroute)) return;
			    	if (!state.get(State.values.navigationrouteid).equals(id)) return;

		    		String wpname = ((Element) waypoints.item(wpnum)).getElementsByTagName("wpname").item(0).getTextContent();
					wpname = wpname.trim();

					app.comport.checkisConnectedBlocking(); // just in case

		    		if (wpname.equals(DOCK))  break;

					SystemWatchdog.waitForCpu();

					if (state.exists(values.roscurrentgoal)) { // current route override TODO: add waypoint name to log msg
						navlog.newItem(NavigationLog.ERRORSTATUS, "current route override prior to set waypoint: "+wpname,
								routestarttime, null, name, consecutiveroute, 0);
						app.driverCallServer(PlayerCommands.messageclients, "current route override prior to set waypoint: "+wpname);
						NavigationUtilities.routeFailed(state.get(values.navigationroute));
						break;
					}

					Util.log("setting waypoint: "+wpname, this);
		    		if (!Ros.setWaypointAsGoal(wpname)) { // can't set waypoint, try the next one
						navlog.newItem(NavigationLog.ERRORSTATUS, "unable to set waypoint", routestarttime,
								wpname, name, consecutiveroute, 0);
						app.driverCallServer(PlayerCommands.messageclients, "route "+name+" unable to set waypoint");
						wpnum ++;
						continue;
		    		}
	
		    		state.set(State.values.roscurrentgoal, "pending");
		    		
		    		// wait to reach wayypoint
					long start = System.currentTimeMillis();
					boolean oktocontinue = true;
					while (state.exists(State.values.roscurrentgoal) && System.currentTimeMillis() - start < WAYPOINTTIMEOUT) {
						Util.delay(10);
						if (!state.equals(values.roswaypoint, wpname)) { // current route override
							navlog.newItem(NavigationLog.ERRORSTATUS, "current route override on way to waypoint: "+wpname,
									routestarttime, wpname, name, consecutiveroute, 0);
							app.driverCallServer(PlayerCommands.messageclients, "current route override on way to waypoint: "+wpname);
							oktocontinue = false;
							break;
						}
					}
					if (!oktocontinue) break;
					
					if (!state.exists(State.values.navigationroute)) return;
			    	if (!state.get(State.values.navigationrouteid).equals(id)) return;

					if (!state.exists(State.values.rosgoalstatus)) { // this is (harmlessly) thrown normally nav goal cancelled (by driver stop command?)
						Util.log("error, state rosgoalstatus null", this);
						state.set(State.values.rosgoalstatus, "error");
					}
					
					// failed, try next waypoint
					if (!state.get(State.values.rosgoalstatus).equals(Ros.ROSGOALSTATUS_SUCCEEDED)) {
						navlog.newItem(NavigationLog.ERRORSTATUS, "Failed to reach waypoint: "+wpname,
								routestarttime, wpname, name, consecutiveroute, 0);
						app.driverCallServer(PlayerCommands.messageclients, "route "+name+" failed to reach waypoint");
						wpnum ++;
						continue; 
					}

					// waypoint reached
					// send actions and duration delay to processRouteActions()
					NodeList actions = ((Element) waypoints.item(wpnum)).getElementsByTagName("action");
					long duration = Long.parseLong(
						((Element) waypoints.item(wpnum)).getElementsByTagName("duration").item(0).getTextContent());
					if (duration > 0)  processWayPointActions(actions, duration * 1000, wpname, name, id);
					wpnum ++;
				}
		    	
		    	if (!state.exists(State.values.navigationroute)) return;
		    	if (!state.get(State.values.navigationrouteid).equals(id)) return;

				if (!state.exists(values.roscurrentgoal)) { // current route override check

					dock();

					// wait while autodocking does its thing
					final long start = System.currentTimeMillis();
					while (System.currentTimeMillis() - start < SystemWatchdog.AUTODOCKTIMEOUT + WAYPOINTTIMEOUT) {
						if (!state.exists(State.values.navigationroute)) return;
						if (!state.get(State.values.navigationrouteid).equals(id)) return;
						if (state.get(State.values.dockstatus).equals(AutoDock.DOCKED) && !state.getBoolean(State.values.autodocking))
							break;
						Util.delay(100); // success
					}

					if (!state.get(State.values.dockstatus).equals(AutoDock.DOCKED)) {

						navlog.newItem(NavigationLog.ERRORSTATUS, "Unable to dock", routestarttime, null, name, consecutiveroute, 0);

						// cancelRoute(id);
						// try docking one more time, sending alert if fail
						Util.log("calling redock()", this);
						stopNavigation();
						Util.delay(Ros.ROSSHUTDOWNDELAY / 2); // 5000 too low, massive cpu sometimes here
						app.driverCallServer(PlayerCommands.redock, SystemWatchdog.NOFORWARD);

						if (!delayToNextRoute(navroute, name, id)) return;
						continue;
					}

					navlog.newItem(NavigationLog.COMPLETEDSTATUS, null, routestarttime, null, name, consecutiveroute, routemillimeters);

					// how long did docking take
					int timetodock = 0; // (int) ((System.currentTimeMillis() - start)/ 1000);
					// subtract from routes time
					int routetime = (int)(System.currentTimeMillis() - routestarttime)/1000 - timetodock;
					NavigationUtilities.routeCompleted(name, routetime, (int)routemillimeters/1000);

					consecutiveroute++;
					routemillimeters = 0;

				}

				if (!delayToNextRoute(navroute, name, id)) return;
			}
		}  }).start();
	}

	private void undockandlocalize() { // blocking
		state.set(State.values.motionenabled, true);
		double distance = settings.getDouble(ManualSettings.undockdistance);
		app.driverCallServer(PlayerCommands.forward, String.valueOf(distance));
		Util.delay((long) (distance / state.getDouble(values.odomlinearmpms.toString()))); // required for fast systems?!
		long start = System.currentTimeMillis();
		while(!state.get(values.direction).equals(Malg.direction.stop.toString())
				&& System.currentTimeMillis() - start < 10000) { Util.delay(10); } // wait
	}

	private boolean delayToNextRoute(Element navroute, String name, String id) {
		// delay to next route

		String msg = " min until next route: "+name+", run #"+consecutiveroute;
		if (consecutiveroute > RESTARTAFTERCONSECUTIVEROUTES) {
			msg = " min until reboot, max consecutive routes: "+RESTARTAFTERCONSECUTIVEROUTES+ " reached";
		}

		String min = navroute.getElementsByTagName("minbetween").item(0).getTextContent();
		long timebetween = Long.parseLong(min) * 1000 * 60;
		state.set(State.values.nextroutetime, System.currentTimeMillis()+timebetween);
		app.driverCallServer(PlayerCommands.messageclients, min +  msg);
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < timebetween) {
			if (!state.exists(State.values.navigationroute)) {
				state.delete(State.values.nextroutetime);
				return false;
			}
			if (!state.get(State.values.navigationrouteid).equals(id)) {
				state.delete(State.values.nextroutetime);
				return false;
			}
			Util.delay(1000);
		}

		if (consecutiveroute > RESTARTAFTERCONSECUTIVEROUTES &&
				state.getUpTime() > Util.TEN_MINUTES)  { // prevent runaway reboots
			Util.log("rebooting, max consecutive routes reached", this);
			app.driverCallServer(PlayerCommands.reboot, null);
			return false;
		}
		return true;
	}

	/**
	 * process actions for single waypoint 
	 * 
	 * @param actions
	 * @param duration
	 */
	private void processWayPointActions(NodeList actions, long duration, String wpname, String name, String id) {
		
		// TODO: actions here
		//  <action>  
		// var navrouteavailableactions = ["rotate", "email", "rss", "motion", "sound", "human", "not detect" ];
		/*
		 * rotate only works with motion & human (ie., camera) ignore otherwise
		 *     -rotate ~30 deg increments, fixed duration. start-stop
		 *     -minimum full rotation, or more if <duration> allows 
		 * <duration> -- cancel all actions and move to next waypoint (let actions complete)
		 * alerts: rss or email: send on detection (or not) from "motion", "human", "sound"
		 *      -once only from single waypoint, max 2 per route (on 1st detection, then summary at end)
		 * if no alerts, log only
		 */
    	// takes 5-10 seconds to init if mic is on (mic only, or mic + camera)
		
		state.set(values.waypointbusy, "true");
		Util.debug("processWayPointActions wp:" +wpname, this);

		boolean rotate = false;
		boolean email = false;
		boolean rss = false;
		boolean motion = false;
		boolean notdetect = false;
		boolean sound = false;
		boolean human = false;
		boolean photo = false;
		boolean record = false;
		
		boolean camera = false;
		boolean mic = false;
		String notdetectedaction = "";


    	for (int i=0; i< actions.getLength(); i++) {
    		String action = ((Element) actions.item(i)).getTextContent();
    		switch (action) {
				case "rotate": rotate = true; break;
				case "email": email = true; break;
				case "rss": rss = true; break;
				case "motion":
					motion = true;
					camera = true;
					notdetectedaction = action;
					break;
				case "not detect":
					notdetect = true;
					break;
				case "sound":
					sound = true;
					mic = true;
					notdetectedaction = action;
					break;
				case "human":
					human = true;
					camera = true;
					notdetectedaction = action;
					break;
				case "photo":
					photo = true;
					camera = true;
					break;

				case "record video":
					record = true;
					camera = true;
					mic = true;
					break;
			}
    	}

		// if no camera, what's the point in rotating TODO: disallow this with javascript instead
    	if (!camera && rotate) {
			rotate = false;
			app.driverCallServer(PlayerCommands.messageclients, "rotate action ignored, camera unused");
		}

    	long startupdelay = 0;

		// setup camera position
		if (camera) {
			app.driverCallServer(PlayerCommands.camtilt, String.valueOf(Malg.CAM_HORIZ + Malg.CAM_NUDGE * 4 ));
			startupdelay = 2000;
        }

		// turn lidar off if necessary
		if (mic || duration > Util.ONE_MINUTE) {
			if (state.exists(values.lidar)) {
				state.set(values.lidar, lidarstate.disabled.toString());
				startupdelay = 3000;
			}
		}

		Util.delay(startupdelay);

		String recordlink = null;
		if (record)  recordlink = app.video.record(Settings.TRUE); // start recording

		long waypointstart = System.currentTimeMillis();
		long delay = 10000;
 		if (duration < delay) duration = delay;
		int turns = 0;
		int maxturns = 8;
		if (!rotate) {
			delay = duration;
			turns = maxturns;
		}

		// remain at waypoint rotating and/or waiting, detection running if enabled
		while (System.currentTimeMillis() - waypointstart < duration || turns < maxturns) {

			if (!state.exists(State.values.navigationroute)) break;
	    	if (!state.get(State.values.navigationrouteid).equals(id)) break;

			state.delete(State.values.streamactivity);

			// enable sound detection
			if (sound) {
			    app.driverCallServer(PlayerCommands.sounddetect, Settings.TRUE);
			}

			// lights on if needed
			if (camera) {
				if (turnLightOnIfDark()) {
				    Util.delay(3000); // allow cam to adjust
                    waypointstart += 3000;
                }
			}

			// enable human or motion detection
			if (human)
				app.driverCallServer(PlayerCommands.objectdetect, OpenCVObjectDetect.HUMAN);
			else if (motion)
				app.driverCallServer(PlayerCommands.motiondetect, null);

			// mic takes a while to start up
//			if (sound && !lightondelay) Util.delay(2000);

			// ALL SENSES ENABLED, NOW WAIT
			long start = System.currentTimeMillis();
			while (!state.exists(State.values.streamactivity) && System.currentTimeMillis() - start < delay
				&& state.get(State.values.navigationrouteid).equals(id) && state.exists(values.navigationroute))
					{ Util.delay(10); }

			// if cancelled while waiting
			if (!state.exists(State.values.navigationroute)) break;
			if (!state.get(State.values.navigationrouteid).equals(id)) break;

			// PHOTO
			if (photo) {
				String link = FrameGrabHTTP.saveToFile(null);

				Util.delay(2000); // allow time for framgrabusy flag to be set true
				long timeout = System.currentTimeMillis() + 10000;
				while (state.getBoolean(values.framegrabbusy) && System.currentTimeMillis() < timeout) Util.delay(10);
				Util.delay(3000); // allow time to download

				String navlogmsg = "<a href='" + link + "' target='_blank'>Photo</a>";
				String msg = "[Autocrawler Photo] ";
				msg += navlogmsg+", time: "+
						Util.getTime()+", at waypoint: " + wpname + ", route: " + name;

				if (email) {
					String emailto = settings.readSetting(GUISettings.email_to_address);
					if (!emailto.equals(Settings.DISABLED)) {
						app.driverCallServer(PlayerCommands.email, emailto + " " + msg);
						navlogmsg += "<br> email sent ";
					}
				}
				if (rss) {
					app.driverCallServer(PlayerCommands.rssadd, msg);
					navlogmsg += "<br> new RSS item ";
				}

				navlog.newItem(NavigationLog.PHOTOSTATUS, navlogmsg, routestarttime, wpname,
						state.get(State.values.navigationroute), consecutiveroute, 0);
			}

			// ALERT
			if (state.exists(State.values.streamactivity) && ! notdetect) {

				String streamactivity =  state.get(State.values.streamactivity);
				String msg = "Detected: "+streamactivity+", time: "+
						Util.getTime()+", at waypoint: " + wpname + ", route: " + name;
				Util.log(msg + " " + streamactivity, this);

				String navlogmsg = "Detected: ";

				if (streamactivity.contains("video")) {
					navlogmsg += "motion";
				}
				else if (streamactivity.contains("audio")) {
					navlogmsg += "sound";
				}
				else navlogmsg += streamactivity;


				String link = "";
				if (streamactivity.contains("video") || streamactivity.contains(OpenCVObjectDetect.HUMAN)) {
					link = FrameGrabHTTP.saveToFile("?mode=processedImgJPG");
					navlogmsg += "<br><a href='" + link + "' target='_blank'>image link</a>";
				}

				if (email || rss) {

					if (streamactivity.contains(OpenCVObjectDetect.HUMAN)) {
						msg = "[Autocrawler Detected "+streamactivity+"] " + msg;
						msg += "\nimage link: " + link + "\n";
						Util.delay(3000); // allow time for download thread to capture image before turning off camera
					}
					if (streamactivity.contains("video")) {
						msg = "[Autocrawler Detected Motion] " + msg;
						msg += "\nimage link: " + link + "\n";
						Util.delay(3000); // allow time for download thread to capture image before turning off camera
					}
					else if (streamactivity.contains("audio")) {
						msg = "[Autocrawler Sound Detection] Sound " + msg;
					}

					if (email) {
						String emailto = settings.readSetting(GUISettings.email_to_address);
						if (!emailto.equals(Settings.DISABLED)) {
							app.driverCallServer(PlayerCommands.email, emailto + " " + msg);
							navlogmsg += "<br> email sent ";
						}
					}
					if (rss) {
						app.driverCallServer(PlayerCommands.rssadd, msg);
						navlogmsg += "<br> new RSS item ";
					}
				}

				navlog.newItem(NavigationLog.ALERTSTATUS, navlogmsg, routestarttime, wpname,
						state.get(State.values.navigationroute), consecutiveroute, 0);

				// shut down sensing
				if (state.exists(State.values.motiondetect))
					app.driverCallServer(PlayerCommands.motiondetectcancel, null);
				if (state.exists(State.values.objectdetect))
					app.driverCallServer(PlayerCommands.objectdetectcancel, null);
				if (sound)
				    state.set(values.sounddetect, false);
				if (camera)
				    state.delete(State.values.writingframegrabs); // saves cpu, especially high res

                break; // go to next waypoint, stop if rotating
			}

			// nothing detected, shut down sensing
			if (state.exists(State.values.motiondetect))
				app.driverCallServer(PlayerCommands.motiondetectcancel, null);
			if (state.exists(State.values.objectdetect))
				app.driverCallServer(PlayerCommands.objectdetectcancel, null);
			if (sound)
                state.set(values.sounddetect, false);

			// ALERT if not detect
			if (notdetect) {
				String navlogmsg = "NOT Detected: "+notdetectedaction;
				String msg = "";

				if (email || rss) {
					msg = "[Autocrawler: "+notdetectedaction+" NOT detected] ";
					msg += "At waypoint: " + wpname + ", route: " + name + ", time: "+Util.getTime();
				}

				if (email) {
					String emailto = settings.readSetting(GUISettings.email_to_address);
					if (!emailto.equals(Settings.DISABLED)) {
						app.driverCallServer(PlayerCommands.email, emailto + " " + msg);
						navlogmsg += "<br> email sent ";
					}
				}
				if (rss) {
					app.driverCallServer(PlayerCommands.rssadd, msg);
					navlogmsg += "<br> new RSS item ";
				}

				navlog.newItem(NavigationLog.ALERTSTATUS, navlogmsg, routestarttime, wpname,
						state.get(State.values.navigationroute), consecutiveroute, 0);
			}


			if (rotate) {

				Util.delay(2000);
				SystemWatchdog.waitForCpu(8000); // lots of missed stop commands, cpu timeouts here

                app.driverCallServer(PlayerCommands.rotate, "45");
                Util.delay(100); // allow state value set
                state.block(State.values.direction, Malg.direction.stop.toString(), 10000);

				Util.delay(4000);
				
				turns ++;
			}

		}

		// END RECORD
		if (record && recordlink != null) {

			String navlogmsg = "<a href='" + recordlink + "_video.flv' target='_blank'>Video</a>";
			if (!settings.getBoolean(ManualSettings.useflash))
				navlogmsg += "<br><a href='" + recordlink + "_audio.flv' target='_blank'>Audio</a>";
			String msg = "[Autocrawler Video] ";
			msg += navlogmsg+", time: "+
					Util.getTime()+", at waypoint: " + wpname + ", route: " + name;

			if (email) {
				String emailto = settings.readSetting(GUISettings.email_to_address);
				if (!emailto.equals(Settings.DISABLED)) {
					app.driverCallServer(PlayerCommands.email, emailto + " " + msg);
					navlogmsg += "<br> email sent ";
				}
			}
			if (rss) {
				app.driverCallServer(PlayerCommands.rssadd, msg);
				navlogmsg += "<br> new RSS item ";
			}
			navlog.newItem(NavigationLog.VIDEOSTATUS, navlogmsg, routestarttime, wpname,
					state.get(State.values.navigationroute), consecutiveroute, 0);
			app.video.record(Settings.FALSE); // stop recording
		}

        // turn lidar back on if necessary
		if (state.exists(values.lidar)) {
		    if (state.get(values.lidar).equals(lidarstate.disabled.toString())) {
                state.set(values.lidar, lidarstate.enabled.toString());
                Util.delay(3000);
                if (!camera) Util.delay(2000);
            }
        }

        if (camera) {
            app.driverCallServer(PlayerCommands.cameracommand, Malg.cameramove.horiz.toString());
            Util.delay(2000);
        }

        if (state.getInteger(values.spotlightbrightness) != 0)
            app.driverCallServer(PlayerCommands.spotlight, "0");

        state.set(values.waypointbusy, Settings.FALSE);

	}

	public static boolean turnLightOnIfDark() {

		if (state.getInteger(values.spotlightbrightness) == 100) return false; // already on

		state.delete(State.values.lightlevel);
		app.driverCallServer(PlayerCommands.getlightlevel, null);
		long timeout = System.currentTimeMillis() + 5000;
		while (!state.exists(State.values.lightlevel) && System.currentTimeMillis() < timeout)
			Util.delay(10);

		if (state.exists(State.values.lightlevel)) {
			if (state.getInteger(State.values.lightlevel) < 25) {
				app.driverCallServer(PlayerCommands.spotlight, "100"); // light on
				return true;
			}
		}

		return false;
	}

	/**
	 * cancel all routes, only if id matches state
	 * @param id
	 */
	private void cancelRoute(String id) {
		if (id.equals(state.get(State.values.navigationrouteid))) cancelAllRoutes();
	}

	public void cancelAllRoutes() {
		state.delete(State.values.navigationroute); // this eventually stops currently running route
		goalCancel();
		state.delete(State.values.nextroutetime);

		Document document = Util.loadXMLFromString(routesLoad());
		NodeList routes = document.getDocumentElement().getChildNodes();

		// set all routes inactive
		for (int i = 0; i< routes.getLength(); i++) {
			((Element) routes.item(i)).getElementsByTagName("active").item(0).setTextContent("false");
		}

		String xmlString = Util.XMLtoString(document);
		saveRoute(xmlString);
		batteryskips = 0;
		app.driverCallServer(PlayerCommands.messageclients, "all routes cancelled");
	}

	public void saveMap() {
		if (!state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.mapping.toString())) {
			app.message("unable to save map, mapping not running", null, null);
			return;
		}
		new Thread(new Runnable() { public void run() {
			if (Ros.saveMap())  app.message("map saved to "+Ros.getMapFilePath(), null, null);
		}  }).start();

	}


}
