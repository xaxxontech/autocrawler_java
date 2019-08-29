var msgcheckpending = false;
var msgssent = 0;
var msgsrcvd = 0;
var msgPollTimeout = null;
var initiallogin = false;
var commclientid = null;
var webrtcinit = false;


function commClientLoaded() {
	commclientid = Date.now();
	// comm_client_log("commClientLoaded, id: "+commclientid); 

	if (/auth=/.test(document.cookie)) { commLoginFromCookie(); }
	else login(); // user input goes out thru commLogin() below
	
	videologo("on");
}

function callServerComm(fn, s) {
	var obj = { command: fn, str: s };
	var msg = JSON.stringify(obj);
	postxmlhttp("comm?msgfromclient", msg);
}

function commLogin(user, pass, remember) {
	initiallogin = true;
	getxmlhttp("comm?loginuser="+user+"&loginpass="+pass+"&loginremember="+remember);
	logintimer = setTimeout("window.location.reload()", logintimeout);
}

function commLoginFromCookie() {
	var str = ""; 
	str = readCookie("auth");
	
	initiallogin = true;
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
			comm_client_log("serverResponse:\n"+xhr.responseText);
	
			if (xhr.responseText == "") { // no msg
				comm_client_log("blank message");
				return;
			}
			
			// if (xhr.responseText == "TIMEOUT") { // no msg
				// comm_client_log("TIMEOUT");
				// return;
			// }	
					
			if (xhr.responseText == "ok") { 
				checkForMsg();
				return;
			}
			
			if (!webrtcinit) {
				webrtcinit = true;
				websocketServerConnect(); // webrtc.js
			}

			var msg = JSON.parse(xhr.responseText);
			
			if (msg.hasOwnProperty('str'))
				message(msg.str, msg.colour, msg.status, msg.value); 
			
			else if (msg.hasOwnProperty('fn')) {
				comm_client_log(msg.params);
				// var params = msg.params.replace(/"/g, "&quot;");
				var params = msg.params.replace(/"/g, "'");
				params = params.replace("\n"," ");
				// var params = msg.params.replace("\n"," ");

				comm_client_log(msg.fn+"(\""+params+"\")");
				eval(msg.fn+"(\""+params+"\")");
			}
			
			checkForMsg();

		}
		
		else if (xhr.status==403) { // forbidden
			initiallogin = false;
			comm_client_log("received 403"); 
			commclientclose();
		}
		
	}
}

function commclientclose() {
	connectionlost();
	websocketServerDisconnect();
}

function checkForMsg() {
	
	if (!initiallogin) return; // msgcheckpending || 
	
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
	
	if (!initiallogin) {
		comm_client_log("getxmlhttp dropped: "+theurl);
		return;
	}
	
	// comm_client_log("getxmlhttp("+theurl+")");
	
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

	if (!initiallogin) {
		comm_client_log("postxmlhttp dropped: "+data);
		return;
	}

	theurl += "&clientid="+commclientid;
	
	// comm_client_log("postxmlhttp("+theurl+", "+data+")");
	
	msgcheckpending = true;

	let pxh=new XMLHttpRequest();

	pxh.onreadystatechange = function() { msgReceived(pxh); }; // event handler function call;
	pxh.open("POST",theurl,true);
	pxh.send(data);
	
	msgssent ++;
	// comm_client_log ("messages sent/received: "+msgssent+", "+msgsrcvd);

}

function comm_client_log(str) {
	// console.log(str);
}
