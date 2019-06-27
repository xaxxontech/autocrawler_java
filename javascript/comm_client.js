var msgcheckpending = false;
var msgssent = 0;
var msgsrcvd = 0;
var msgPollTimeout = null;
var initiallogin = false;

// TODO: nuke below 2 lines:
// getxmlhttp("/oculusPrime/webRTCServlet?clearvars");
// msgPollTimeout = setTimeout("checkForMsg();", MSGPOLLINTERVAL);

function commClientLoaded() {
	console.log("commClientLoaded 2"); 
	if (/auth=/.test(document.cookie)) { commLoginFromCookie(); }
	else { 
		login(); 
	}
}

function callServerComm(fn, s) {
	console.log("callServerComm fn: "+fn+", str: "+s);

	var obj = { command: fn, str: s };
	var msg = JSON.stringify(obj);
	postxmlhttp("comm?msgfromclient="+msg, msg);
}

function commLogin(user, pass, remember) {
	console.log("commLogin");
	initiallogin = true;
	postxmlhttp("comm?loginuser="+user+"&loginpass="+pass+"&loginremember="+remember);
	// logintimer = setTimeout("window.location.reload()", logintimeout);
}

function commLoginFromCookie() {
	var str = ""; 
	str = readCookie("auth");
	console.log("commLoginFromCookie: "+str);
	
	initiallogin = true;
	postxmlhttp("comm?logincookie="+str, str);
	// logintimer = setTimeout("eraseCookie('auth'); window.location.reload()", logintimeout);
}


function msgReceived(xhr) { 

	if (xhr.readyState==4) {// 4 = "loaded"
		
		msgcheckpending = false;

		if (xhr.status==200) {// 200 = OK
			
			clearTimeout(msgPollTimeout); msgPollTimeout = null;

			// msg recevied
			msgsrcvd ++;
			console.log ("messages sent/received: "+msgssent+", "+msgsrcvd);
			console.log("serverResponse:\n"+xhr.responseText);
			
			if (xhr.responseText == "") { // no msg
				console.log("blank message");
				// checkForMsg();
				return;
			}

			var msg = JSON.parse(xhr.responseText);
			
			message(msg.str, msg.colour, msg.status, msg.value); 
			
			checkForMsg();

		}
	}
}

function checkForMsg() {
	
	if (msgcheckpending || !initiallogin) return;
	
	msgcheckpending = true;

	getxmlhttp("comm?requestservermsg");
	
}

function getxmlhttp(theurl) {
	
	// if (msgcheckpending) { 
		// setTimeout(function() { getxmlhttp(theurl) }, 100);
		// console.log("getxmlhttp delayed");
		// return; 
	// } 
	
	console.log("getxmlhttp("+theurl+")");
	
	msgcheckpending = true;
	
	let gxh = new XMLHttpRequest(); 
	gxh.onreadystatechange=function() { msgReceived(gxh); } // event handler function call;
	gxh.open("GET",theurl,true);
	gxh.send();
	
}

function postxmlhttp(theurl, data) {
	
	// if (msgcheckpending) { 
		// setTimeout(function() { postxmlhttp(theurl, data) }, 100);
		// console.log("postxmlhttp delayed");
		// return; 
	// } 
	
	if (!initiallogin) {
		console.log("postxmlhttp dropped: "+data);
		return;
	}
	
	console.log("postxmlhttp("+theurl+", "+data+")");
	
	msgcheckpending = true;

	let pxh=new XMLHttpRequest();

	pxh.onreadystatechange = function() { msgReceived(pxh); }; // event handler function call;
	pxh.open("POST",theurl,true);
	pxh.send(data);
	
	msgssent ++;
	console.log ("messages sent/received: "+msgssent+", "+msgsrcvd);

}
