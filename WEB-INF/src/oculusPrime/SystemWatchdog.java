package oculusPrime;

import developer.Navigation;
import developer.Ros;
import oculusPrime.AutoDock.autodockmodes;
import oculusPrime.State.values;
import oculusPrime.commport.ArduinoPower;
import oculusPrime.commport.ArduinoPrime;
import oculusPrime.commport.PowerLogger;

import java.io.File;
import java.util.Calendar;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

public class SystemWatchdog {
	
	private final Settings settings = Settings.getReference();
	protected Application application = null;

	static final String AP = "oculusprime";

	private static final long DELAY = 10000; // 10 sec 
	public static final long AUTODOCKTIMEOUT= 5*Util.ONE_MINUTE;
	private static final long ABANDONDEDLOGIN= 30*Util.ONE_MINUTE; 
	public static final String NOFORWARD = "noforward";

	// stale system reboot frequency 
 	private static final long STALE = Util.ONE_DAY * 2; 
	
	private State state = State.getReference();
	
	public String lastpowererrornotify = null; // this gets set to null on client login 
	public boolean powererrorwarningonly = true;
	public boolean redocking = false;
	private boolean lowbattredock = false;
	private long lowbattredockstart = 0;
    private static final File TIMEDSHUTDOWNFILE = new File(Settings.redhome + Util.sep + "timedshutdown");

	SystemWatchdog(Application a){ 
		application = a;
		new Timer().scheduleAtFixedRate(new Task(), DELAY, DELAY);
	}

