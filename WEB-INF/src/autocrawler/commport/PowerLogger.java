package autocrawler.commport;

import autocrawler.Settings;

import java.io.RandomAccessFile;
import java.util.Date;

public class PowerLogger {

	public final static String powerlog = Settings.redhome + "/log/power.log";
	private static RandomAccessFile logger = null;
	
	private static void init() {		
		try {
			logger = new RandomAccessFile(powerlog, "rw");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void append(String msg, Object c) {
		
		if(logger == null) init();

		// highly redundant to put class name in log 
		String data = /* c.getClass().getName().toLowerCase() + ", " + */ msg;
		try {
			logger.seek(logger.length());
			logger.writeBytes(new Date().toString() + ", " + data + "\r\n");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public static void close() {	
		try {		
			logger.close();
			logger = null;
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
