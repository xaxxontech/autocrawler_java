package autocrawler;

public enum PlayerCommands { 
	    
    // server
    uptime, restart, quitserver, showlog, writesetting, readsetting, settings, log,
    // not typically used by scripts, undocumented:
    softwareupdate,
    // undocumented
    clientjs,

	// operating system
    reboot, systemshutdown, memory, systemcall, setsystemvolume, cpu, waitforcpu, 
	networkconnect,
	// not typically used by scripts, undocumented:
	networksettings,

	//user, accounts
    who, chat, driverexit, messageclients,
	logout, // undocumented
    
    //undocumented (not typically used by scripts):
    password_update,
    new_user_add, user_list, delete_user, extrauser_password_update, username_update,

    //docking
    dock, autodock,  redock,
	// undocumented
	dockcam,

	// power board
    battstats, powerreset, powercommand,
	// not typically used by scripts, undocumented:
    erroracknowledged,
	// undocumented
	powershutdown, // added timed interval option
    
    // video/audio
    streamsettingscustom, publish,
    streamsettingsset, framegrabtofile,	record,
	// experimental, undocumented:
	jpgstream, streammode,
    
    // malg board 
    malgreset, getdrivingsettings, drivingsettingsupdate, malgcommand,
    // wheels
    clicksteer, motionenabletoggle, speed, move, nudge, forward, backward, left, right, 
    odometrystart, odometryreport, odometrystop, lefttimed, righttimed, forwardtimed,
	arcmove, calibraterotation, rotate,
    // lights and camera tilt
    strobeflash, spotlight, floodlight, cameracommand, camtilt,
	// undocumented (unused):
    fwdflood,

	// autocrawler.navigation
	roslaunch, savewaypoints, gotowaypoint, startnav, stopnav,
	gotodock, saveroute, runroute, cancelroute, startmapping, savemap,

	// sensing
	getlightlevel,
	objectdetect, objectdetectcancel,
	motiondetect, motiondetectcancel, sounddetect,
	// not typically used by scripts, undocumented:
	objectdetectstream, motiondetectstream,

	// un-categorized
	speech, email, state, rssadd,

    // experimental (undocumented)
    test,

    // deprecated (kept for mobile client compatibility, undocumented)
    spotlightsetbrightness,
    
    // undocumented    
    statuscheck, block, unblock, getemailsettings, emailsettingsupdate,
    
    // file manage 
	deletelogs, truncmedia, archivelogs, archivenavigation, 
	
    ;
	
	// sub-set that are restricted to "user0"
	private enum AdminCommands {
		docklineposupdate, autodockcalibrate, getemailsettings, emailsettingsupdate,
		getdrivingsettings, drivingsettingsupdate,  
		systemcall, 
		new_user_add, user_list, delete_user, extrauser_password_update, username_update, 
		showlog, softwareupdate,
		arduinoreset, muterovmiconmovetoggle, 
	    writesetting, holdservo, opennisensor, videosoundmode, restart, shutdown,
	    setstreamactivitythreshold, email, state, uptime, help, memory, who, 
	    loginrecords, settings, messageclients, dockgrabtest, rssaddb, block, 
	    unblock, powershutdown, reboot, systemshutdown, clearmap, erroracknowledged,
		networksettings, networkconnect, test,

		;

	}

	// @return true if given command is in the sub-set
	public static boolean requiresAdmin(final PlayerCommands cmd) {
		try {
			AdminCommands.valueOf(cmd.name());
		} catch (Exception e) {return false;}
		
		return true; 
	}	
}
