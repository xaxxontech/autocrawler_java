/* vim: set sts=4 sw=4 et :
 *
 * Demo Javascript app for negotiating and streaming a sendrecv webrtc stream
 * with a GStreamer app. Runs only in passive mode, i.e., responds to offers
 * with answers, exchanges ICE candidates, and streams.
 *
 * Author: Nirbheek Chauhan <nirbheek@centricular.com>
 */

// Set this to override the automatic detection in websocketServerConnect()
var ws_server="xaxxon.com";
var ws_port;
// Set this to use a specific peer id instead of a random one
var default_peer_id; // =777;
// Override with your own STUN servers if you want
// var rtc_configuration = {iceServers: [{urls: "stun:stun.services.mozilla.com"},
                                      // {urls: "stun:stun.l.google.com:19302"}]};
var rtc_configuration = {iceServers: [{urls: "stun:stun.l.google.com:19302"},
                                      {urls: "turn:"+window.location.hostname+":3478",
										  username: "auto",
										  credential: "robot"
										  }]};
// The default constraints that will be attempted. Can be overriden by the user.
// var default_constraints = {video: false, audio: false};

var connect_attempts = 0;
var peer_connection;
var send_channel;
var ws_conn;
// Promise for local stream after constraints are approved by the user
var local_stream_promise;
var ws_conn_close_forever = false;

function getOurId() {
	if (commclientid) return commclientid;
    return Math.floor(Math.random() * (900000000 - 10) + 10).toString();
}

function resetState() {
    // This will call onServerClose()
    ws_conn.close();
}

function handleIncomingError(error) {
    setError("ERROR: " + error);
    resetState();
}

function getVideoElement() {
    return document.getElementById("stream");
}

function webrtcStatus(text) {
    webrtc_log(text);

    /*
    var span = document.getElementById("status")
    // Don't set the status if it already contains an error
    if (!span.classList.contains('error'))
        span.textContent = text;
	*/
}

function setError(text) {
    console.error(text);
    
    // var span = document.getElementById("status")
    // span.textContent = text;
    // span.classList.add('error');
}

function resetVideo() {
    // Release the webcam and mic
    if (local_stream_promise)
        local_stream_promise.then(stream => {
            if (stream) {
                stream.getTracks().forEach(function (track) { track.stop(); });
            }
        });

    // Reset the video element and stop showing the last received frame
    var videoElement = getVideoElement();
    videoElement.pause();
    videoElement.src = "";
    videoElement.load();
}

// SDP offer received from peer, set remote description and create an answer
function onIncomingSDP(sdp) {
    peer_connection.setRemoteDescription(sdp).then(() => {
        webrtcStatus("Remote SDP set");
        if (sdp.type != "offer")
            return;
        webrtcStatus("Got SDP offer, creating answer");
		peer_connection.createAnswer().then(onLocalDescription).catch(setError);
    }).catch(setError);
}

// Local description was set, send it to peer
function onLocalDescription(desc) {
    webrtc_log("Got local description: " + JSON.stringify(desc));
    peer_connection.setLocalDescription(desc).then(function() {
        webrtcStatus("Sending SDP answer");
        sdp = {'sdp': peer_connection.localDescription}
        ws_conn.send(JSON.stringify(sdp));
    });
}

// ICE candidate received from peer, add it to the peer connection
function onIncomingICE(ice) {
    var candidate = new RTCIceCandidate(ice);
    peer_connection.addIceCandidate(candidate).catch(setError);
}

function onServerMessage(event) {
    webrtc_log("Received " + event.data);
    switch (event.data) {
        case "HELLO":
            webrtcStatus("Registered with server, waiting for call");
            return;
        default:
            if (event.data.startsWith("ERROR")) {
                handleIncomingError(event.data);
                return;
            }
            // Handle incoming JSON SDP and ICE messages
            try {
                msg = JSON.parse(event.data);
            } catch (e) {
                if (e instanceof SyntaxError) {
                    handleIncomingError("Error parsing incoming JSON: " + event.data);
                } else {
                    handleIncomingError("Unknown error parsing response: " + event.data);
                }
                return;
            }

            // Incoming JSON signals the beginning of a call
            if (!peer_connection)
                createCall(msg);

            if (msg.sdp != null) {
                onIncomingSDP(msg.sdp);
            } else if (msg.ice != null) {
                onIncomingICE(msg.ice);
            } else {
                handleIncomingError("Unknown incoming JSON: " + msg);
            }
    }
}

function onServerClose(event) {
    webrtcStatus('Disconnected from server');
    resetVideo();

    if (peer_connection) {
        peer_connection.close();
        peer_connection = null;
    }

    // Reset after a second
    window.setTimeout(websocketServerConnect, 1000);
}

function onServerError(event) {
    setError("Unable to connect to server, did you add an exception for the certificate?")
    // Retry after 3 seconds
    window.setTimeout(websocketServerConnect, 3000);
}

