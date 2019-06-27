package oculusPrime.servlet;

import oculusPrime.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.*;
import javax.servlet.http.*;
import org.json.simple.*;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.ParseException;
import org.json.simple.parser.JSONParser;

public class CommServlet extends HttpServlet {

	public enum params { logincookie, loginuser, msgfromclient, requestservermsg, loginpass, loginremember }

	private static volatile List<String> msgFromServer = new ArrayList<>(); // list of JSON strings

	static final long TIMEOUT = 10000; // must be longer than js ping interval (5 seconds)
	volatile long clientRequestID = 0;
	private static Application app = null;
    private static BanList ban = BanList.getRefrence();
	private static State state = State.getReference();
	private static volatile boolean reload = false;
	private static String RESP = "";

	public static void setApp(Application a) { app = a; }

	public void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException { doPost(req,res); }

	public void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

//        Util.debug("doPost: "+request.getQueryString(), this);

        if (request.getParameter(params.logincookie.toString()) != null) {
			reload();

            String cookie = getPostData(request);
			Util.debug("logincookie: "+cookie, this);
		    String username = app.logintest("", cookie);
            if(username == null) {
            	Util.debug("logincookie: sending SC_FORBIDDEN", this);
            	response.sendError(HttpServletResponse.SC_FORBIDDEN);
            	return;
            }

            ban.clearAddress(request.getRemoteAddr());
            app.driverSignIn(username);
//            msgFromServer.add(RESP);
		}

		else if (request.getParameter(params.loginuser.toString()) != null) {
			reload();

		    Util.debug("loginpass: "+request.getParameter("loginpass"), this);
            String username = app.logintest(request.getParameter(params.loginuser.toString()),
					request.getParameter(params.loginpass.toString()), request.getParameter(params.loginremember.toString()));
            if(username == null) {
				Util.debug("loginuser: sending SC_FORBIDDEN", this);
            	response.sendError(HttpServletResponse.SC_FORBIDDEN);
            	return;
            }

            ban.clearAddress(request.getRemoteAddr());
            app.driverSignIn(username);
//            msgFromServer.add(RESP);
        }

		// logins must be above this
        if( ! ban.knownAddress(request.getRemoteAddr())) {
            Util.log("unknown address, blocked, from: "+request.getRemoteAddr()+", str: "+request.toString(), this);
			Util.debug("sending SC_FORBIDDEN", this);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        if (!state.exists(State.values.driver)) {
			Util.debug("signed out, sending SC_FORBIDDEN", this);
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}

		// incoming msg from client
		if (request.getParameter(params.msgfromclient.toString()) != null) {
			String msg = getPostData(request);
			Util.debug(msg, this);

			JSONParser parser = new JSONParser();
			try {
				JSONObject obj = (JSONObject) parser.parse(msg);

				String fn = (String) obj.get("command");
				String str = (String) obj.get("str");

				Util.debug("msgfromclient: "+fn+" "+str, this);

	            app.driverCallServer(PlayerCommands.valueOf(fn), str);
                msgFromServer.add(RESP);

			} catch(Exception e) { e.printStackTrace(); }
		}

		// client requesting server msg, wait for response
		else if (request.getParameter(params.requestservermsg.toString()) != null) {

		}

		sendServerMessage(response);

	}


	void sendServerMessage(HttpServletResponse response) {

		long id = newID();
		clientRequestID = id;
		String msg = RESP;

		Util.debug("sendServerMessage, queue size: "+msgFromServer.size()+", id: "+id, this);

		long timeout = System.currentTimeMillis() + TIMEOUT;

		// wait for msgFromServer
		while (System.currentTimeMillis() < timeout && msgFromServer.isEmpty() && !reload &&  id == clientRequestID)
			Util.delay(1);

		if (reload) {
            Util.debug("RELOAD", this);
            return;
        }

		if (System.currentTimeMillis() >= timeout && id == clientRequestID) {
		    // TODO: logout code here
			Util.debug("TIMED OUT", this);
			return;
        }

		if (!msgFromServer.isEmpty()) {
			msg = msgFromServer.get(0);
			msgFromServer.remove(0);
			Util.debug("msgFromServer read, size: "+msgFromServer.size(), this);
		}

		try {
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			out.print(msg);
			out.close();
			Util.debug("id="+id+", sendServerMessage: "+msg, this);
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

//        Util.debug("sendToClient: "+msg, "CommServlet.sendToClient()");
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

	private void reload() {
	    reload = true;
	    msgFromServer.clear();
        clientRequestID=newID();
	    Util.delay(10);
	    reload = false;
    }

}
