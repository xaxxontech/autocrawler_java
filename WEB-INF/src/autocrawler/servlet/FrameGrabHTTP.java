package autocrawler.servlet;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Vector;

import javax.imageio.ImageIO;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import developer.Navigation;
import developer.Ros;
import autocrawler.*;
import autocrawler.State.values;

@SuppressWarnings("serial")
@MultipartConfig(fileSizeThreshold=1024*1024*2,    // 2MB
		maxFileSize=1024*1024*10,                  // 10MB
		maxRequestSize=1024*1024*50)               // 50MB

public class FrameGrabHTTP extends HttpServlet {
	
	private static State state = State.getReference();
	private static BanList ban = BanList.getRefrence();  // TODO: PULL DATA FROM LOG FILES
	private static BufferedImage batteryImage = null;
	private static RenderedImage cpuImage = null;
	private static BufferedImage radarImage = null;
	private static Application app = null;
	private static int var = 0;

	private static final int MAX_STATE_HISTORY = 100;
	Vector<String> history = new Vector<String>(MAX_STATE_HISTORY);
	
	public static void setApp(Application a) { app = a; }
	
	public void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException { doPost(req,res); }
	public void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {

		if( ! ban.knownAddress(req.getRemoteAddr())){
			Util.log("unknown address, blocked: "+req.getRemoteAddr(), this);
			return;
		}

        if (req.getParameter("mode") != null) {
        	String mode = req.getParameter("mode");
            
            if (mode.equals("battery")) batteryGrab(req, res);
            else if (mode.equals("cpu")) cpuGrab(req, res);       	
            else if (mode.equals("processedImg"))  processedImg(req,res);
			else if (mode.equals("processedImgJPG"))  processedImgJPG(req,res);
			else if (mode.equals("videoOverlayImg")) videoOverlayImg(req, res);
            else if (mode.equals("rosmap")) {
            	Application.processedImage = Ros.rosmapImg();
				if (!state.exists(State.values.rosmapinfo))
					app.driverCallServer(PlayerCommands.messageclients, "map data unavailable, try starting navigation system");
            	processedImg(req,res);
            }
            else if (mode.equals("rosmapinfo")) { // xmlhttp text
        		res.setContentType("text/html");
        		PrintWriter out = res.getWriter();
        		out.print(Ros.mapinfo());
        		out.close();
            }
            else if (mode.equals("routesload")) {
        		res.setContentType("text/html");
        		PrintWriter out = res.getWriter();
        		out.print(Navigation.routesLoad());
        		out.close();
            }
			else if (mode.equals("rosmapdownload")) {
				res.setContentType("image/x-portable-graymap");
				res.setHeader("Content-Disposition", "attachment; filename=\"map.pgm\"");
				FileInputStream a = new FileInputStream(Ros.getMapFilePath()+Ros.mapfilename);
				while(a.available() > 0)
					res.getWriter().append((char)a.read());
				a.close();
			}
			else if (mode.equals("rosmapupload")) {
				if (!state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.stopped.toString())) {
					app.message("unable to modify map while navigation running", null, null);
					return;
				}
				Part part = req.getParts().iterator().next();
				if (part == null) {
					app.message("problem uploading, map not saved", null, null);
					return;
				}

				File save = new File(Ros.getMapFilePath(), Ros.mapfilename );
				Ros.backUpMappgm();
				part.write(save.getAbsolutePath());
				app.message("map saved as: " + save.getAbsolutePath(), null, null);
			}
			else if (mode.equals("dock")) dockFrameGrab(req, res);
        }
		else {
		    frameGrab(req, res);
		}
	}
	
	private void frameGrab(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		
		res.setContentType("image/jpeg");
		OutputStream out = res.getOutputStream();

        if (!state.getBoolean(State.values.framegrabbusy)) {
            Application.processedImage = null;
        }

		if (app.frameGrab()) {
			
			int n = 0;
			while (state.getBoolean(State.values.framegrabbusy)) {
				Util.delay(5);
				n++;
				if (n> 2000) {  // give up after 10 seconds 
					state.set(State.values.framegrabbusy, false);
					break;
				}
			}

//			if (Application.framegrabimg != null) { // TODO: unused?
//				for (int i=0; i<Application.framegrabimg.length; i++) {
//					out.write(Application.framegrabimg[i]);
//				}
//			}
//
//			else {
            if (Application.processedImage != null) {
					ImageIO.write(Application.processedImage, "JPG", out);
				}
//			}
			
		    out.close();
		}
	}

	// unused, using dockwebrtc ros topic fiducial_images instead
    private void dockFrameGrab(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {

        res.setContentType("image/jpeg");
        OutputStream out = res.getOutputStream();

        if (!state.getBoolean(State.values.framegrabbusy))
            Application.processedImage = null;

        if (app.frameGrab()) {

            int n = 0;
            long start = System.currentTimeMillis();
            while (state.getBoolean(State.values.framegrabbusy) && System.currentTimeMillis() - start < 10000)
                Util.delay(5);

            if (!state.getBoolean(values.framegrabbusy)) {

                if (Application.processedImage != null) {
                    ImageIO.write(Application.processedImage, "JPG", out);
                }
            }
            else  state.set(State.values.framegrabbusy, false);

        } else {
            ImageIO.write(new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), "JPG", out);
        }

        out.close();
    }

	private void processedImg(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		
		if (Application.processedImage == null) return;
		
		// send image
		res.setContentType("image/gif");
		OutputStream out = res.getOutputStream();
		ImageIO.write(Application.processedImage, "GIF", out);
	}

	private void processedImgJPG(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {

		if (Application.processedImage == null) return;

		// send image
		res.setContentType("image/jpg");
		OutputStream out = res.getOutputStream();
		ImageIO.write(Application.processedImage, "JPG", out);
	}

	private void videoOverlayImg(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {

		if (Application.videoOverlayImage == null)
			Application.videoOverlayImage= new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);

		// send image
		res.setContentType("image/jpg");
		OutputStream out = res.getOutputStream();
		ImageIO.write(Application.videoOverlayImage, "JPG", out);
	}

	private void batteryGrab(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		generateBatteryImage();
		res.setContentType("image/gif");
		OutputStream out = res.getOutputStream();
		ImageIO.write(batteryImage, "GIF", out);
	}
	
	private void cpuGrab(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		generateCpuImagemage();
		res.setContentType("image/gif");
		OutputStream out = res.getOutputStream();
		ImageIO.write(cpuImage, "GIF", out);
	} 

	//TODO: STUB ONLY, FILL IN FROM power HISTORY 
	private void generateBatteryImage() {

			final int w = 500;
			final int h = 200;
			BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g2d = image.createGraphics();
			
			//render background
		//	g2d.setColor(new Color(60,60,90));  
		//	g2d.fill(new Rectangle2D.Double(0, 0, w, h));
		//
	    //    g2d.setFont(new Font("Serif", Font.BOLD, 45));
	        String s = "generateBatteryImage";
	        
	        //g2d.drawString(s, 10, h/2);
	        //g2d.drawLine(0, 0, w, h);	
	        g2d.setPaint(Color.red);
	        //g2d.drawLine(0, h/3, w/3, h/3);
	        
			batteryImage = image;
	}

	//TODO: STUB ONLY, FILL IN FROM CPU HISTORY 
	private void generateCpuImagemage() {
 
		final int radius = 6;
		final int w = 500;
		final int h = 100;
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2d = image.createGraphics();
	
		g2d.setPaint(Color.yellow);
        drawCenteredCircle(g2d, 8, 8, radius);
        g2d.drawPolyline(new int[]{10, w/3, w/2, w-radius}, new int[]{10, 20, 90, 50}, 4);
        
        g2d.setPaint(Color.green);
        for( int i = 0 ; i < history.size() ; i++ ){
    //    	Util.log(i + " " +  Integer.parseInt(history.get(i)));
        	drawCenteredCircle(g2d, i*5, Integer.parseInt(history.get(i)), radius);
        }

  
    //    g2d.setPaint(Color.red);
    //    drawCenteredCircle(g2d, w/2, h/2, radius);
        
        g2d.setPaint(Color.red);
   //     drawCenteredCircle(g2d, w-radius, h-radius, radius);
   //     drawCenteredCircle(g2d, 1, 1, 8);
        g2d.drawPolyline(new int[]{5, w-5, w-5, 5, 5}, new int[]{5, 5, h-5, h-5, 5}, 5);
         
		cpuImage = image;
	}
	public void drawCenteredCircle(Graphics2D g, int x, int y, int r) {
		x = x-(r/2);
		y = y-(r/2);
		g.fillOval(x,y,r,r);
	}
	
	/**
	 * @param args download url params, can be null
	 * @return returns download url of saved image
	 */
	public static String saveToFile(String args) {	
		String urlString = "http://127.0.0.1:" + state.get(State.values.httpport) + "/autocrawler/frameGrabHTTP";
		if(args != null) if(args.startsWith("?")) urlString += args; 
		final String url = urlString;
		
		String datetime = Util.getDateStamp();  // && state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString()))  
		if(state.exists(values.roswaypoint) && state.equals(values.navsystemstatus, Ros.navsystemstate.running.toString()))    
			datetime += "_" + state.get(values.roswaypoint).replaceAll(" ", "_");
		
        final String name = datetime + ".jpg";
		new Thread(new Runnable() {
			public void run() {
				new Downloader().FileDownload(url, name, "webapps/autocrawler/framegrabs"); // TODO: EVENT ON NULL ?
			}
		}).start();
		return "/autocrawler/framegrabs/" +name;
	}

	/** add extra text into file name after timestamp */
	public static String saveToFile(final String args, final String optionalname) {
		String urlString = "http://127.0.0.1:" + state.get(State.values.httpport) + "/autocrawler/frameGrabHTTP";
		if(args != null) if(args.startsWith("?")) urlString += args; 
		final String url = urlString;
		
		String datetime = Util.getDateStamp();  // no spaces in filenames       
		if(state.exists(values.roswaypoint)) //  && state.get(State.values.navsystemstatus).equals(Ros.navsystemstate.running.toString())) 
			datetime += "_" + state.get(values.roswaypoint);
		
		if(state.equals(values.dockstatus, AutoDock.DOCKED)) datetime += "_docked"; 
		
        final String name = (datetime + "_"+optionalname + ".jpg").replaceAll(" ", "_"); // no spaces in filenames       
		new Thread(new Runnable() {
			public void run() {
				new Downloader().FileDownload(url, name, "webapps/autocrawler/framegrabs");
			}
		}).start();
		return "/autocrawler/framegrabs/" +name;
	}

}