	private class Task extends TimerTask {
		public void run() {

			// regular reboot if set 
			if (System.currentTimeMillis() - state.getLong(values.linuxboot) > STALE
					&& !state.exists(State.values.driver.toString()) &&
					!state.exists(State.values.powererror.toString()) &&
					state.get(State.values.dockstatus).equals(AutoDock.DOCKED) &&
					state.getInteger(State.values.telnetusers) == 0 &&
					state.getUpTime() > Util.TEN_MINUTES && // prevent runaway reboots
					(settings.getBoolean(GUISettings.reboot))){
				
				// String boot = new Date(state.getLong(State.values.javastartup.name())).toString();				
				Util.log("regular reboot", this);
				application.driverCallServer(PlayerCommands.reboot, null);
			}
			
			// show AP mode enabled, if no driver
			if(state.equals(values.ssid, AP)){ 
				if( ! state.getBoolean(State.values.autodocking) && ! state.exists(State.values.driver)) {
					application.driverCallServer(PlayerCommands.strobeflash, "on 10 10");
				}
			}

			// notify clients of power errors
			if (state.exists(State.values.powererror.toString())) {
				if (lastpowererrornotify == null) notifyPowerError();
				else if ( ! lastpowererrornotify.equals(state.get(State.values.powererror))) notifyPowerError();
			}

			// safety: check for force_undock command from battery firmware
			if (state.getBoolean(State.values.forceundock) && state.equals(values.dockstatus, AutoDock.DOCKED)){ 
				Util.log("System WatchDog, force undock", this);
				PowerLogger.append("System WatchDog, force undock", this);
				forceundock();
			}

			// deal with abandoned logins, driver still connected
			if (state.exists(State.values.driver.toString()) && 
					System.currentTimeMillis() - state.getLong(State.values.lastusercommand) > ABANDONDEDLOGIN ) {

				application.driverCallServer(PlayerCommands.disconnectotherconnections, null);
				application.driverCallServer(PlayerCommands.driverexit, null);
				if (state.get(State.values.dockstatus).equals(AutoDock.UNDOCKED) && 
						settings.getBoolean(GUISettings.redock)) {
					Util.log("abandoned logins, driver still connected, attempt redock", this);
					PowerLogger.append("abandoned logins, driver still connected, attempt redock", this);
					redock(NOFORWARD);
				}
			}
			
			// deal with abandonded, undocked, low battery, not redocking, not already attempted redock
			if (state.get(values.batterylife).matches(".*\\d+.*")) {  // make sure batterylife != 'TIMEOUT', throws error
				if (!state.exists(State.values.driver) &&
						System.currentTimeMillis() - state.getLong(State.values.lastusercommand) > ABANDONDEDLOGIN &&
						redocking == false &&
						Integer.parseInt(state.get(State.values.batterylife).replaceAll("[^0-9]", ""))
								< settings.getInteger(ManualSettings.lowbattery) &&
						state.get(State.values.dockstatus).equals(AutoDock.UNDOCKED) &&
						settings.getBoolean(GUISettings.redock)
						) {
					if (!lowbattredock) {
						lowbattredock = true;
						lowbattredockstart = System.currentTimeMillis();
						Util.log("abandonded, undocked, low battery, not redocking", this);
						PowerLogger.append("abandonded, undocked, low battery, not redocking", this);
						redock(NOFORWARD);
					} else { // power down if redock failed, helps with battery death by parasitics
						if (System.currentTimeMillis() - lowbattredockstart > AUTODOCKTIMEOUT) {
							Util.log("abandonded, undocked, low battery, redock failed", this);
                            if (settings.getBoolean(ManualSettings.timedshutdown) &&
									application.powerport.boardid.equals(ArduinoPower.FIRMWARE_IDV2))
								timedShutdown();
							else
								application.driverCallServer(PlayerCommands.powershutdown, null);
						}
					}
				} else if (state.get(values.dockstatus).equals(AutoDock.DOCKED))
					lowbattredock = false;
			}

			// if waking up after above check forced timed shutdown
            if (TIMEDSHUTDOWNFILE.exists() && !lowbattredock) {
                TIMEDSHUTDOWNFILE.delete();
                Util.log("wake up after timed shutdown", this);

                // force check: abandonded, undocked, low battery, not redocking, not already attempted redock
                // assume not to bother trying redock again
                // should shutdown again after AUTODOCKTIMEOUT ms
                if ( !state.exists(State.values.driver.toString()) ) {
//                    state.set(values.lastusercommand, String.valueOf(state.getLong(values.lastusercommand) - ABANDONDEDLOGIN));
					state.set(values.lastusercommand, 0);
					lowbattredock = true;
                    lowbattredockstart = System.currentTimeMillis();
                }
            }

			// navigation running, route running, undocked, low battery, next waypoint != dock, no driver, drive to dock
			if (state.get(values.batterylife).matches(".*\\d+.*")) {  // make sure batterylife != 'TIMEOUT', throws error
				if (!state.exists(State.values.driver) &&
						Integer.parseInt(state.get(State.values.batterylife).replaceAll("[^0-9]", ""))
								< settings.getInteger(ManualSettings.lowbattery) &&
						state.get(State.values.dockstatus).equals(AutoDock.UNDOCKED) &&
						settings.getBoolean(GUISettings.redock) &&
						state.equals(State.values.navsystemstatus, Ros.navsystemstate.running) &&
						state.exists(State.values.navigationroute) &&
						!state.equals(State.values.roswaypoint, Navigation.DOCK)
						)
				{
					Util.log("route running, undocked, low battery, no driver", this);
					PowerLogger.append("route running, undocked, low battery, no driver", this);
					application.driverCallServer(PlayerCommands.gotodock, null);
				}
			}

//			 check cpu useage
			int cpuNow = Util.getCPU();
			if(cpuNow > 60) Util.debug("cpu: "+cpuNow, this);
			 
			// notify driver if any system messages
			if (state.exists(values.guinotify)) {
				if (state.exists(State.values.driver.toString())) {
//					String str="<span style='font-size: 12px'>("+Util.getDateStamp()+")</span><br><br>";
					String str = state.get(values.guinotify);
					str += "<br><br><a href='javascript: guinotify(&quot;ok&quot;);'>";
					str += "<span class='cancelbox'>&#x2714;</span> OK</a> &nbsp; &nbsp; ";
					application.sendplayerfunction("guinotify", str );
				}
			}
		}
	}
	
