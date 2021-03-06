 package autocrawler;

import java.util.Properties;

public enum ManualSettings {

	malgport, powerport, developer, debugenabled, wheeldiameter,
	gyrocomp, alertsenabled, odomturnpwm, odomlinearpwm, checkaddresses,
	motionthreshold, redockifweakconnection,
	arcmovecomp, usearcmoves, arcpwmthreshold, undockdistance, updatelocation,
	lowbattery,	timedshutdown, camhold, soundthreshold, webrtcserver,
	webrtcport, turnserverlogin, turnserverport, dockangle, dockoffset, navfloorscan,
	safeundock,

	// undocumented
	ros2

	;
	
	/** get basic settings, set defaults for all */
	public static Properties createDeaults(){
		Properties config = new Properties();
		config.setProperty(developer.name(), Settings.FALSE);
		config.setProperty(debugenabled.name(), Settings.FALSE);
		config.setProperty(malgport.name(), Settings.ENABLED);
		config.setProperty(powerport.name(), Settings.ENABLED);
		config.setProperty(checkaddresses.name(), Settings.TRUE); // TODO: nuke?!
		config.setProperty(wheeldiameter.name(), "106");
		config.setProperty(gyrocomp.name() , "1.095");
		config.setProperty(alertsenabled.name() , Settings.TRUE);
		config.setProperty(motionthreshold.name(), "0.003");
		config.setProperty(odomlinearpwm.name(), "150");
		config.setProperty(odomturnpwm.name(), "110");
		config.setProperty(redockifweakconnection.name(), Settings.TRUE);
		config.setProperty(dockangle.name(), "2.0");
		config.setProperty(dockoffset.name(), "0.018");
		config.setProperty(arcmovecomp.name(), "0.8");
		config.setProperty(usearcmoves.name(), Settings.TRUE);
		config.setProperty(arcpwmthreshold.name(), "200");
		config.setProperty(soundthreshold.name(), "-25");
		config.setProperty(undockdistance.name(), "0.5");
		config.setProperty(safeundock.name(), Settings.FALSE);
		config.setProperty(lowbattery.name(), "30");
        config.setProperty(timedshutdown.name(), Settings.TRUE);
		config.setProperty(camhold.name(), Settings.FALSE);
        config.setProperty(webrtcserver.name(), "xaxxon.com");
        config.setProperty(webrtcport.name(), "8443");
        config.setProperty(turnserverlogin.name(), "turn:server");
        config.setProperty(turnserverport.name(), "3478");
		config.setProperty(ros2.name(), Settings.FALSE);
		config.setProperty(navfloorscan.name(), Settings.TRUE);
		config.setProperty(updatelocation.name(), "https://www.xaxxon.com/downloads/"); // trailing slash required
        return config;
	}
	
	public static String getDefault(ManualSettings setting){
		Properties defaults = createDeaults();
		return defaults.getProperty(setting.name());
	}
	
	public static boolean isDefault(ManualSettings manual){
		Settings settings = Settings.getReference();
		if(settings.readSetting(manual).equals(getDefault(manual))) return true;
		
		return false;
	}
}
