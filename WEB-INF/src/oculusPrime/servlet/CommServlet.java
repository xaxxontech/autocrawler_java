package oculusPrime.servlet;

import oculusPrime.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.*;
import javax.servlet.http.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class CommServlet extends HttpServlet {

	public enum params { logincookie, loginuser, msgfromclient, requestservermsg, loginpass, loginremember, clientid }

	private static volatile List<String> msgFromServer = new ArrayList<>(); // list of JSON strings

	static final long TIMEOUT = 10000; // must be longer than js ping interval (5 seconds)
	volatile long clientRequestID = 0;
	private static Application app = null;
    private static BanList ban = BanList.getRefrence();
	private static State state = State.getReference();
	private static String RESP = "";
	public static String clientaddress = null;
	volatile long clientID = 0;


	public static void setApp(Application a) { app = a; }

	public void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException { doPost(req,res); }

	public void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (request.getParameter(params.clientid.toString()) == null) return;

		Long id = Long.valueOf(request.getParameter(params.clientid.toString()));

        if (request.getParameter(params.logincookie.toString()) != null) {
			reset(request);

            String cookie = getPostData(request);
			logdebug("logincookie: "+cookie, this);
		    String username = app.logintest("", cookie);
            if(username == null) {
            	logdebug("logincookie: sending SC_FORBIDDEN", this);
            	response.sendError(HttpServletResponse.SC_FORBIDDEN);
            	return;
            }

            clientaddress = request.getRemoteAddr();
            ban.clearAddress(clientaddress);
			clientID = id;
            app.driverSignIn(username);
//            msgFromServer.add(RESP);
		}

		else if (request.getParameter(params.loginuser.toString()) != null) {
			reset(request);

		    logdebug("loginpass: "+request.getParameter("loginpass"), this);
            String username = app.logintest(request.getParameter(params.loginuser.toString()),
					request.getParameter(params.loginpass.toString()), request.getParameter(params.loginremember.toString()));
            if(username == null) {
				logdebug("username=null, loginuser: sending SC_FORBIDDEN", this);
            	response.sendError(HttpServletResponse.SC_FORBIDDEN);
            	return;
            }

			clientaddress = request.getRemoteAddr();
			ban.clearAddress(clientaddress);
			clientID = id;
			app.driverSignIn(username);
//            msgFromServer.add(RESP);
        }

		// logins must be above this
        if( ! ban.knownAddress(request.getRemoteAddr())) {
            Util.log("unknown address, blocked, from: "+request.getRemoteAddr(), this);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        if (clientID != id) {
			logdebug("clientID != id", this);
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}


//		if (!state.exists(State.values.driver)) {
//			logdebug("signed out, sending SC_FORBIDDEN", this);
//			response.sendError(HttpServletResponse.SC_FORBIDDEN);
//			return;
//		}

		// incoming msg from client
		if (request.getParameter(params.msgfromclient.toString()) != null) {
			String msg = getPostData(request);
//			logdebug(msg, this);

			JSONParser parser = new JSONParser();
			try {
				JSONObject obj = (JSONObject) parser.parse(msg);

				String fn = (String) obj.get("command");
				String str = (String) obj.get("str");

				logdebug("msgfromclient: "+fn+" "+str, this);

	            app.playerCallServer(fn, str);

	            if (!fn.equals(PlayerCommands.statuscheck.toString()))
	                msgFromServer.add(RESP);

			} catch(Exception e) { e.printStackTrace(); }
		}

		// client requesting server msg, wait for response
		else if (request.getParameter(params.requestservermsg.toString()) != null) {

		}

		sendServerMessage(response);

	}


	void sendServerMessage(HttpServletResponse response) {

		long msgid = newID();
		clientRequestID = msgid ;
		long clID = clientID;
		String msg = null;

		logdebug("sendServerMessage, queue size: "+msgFromServer.size()+", msgid: "+msgid , this);

		long timeout = System.currentTimeMillis() + TIMEOUT;

		// wait for msgFromServer
		while (System.currentTimeMillis() < timeout && msgFromServer.isEmpty() &&  msgid  == clientRequestID && clID == clientID) //  && !reload
			Util.delay(1);

		if (clID != clientID) {
            logdebug("RELOAD", this);
            return;
        }

		if (System.currentTimeMillis() >= timeout && msgid == clientRequestID) {
		    // TODO: logout code here
			logdebug("TIMED OUT", this);
            app.driverSignOut();
			return;
        }

		if (!msgFromServer.isEmpty()) {
			msg = msgFromServer.get(0);
			msgFromServer.remove(0);
			logdebug("msgFromServer read, size: "+msgFromServer.size(), this);
		}

		try {
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			if (msg != null)
			    out.print(msg);
                logdebug("msgid="+msgid+", sendServerMessage: "+msg, this);
			out.close();
		} catch (Exception e) { e.printStackTrace(); }

	}

	private long newID() {
		return System.nanoTime();
	}

	public static void sendToClient(String str, String colour, String status, String value) {

		JSONObject obj = new JSONObject();
		obj.put("str", str);
		obj.put("colour", colour);
		obj.put("status", status);
		obj.put("value", value);

		String msg = obj.toJSONString();
        msgFromServer.add(msg);

//        logdebug("sendToClient: "+msg, "CommServlet.sendToClient()");
    }

    private String getPostData(HttpServletRequest request) {
		StringBuffer jb = new StringBuffer();
		String line = null;
		try {
			BufferedReader reader = request.getReader();
			while ((line = reader.readLine()) != null)
				jb.append(line);
		} catch (Exception e) { /*report an error*/ }

		return jb.toString();
	}

	private void reset(HttpServletRequest request) {

		logdebug("RESET", this);

		ban.removeAddress(request.getRemoteAddr());

		if (clientaddress != null) ban.removeAddress(clientaddress);
	    clientaddress = null;

	    msgFromServer.clear();
        clientRequestID=newID();

    }

    private void logdebug(String str, Object obj) {
//		Util.debug(str, this);
	}
    
}