/*
function getLocalStream() {
    // var constraints;
    // var textarea = document.getElementById('constraints');
    // try {
        // constraints = JSON.parse(textarea.value);
    // } catch (e) {
        // console.error(e);
        // setError('ERROR parsing constraints: ' + e.message + ', using default constraints');
        // constraints = default_constraints;
    // }
    // webrtc_log(JSON.stringify(constraints));

	constraints = default_constraints;
    // Add local stream
    if (navigator.mediaDevices.getUserMedia) {
        return navigator.mediaDevices.getUserMedia(constraints);
    } else {
        errorUserMediaHandler();
    }
}
*/

function websocketServerConnect() {
	if (ws_conn_close_forever) return;
	
    connect_attempts++;
    if (connect_attempts > 3) {
        setError("Too many connection attempts, aborting. Refresh page to try again");
        message("No connection to signalling server at "+ws_server, "red");
        ws_conn_close_forever = true;
        return;
    }
    /* Clear errors in the status span */
    // var span = document.getElementById("status");
    // span.classList.remove('error');
    // span.textContent = '';
    /* Populate constraints */
    // var textarea = document.getElementById('constraints');
    // if (textarea.value == '')
        // textarea.value = JSON.stringify(default_constraints);
    // Fetch the peer id to use
    peer_id = default_peer_id || getOurId();
    webrtc_log("using peer id: "+peer_id);
    ws_port = ws_port || '8443';
    if (window.location.protocol.startsWith ("file")) {
        ws_server = ws_server || "127.0.0.1";
    } else if (window.location.protocol.startsWith ("http")) {
        ws_server = ws_server || window.location.hostname;
    } else {
        throw new Error ("Don't know how to connect to the signalling server with uri" + window.location);
    }
    var ws_url = 'wss://' + ws_server + ':' + ws_port
    webrtcStatus("Connecting to server " + ws_url);
    ws_conn = new WebSocket(ws_url);
    /* When connected, immediately register with the server */
    ws_conn.addEventListener('open', (event) => {
        // document.getElementById("peer-id").textContent = peer_id;
        ws_conn.send('HELLO ' + peer_id);
        webrtcStatus("Registering with server");
    });
    ws_conn.addEventListener('error', onServerError);
    ws_conn.addEventListener('message', onServerMessage);
    ws_conn.addEventListener('close', onServerClose);
}

function websocketServerDisconnect() {
	ws_conn.close();
	ws_conn_close_forever = true;
}

function onRemoteTrack(event) {
    if (getVideoElement().srcObject !== event.streams[0]) {
        webrtc_log('Incoming stream');
        getVideoElement().srcObject = event.streams[0];
        videologo("off");
    }
}

function errorUserMediaHandler() {
    setError("Browser doesn't support getUserMedia!");
}

const handleDataChannelOpen = (event) =>{
    webrtc_log("dataChannel.OnOpen", event);
};

// const handleDataChannelMessageReceived = (event) =>{
    // webrtc_log("dataChannel.OnMessage:", event, event.data.type);

    // webrtcStatus("Received data channel message");
    // if (typeof event.data === 'string' || event.data instanceof String) {
        // webrtc_log('Incoming string message: ' + event.data);
        // textarea = document.getElementById("text")
        // textarea.value = textarea.value + '\n' + event.data
    // } else {
        // webrtc_log('Incoming data message');
    // }
    // send_channel.send("Hi! (from browser)");
// };

// const handleDataChannelError = (error) =>{
    // console.log("dataChannel.OnError:", error);
// };

// const handleDataChannelClose = (event) =>{
    // webrtc_log("dataChannel.OnClose", event);
// };

// function onDataChannel(event) {
    // webrtcStatus("Data channel created");
    // let receiveChannel = event.channel;
    // receiveChannel.onopen = handleDataChannelOpen;
    // receiveChannel.onmessage = handleDataChannelMessageReceived;
    // receiveChannel.onerror = handleDataChannelError;
    // receiveChannel.onclose = handleDataChannelClose;
// }

function createCall(msg) {
    // Reset connection attempts because we connected successfully
    connect_attempts = 0;

    webrtc_log('Creating RTCPeerConnection');

    peer_connection = new RTCPeerConnection(rtc_configuration);
    // send_channel = peer_connection.createDataChannel('label', null);
    // send_channel.onopen = handleDataChannelOpen;
    // send_channel.onmessage = handleDataChannelMessageReceived;
    // send_channel.onerror = handleDataChannelError;
    // send_channel.onclose = handleDataChannelClose;
    // peer_connection.ondatachannel = onDataChannel;
    peer_connection.ontrack = onRemoteTrack;
    /* Send our video/audio to the other peer */
    // local_stream_promise = getLocalStream().then((stream) => {
        // webrtc_log('Adding local stream');
        // peer_connection.addStream(stream);
        // return stream;
    // }).catch(setError);

    if (!msg.sdp) {
        console.log("WARNING: First message wasn't an SDP message!?");
    }

    peer_connection.onicecandidate = (event) => {
	// We have a candidate, send it to the remote party with the
	// same uuid
	if (event.candidate == null) {
            webrtc_log("ICE Candidate was null, done");
            return;
	}
	ws_conn.send(JSON.stringify({'ice': event.candidate}));
    };

    webrtcStatus("Created peer connection for call, waiting for SDP");
}

function webrtc_log(str) {
	// console.log(str);
}
