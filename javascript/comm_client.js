var msgcheckpending = false;
var msgssent = 0;
var msgsrcvd = 0;
var msgPollTimeout = null;
var initiallogin = false;
var commclientid = null;
var webrtcinit = false;


function commClientLoaded() {
	commclientid = Date.now();
	comm_client_log("commClientLoaded, id: "+commclientid); 

	if (/auth=/.test(document.cookie)) { commLoginFromCookie(); }
	else { 
		login(); 
	}
	
	// websocketServerConnect(); // TODO: wait until after successful login
}

function callServerComm(fn, s) {
	// comm_client_log("callServerComm fn: "+fn+", str: "+s);

	var obj = { command: fn, str: s };
	var msg = JSON.stringify(obj);
	// postxmlhttp("comm?msgfromclient="+msg, msg);
	postxmlhttp("comm?msgfromclient", msg);
}

function commLogin(user, pass, remember) {
	// comm_client_log("commLogin");
	initiallogin = true;
	// postxmlhttp("comm?loginuser="+user+"&loginpass="+pass+"&loginremember="+remember);
	getxmlhttp("comm?loginuser="+user+"&loginpass="+pass+"&loginremember="+remember);
	logintimer = setTimeout("window.location.reload()", logintimeout);
}

function commLoginFromCookie() {
	var str = ""; 
	str = readCookie("auth");
	// comm_client_log("commLoginFromCookie: "+str);
	
	initiallogin = true;
	// postxmlhttp("comm?logincookie="+str, str);
	postxmlhttp("comm?logincookie", str);
	logintimer = setTimeout("eraseCookie('auth'); window.location.reload()", logintimeout);
}

function msgReceived(xhr) { 

	if (xhr.readyState==4) {// 4 = "loaded"
		
		msgcheckpending = false;

		if (xhr.status==200) {// 200 = OK
			
			clearTimeout(msgPollTimeout); msgPollTimeout = null;

			// msg recevied
			msgsrcvd ++;
			// comm_client_log ("messages sent/received: "+msgssent+", "+msgsrcvd);
			comm_client_log("serverResponse:\n"+xhr.responseText);
			
			if (xhr.responseText == "") { // no msg
				comm_client_log("blank message");
				// checkForMsg();
				return;
			}

			if (!webrtcinit) {
				webrtcinit = true;
				websocketServerConnect(); // webrtc.js
			}

			var msg = JSON.parse(xhr.responseText);
			
			message(msg.str, msg.colour, msg.status, msg.value); 
			
			checkForMsg();

		}
		
		else if (xhr.status==403) { // forbidden
			commclientclose();
		}
	}
}

function commclientclose() {
	websocketServerDisconnect();
	initiallogin = false;
	connectionlost();
}

function checkForMsg() {
	
	if (msgcheckpending || !initiallogin) return;
	
	msgcheckpending = true;

	getxmlhttp("comm?requestservermsg");
	
}

function getxmlhttp(theurl) {
	
	// if (msgcheckpending) { 
		// setTimeout(function() { getxmlhttp(theurl) }, 100);
		// comm_client_log("getxmlhttp delayed");
		// return; 
	// } 
	if (commclientid == null) return;
	
	theurl += "&clientid="+commclientid;
	
	comm_client_log("getxmlhttp("+theurl+")");
	
	msgcheckpending = true;
	
	let gxh = new XMLHttpRequest(); 
	gxh.onreadystatechange=function() { msgReceived(gxh); } // event handler function call;
	gxh.open("GET",theurl,true);
	gxh.send();
	
}

function postxmlhttp(theurl, data) {
	
	// if (msgcheckpending) { 
		// setTimeout(function() { postxmlhttp(theurl, data) }, 100);
		// comm_client_log("postxmlhttp delayed");
		// return; 
	// } 
	
	if (commclientid == null) return;
	
	theurl += "&clientid="+commclientid;
	
	if (!initiallogin) {
		comm_client_log("postxmlhttp dropped: "+data);
		return;
	}
	
	comm_client_log("postxmlhttp("+theurl+", "+data+")");
	
	msgcheckpending = true;

	let pxh=new XMLHttpRequest();

	pxh.onreadystatechange = function() { msgReceived(pxh); }; // event handler function call;
	pxh.open("POST",theurl,true);
	pxh.send(data);
	
	msgssent ++;
	// comm_client_log ("messages sent/received: "+msgssent+", "+msgsrcvd);

}

function comm_client_log(str) {
	console.log(str);
}