	private void notifyPowerError() {
		PowerLogger.append("notifyPowerError()", this);
		lastpowererrornotify = state.get(State.values.powererror);
		boolean warningonly = true;
		boolean resetrequired = false;
		String longerror = "";
		boolean commlost = false;
		String code[] = lastpowererrornotify.split(",");
		for (int i=0; i < code.length; i++) {
			int c = Integer.parseInt(code[i]);
			if (c > ArduinoPower.WARNING_ONLY_BELOW ) { 
				warningonly = false;
				longerror += "<span style='color: red'>";
			}
			if (c != 0) longerror += ArduinoPower.pwrerr.get(c).replaceFirst("ERROR_", "") + "<br>";
			if (!warningonly) longerror += "</span>";
			if (c > ArduinoPower.RESET_REQUIRED_ABOVE && c != ArduinoPower.COMM_LOST) resetrequired = true;
			if (c == ArduinoPower.COMM_LOST) commlost = true;
			if (c > ArduinoPower.FORCE_UNDOCK_ABOVE) state.set(State.values.forceundock, true);
		}

		// cancel any navigation routes (TODO: and other autonomous functions ??)
		if (!warningonly) application.driverCallServer(PlayerCommands.cancelroute, null);
		
		if (state.exists(State.values.driver.toString())) {
			String msg = "";
			if (warningonly && !commlost) msg += "POWER WARNING<br>History:<br><br>";
			else msg += "POWER SYSTEM ERROR<br>History:<br><br>";
			
			msg += longerror + "<br>";
			
			if (warningonly && !commlost) msg += "OK to clear warnings?";
			else if (warningonly && commlost) msg += "Try: restart application, reboot, check USB cable"; // commlost
			else msg += "Please UNPLUG BATTERY and consult technical support";

			if (resetrequired)
				msg += "<br><br>Charging is limited or disabled";

			if (warningonly && resetrequired)
				msg += "<br>until this message is cleared";

			msg += "<br><br>";

			msg += "<a href='javascript: acknowledgeerror(&quot;true&quot;);'>";
		    msg += "<span class='cancelbox'>&#x2714;</span> OK</a> &nbsp; &nbsp; ";

			if (warningonly) { powererrorwarningonly = true;
			    msg += "<a href='javascript: acknowledgeerror(&quot;cancel&quot;);'>";
			    msg += "<span class='cancelbox'><b>X</b></span> IGNORE</a><br>";
			}
			else powererrorwarningonly = false;
			
			application.sendplayerfunction("acknowledgeerror", msg);
		}
		else if (!warningonly) callForHelp("Oculus Prime POWER ERROR","Please UNPLUG BATTERY and consult technical support");
		else if (warningonly && commlost) callForHelp("Oculus Prime POWER ERROR","Power PCB Communication Lost");
	}
	
	public void redock(String str) {
		if (redocking) return;

		if (str == null) str = "";

		// TODO: force nav shutdown?
		if (settings.getBoolean(GUISettings.navigation) && !str.equals(NOFORWARD) ) {
			if ( !state.get(values.navsystemstatus).equals(Ros.navsystemstate.stopping.toString()) &&
					!state.get(values.navsystemstatus).equals(Ros.navsystemstate.stopped.toString())) {
				Util.log("warning: redock skipped, navigation running", this);
				return;
			}
			if (state.exists(values.nextroutetime)) {
				if (state.getLong(values.nextroutetime.toString()) - System.currentTimeMillis() < Util.TWO_MINUTES) {
					Util.log("warning: redock skipped, route starting soon", this);
					return;
				}
			}
		}

		Util.log("SystemWatchdog.redock()", this);
        PowerLogger.append("SystemWatchdog.redock()", this);
		
		final String option = str;
		new Thread(new Runnable() { public void run() {
			redocking = true;
			long start;
			String subject = "Oculus Prime Unable to Dock";
			String body = "Un-docked, battery draining";
			
			// warn
//			application.driverCallServer(PlayerCommands.speech, "warning, moving, in, 5, seconds");
			application.driverCallServer(PlayerCommands.strobeflash, "on 500 50");
			Util.delay(1000); // including 500 delay 
			application.driverCallServer(PlayerCommands.strobeflash, "on 500 50");
			Util.delay(1000); // including 500 delay 
			application.driverCallServer(PlayerCommands.strobeflash, "on 500 50");
			Util.delay(6000); // allow reaction

			if (!redocking) return;

			// dock camera on
			application.driverCallServer(PlayerCommands.dockcam, Settings.ON);
			state.block(State.values.dockcamready, Settings.TRUE, 15000);

			state.set(State.values.motionenabled, true);
			application.driverCallServer(PlayerCommands.odometrystart, null);

			// go backward momentarily
			double distance = 0.3;
			application.driverCallServer(PlayerCommands.backward, String.valueOf(distance));
			Util.delay((long) (distance / state.getDouble(State.values.odomlinearmpms.toString())));
			state.block(State.values.direction, ArduinoPrime.direction.stop.toString(), 10000);
			Util.delay(AutoDock.DOCKGRABDELAY);

			if (!redocking) return;

			application.driverCallServer(PlayerCommands.autodock, autodockmodes.go.toString()); // attempt dock
			// wait while autodocking does its thing 
			start = System.currentTimeMillis();
			while (state.getBoolean(State.values.autodocking) && System.currentTimeMillis() - start < AUTODOCKTIMEOUT)  
				Util.delay(100);

			if (!redocking) return;

			if (!state.get(State.values.dockstatus).equals(AutoDock.DOCKED)) {
				if (!state.exists(State.values.driver.toString())) {
					if (state.exists(values.navigationroute)) application.driverCallServer(PlayerCommands.cancelroute, null);
					callForHelp(subject, body);
				}
			}

			redocking = false;
		}  }).start();

	}
	
