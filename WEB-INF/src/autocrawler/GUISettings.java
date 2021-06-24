package autocrawler;

import java.util.Properties;

public enum GUISettings {

	/** these settings must be available in basic configuration */
	speedslow, speedmed, nudgedelay, fullrotationdelay, onemeterdelay,
	vlow, vmed, vhigh, vcustom, vset, maxclicknudgedelay, steeringcomp,
	maxclickcam, loginnotify, redock, telnetport,
	email_smtp_server, email_smtp_port, email_username, email_password, email_from_address, email_to_address,
	volume, reboot, camhoriz,
	cammin, cammax, reversesteeringcomp // undocumented
	;
	
	/** get basic settings */
	public static Properties createDeaults() {
		Properties config = new Properties();
		config.setProperty(speedslow.name() , "55");
		config.setProperty(speedmed.name() , "150");
		config.setProperty(vlow.name() , "320_240_30_64");
		config.setProperty(vmed.name() , "640_480_15_256");
		config.setProperty(vhigh.name() , "1280_720_15_512");
		config.setProperty(vcustom.name() , "1920_1080_15_1024");
		config.setProperty(vset.name() , "vmed");
		config.setProperty(maxclicknudgedelay.name() , "180");
		config.setProperty(nudgedelay.name() , "80");
		config.setProperty(maxclickcam.name() , "30");
		config.setProperty(volume.name() , "100");
		config.setProperty(reboot.name() , Settings.FALSE);
		config.setProperty(fullrotationdelay.name(), "3800");
		config.setProperty(onemeterdelay.name(), "2400");
		config.setProperty(steeringcomp.name(), "L20");
		config.setProperty(reversesteeringcomp.name(), "R20");
		config.setProperty(cammax.name(), "95");
		config.setProperty(camhoriz.name(), "70");
		config.setProperty(cammin.name(), "52");
		config.setProperty(loginnotify.name() , Settings.FALSE);
		config.setProperty(redock.name() , Settings.FALSE);
		config.setProperty(email_smtp_server.name(), Settings.DISABLED);
		config.setProperty(email_smtp_port.name(), "25");
		config.setProperty(email_username.name(), Settings.DISABLED);
		config.setProperty(email_password.name(), Settings.DISABLED);
		config.setProperty(email_from_address.name(), Settings.DISABLED);
		config.setProperty(email_to_address.name(), Settings.DISABLED);
		config.setProperty(telnetport.name(), "4444");

		return config;
	}
	
	public static String getDefault(GUISettings factory) {
		Properties defaults = createDeaults();
		return defaults.getProperty(factory.name() );	
	}
	
}