	private void callForHelp(String subject, String body) {
        application.driverCallServer(PlayerCommands.messageclients, body);

        if (!settings.getBoolean(ManualSettings.alertsenabled)) return;

		Util.log("callForHelp() " + subject + " " + body, this);
		PowerLogger.append("callForHelp() " + subject + " " + body, this);

		body += "\nhttp://"+state.get(State.values.externaladdress)+":"+
				settings.readRed5Setting("http.port")+"/oculusPrime/";
		String emailto = settings.readSetting(GUISettings.email_to_address);
		if (!emailto.equals(Settings.DISABLED))
			application.driverCallServer(PlayerCommands.email, emailto+" ["+subject+"] "+body);
		application.driverCallServer(PlayerCommands.rssadd, "[" + subject + "] " + body);
	}
	
	private void forceundock() {
		application.driverCallServer(PlayerCommands.messageclients, "Power ERROR, Forced Un-Dock");
		// go forward momentarily
		application.driverCallServer(PlayerCommands.speed, ArduinoPrime.speeds.med.toString());
		state.set(State.values.motionenabled, true);
//		state.set(State.values.controlsinverted, false);
		application.driverCallServer(PlayerCommands.move, ArduinoPrime.direction.forward.toString());
		Util.delay(800);
		application.driverCallServer(PlayerCommands.move, ArduinoPrime.direction.stop.toString());
		 
//		String subject = "Oculus Prime Power ERROR, Forced Un-Dock";
//		String body = "Oculus Prime Power ERROR, Forced Un-Dock";
//		callForHelp(subject, body);
	}

	public void waitForCpuThread() {
		new Thread(new Runnable() { public void run() {
			waitForCpu();
		}  }).start();
	}

	public static void waitForCpu() { waitForCpu(60, 20000); }

	public static void waitForCpu(long timeout) { waitForCpu(60, timeout); }

	public static void waitForCpu(int threshold, long timeout) {
		State state = State.getReference();
		if (state.getBoolean(values.waitingforcpu)) return;
		state.set(values.waitingforcpu, true);
		long start = System.currentTimeMillis();
		int cpu = 0;
		while (System.currentTimeMillis() - start < timeout) {
			cpu = Util.getCPU();
			if (cpu < threshold) { // do it again to be sure
				cpu = Util.getCPU();
				if (cpu <threshold) {
					Util.debug("SystemWatchdog.waitForCpu() cleared, cpu @ " + cpu + "% after " + (System.currentTimeMillis() - start) + "ms", null);
					state.set(values.waitingforcpu, false);
					return;
				}
			}
			Util.delay(1000);
		}
		state.set(values.waitingforcpu, false);
		Util.log("SystemWatchdog.waitForCpu() warning, timed out " + cpu + "% after " + timeout + "ms", null);
	}

	private void timedShutdown() {

		Calendar calendarnow = Calendar.getInstance();
		calendarnow.setTime(new Date());
		int minutestohour = 60-calendarnow.get(Calendar.MINUTE);
		if (minutestohour <2) minutestohour += 60;

		// write file to know this happenend on next wakeup
		if (!TIMEDSHUTDOWNFILE.exists())
		    try { TIMEDSHUTDOWNFILE.createNewFile(); } catch (Exception e) {e.printStackTrace();}

		// TODO: send email? (only once?)
		application.driverCallServer(PlayerCommands.powershutdown, Integer.toString(minutestohour*60)); // seconds

		Util.log(TIMEDSHUTDOWNFILE.toString(), this); // TODO: testing
	}
}
