package autocrawler.developer.swingtool;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import autocrawler.PlayerCommands;
import autocrawler.State;
import autocrawler.Util;

//todo: scollpane tabbed .. count state vars use
//         button panel 
//         history up arrows
// 		
// high res monitors: https://stackoverflow.com/questions/26877517/java-swing-on-high-dpi-screen
//
public class SwingTerminal extends JFrame {

	private static final long serialVersionUID = 1L;
	final long DELAY = 5000; 

	DefaultListModel<String> listModel = new DefaultListModel<String>();
	JList<String> list = new JList<String>(listModel);
	
	JToggleButton pause = new JToggleButton("waiting", true);
	JTextArea messages = new JTextArea();	
	JTextField in = new JTextField();
	Vector<String> ignore = new Vector<String>();
	BufferedReader reader = null;
	PrintWriter printer = null;
	Socket socket = null;	
	String version = null;
	String exclude = null;
	int rx, tx, historyPointer, statePointer = 0;
	String ip;
	int port;

	final int MAX_HISTORY = 20;
	Vector<String> history = new Vector<String>(MAX_HISTORY);


	Vector<String> filter = getFilter();
	Vector<String> getFilter() {
		HashSet<String> init = new HashSet<String>();
		init.add("Connection timed out: connect");
		init.add("<messageclient> <status> battery");
		init.add("distanceangle");
		init.add("volts");
		init.add("cpu");
		return new Vector<String>(init);
	}
	
	public SwingTerminal(String ip, int port){ 
		
		this.ip = ip;
		this.port = port;
		// this.exclude = exclude;
		
		// hack
		/*
		history.add("runroute red");
		history.add("stopnav");
		history.add("gotodock");
		*/
		
		setDefaultLookAndFeelDecorated(true);
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setLayout(new BorderLayout());

		messages.setFont(new Font("serif", Font.PLAIN, 25));
		list.setFont(new Font("serif", Font.PLAIN, 25));
		in.setFont(new Font("serif", Font.PLAIN, 25));
		setFont(new Font("serif", Font.PLAIN, 25));
		
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.addListSelectionListener(new ListSelectionListener() {	
			@Override // update text in input window 
			public void valueChanged(ListSelectionEvent e) {
				if(e.getValueIsAdjusting() == false) {
					if(list.getSelectedIndex() != -1){
						in.setText(list.getSelectedValue().toString() + " ");
						in.setCaretPosition(in.getText().length());
					}
			    }
			}
		});
		
		list.addKeyListener(new KeyListener() {
			@Override public void keyTyped(KeyEvent arg) {}
			@Override public void keyReleased(KeyEvent arg) {}
			@Override // enter key 
			public void keyPressed(KeyEvent arg) {
				
				if(arg.getKeyCode() == 10){				
					if(list.getSelectedIndex() != -1){
						sendCommand(in.getText().trim());
					}
				}
			}
		});
		
		list.addMouseListener( new MouseListener() {
			@Override public void mouseReleased(MouseEvent arg) {}
			@Override public void mousePressed(MouseEvent arg) {}
			@Override public void mouseExited(MouseEvent arg) {}
			@Override public void mouseEntered(MouseEvent arg) {}
			@Override // double clicked 
			public void mouseClicked(MouseEvent arg) {
				if(arg.getClickCount() == 2){			
					if(list.getSelectedIndex() != -1){
						sendCommand(in.getText().trim());
					}
				}
			}
		});
		
		pause.addItemListener( new ItemListener() {
			@Override
		    public void itemStateChanged(ItemEvent eve) {  
				updateButtonText();
		    }  
		});


		PlayerCommands[] cmds = PlayerCommands.values();
//		nonTelnetBroadcast[] cmds = State.nonTelnetBroadcast.values();
		String[] c = new String[cmds.length];
		for(int i = 0; i < cmds.length; i++) {
			c[i] = cmds[i].toString();
		}
		
		Arrays.sort(c); // sort doesn't work unless create string array?
		for(int i = 0; i < c.length; i++) {
			System.out.println(c[i]);
			listModel.addElement(c[i].toString());
		}
		
		JScrollPane listScrollPane = new JScrollPane(list);
		JScrollPane chatScroller = new JScrollPane(messages);
		JScrollPane cmdsScroller = new JScrollPane(listScrollPane);

		chatScroller.setPreferredSize(new Dimension(700, 800));
		cmdsScroller.setPreferredSize(new Dimension(350, 800));
		getContentPane().add(chatScroller, BorderLayout.LINE_END);
		getContentPane().add(cmdsScroller, BorderLayout.LINE_START);

		getContentPane().add(in, BorderLayout.PAGE_END);
		getContentPane().add(pause, BorderLayout.PAGE_START);
		chatScroller.setFocusable(false);
		
		in.addKeyListener(new KeyListener() {
			@Override public void keyPressed(KeyEvent arg) {
								
				if (arg.getKeyCode() == 38) {
					if (historyPointer == history.size()-1) historyPointer = 0;
					else historyPointer++;
					in.setText(history.get(historyPointer));
					// System.out.println(arg.getKeyCode() + " ++ " + historyPointer + " " + history.size());
					// System.out.println(history.toString());
				}
				
				if (arg.getKeyCode() == 40) {
					if (historyPointer == 0) historyPointer = history.size()-1;
					else historyPointer--;
					in.setText(history.get(historyPointer));
					// System.out.println(arg.getKeyCode() + " -- " + historyPointer + " " + history.size());
					// System.out.println(history.toString());

				}
				
				if (arg.getKeyCode() == 39) {
					if (statePointer == State.values.values().length-1) statePointer = 0;
					statePointer++;
					in.setText("state " + State.values.values()[statePointer] + " ");
				}
				
				if (arg.getKeyCode() == 37) {
					if (statePointer == 0) statePointer = State.values.values().length-1;
					else statePointer--;
					in.setText("state " + State.values.values()[statePointer] + " ");
				}
			}
			
			@Override public void keyReleased(KeyEvent arg) {}
			@Override public void keyTyped(KeyEvent e) {
				if(e.getKeyChar() == '\n' || e.getKeyChar() == '\r') 
					sendCommand(in.getText().trim());
			}
		});
		
		// show the Swing gui 
		pack();
		setVisible(true);
	//	setResizable(false);
		
		// start timer watch dog 
		new Timer().scheduleAtFixedRate(new Task(), 0, DELAY);
	//	importFile();
	}
	
	/*
	private void importFile(){
		
		if (exclude == null) {
			exclude = " filtering dissabled";
			return;
		}
		
		if ( ! new File(exclude).exists()) {
			exclude = " filtering dissabled";
			return;
		}
	
		try {
			
			String line;
			FileInputStream filein = new FileInputStream(exclude);
			BufferedReader reader = new BufferedReader(new InputStreamReader(filein));
			while ((line = reader.readLine()) != null) {
				
				ignore.add(line.trim());
				
			}
			reader.close();
			filein.close();
			
		} catch (Exception e) {
			// System.out.println("importFile: " + e.getMessage());
			exclude = "file error";
			return;
		}
		
		exclude = new File(exclude).getName(); // just display file name if big path 
	}
	*/
	
	private class Task extends TimerTask {
		public void run(){
			if(printer == null || socket.isClosed()){
				
				openSocket();
				try { Thread.sleep(5000); } catch (InterruptedException e) {}
				if(socket != null) if(socket.isConnected()) readSocket();
					
			} else {
				try {
					printer.checkError();
					printer.flush();
					printer.println("\n\n\n"); 
					// send dummy message to test the connection
					
				} catch (Exception e) {
					appendMessages("TimerTask(): "+e.getMessage());
					closeSocket();
				}
			}
		}
	}
	
	void openSocket(){	
		try {	
			setTitle("\t\t.... Trying to connect");
			socket = new Socket(ip, port);
			printer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
			reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			appendMessages("openSocket(): connected to: " + socket.getInetAddress().toString());
		} catch (Exception e) {
			setTitle("\t\t.... Disconnected");
			appendMessages("openSocket(): " + e.getMessage());
			closeSocket();
		}
	}
	
	void closeSocket(){
		if(printer != null){
			printer.close();
			printer = null;
		}
		if(reader != null){
			try {
				reader.close();
				reader = null;
			} catch (IOException e) {
				appendMessages("closeSocket(): " + e.getLocalizedMessage());
			}
		}
		try { 
			if(socket != null) socket.close(); 
		} catch (IOException ex) {
			appendMessages("closeSocket(): " + ex.getLocalizedMessage());
		}
	}

	void updateButtonText() {
		
		if( pause.isSelected()) {
			
			pause.setText("Click to pause.." + " rx: " + rx + " tx: " + tx );
			
		} else {
			
			pause.setText("Paused..........." + " rx: " + rx++ + " tx: " + tx );

		}
		
	}
	
	void readSocket(){	
		new Thread(new Runnable() { public void run() {
			String input = null;
			while(printer != null) {
				try {
					input = reader.readLine();
					if(input == null) {
						appendMessages("readSocket(): closing..");
						try { Thread.sleep(5000); } catch (InterruptedException e) {}
						closeSocket();
						break;
					}
					
					// ignore dummy messages 
					input = input.trim();
					if(input.length() > 0) {
						
						rx++;
						updateButtonText();
						appendMessages( input );
					}
				} catch (Exception e) {
					appendMessages("readSocket(): "+e.getMessage());
					closeSocket();
				}
			}
		}}).start();
	}
	
	void sendCommand(final String input){		
		if(printer == null){
			appendMessages("sendCommand(): not connected");
			return;
		}
	
		tx++;
		updateButtonText();
		try {
			printer.checkError();
			printer.println(input);
		} catch (Exception e) {
			// appendMessages("sendCommand(): "+e.getMessage());
			closeSocket();
		}
		

		while (history.size() > MAX_HISTORY) history.remove(0);
		if (!history.contains(input)) history.add(input);
		
		System.out.println(history.toString());
		
		in.setText(""); // reset text input field better? 
	}
	
	void appendMessages(final String input){
		
		if( input.toLowerCase().contains("welcome")) {
			
			version = input.replace("<telnet>", "");
		
			setTitle( version + " [" +socket.getInetAddress().toString() + "] " + exclude);

			return;
			
		}
	
		/*	
		for( int i = 0 ; i < ignore.size() ; i++ ) {
			 if (input.contains(ignore.get(i))) {
				System.out.println("ignored.. " + input + " [" + ignore.get(i)+"]");
				return;
			}
		} 
		
		if (filter.contains(input)) {
			System.out.println("filtered.. " + input );
			return;
		}
		
		System.out.println("in.. " + input );
		*/
		for( int i = 0 ; i < filter.size() ; i++ ) {
			 if (input.contains(filter.get(i))) {
				// System.out.println("filtered.. " + input + " [" + filter.get(i)+"]");
				return;
			}
		}
		
		
		if( pause.isSelected()) {
			messages.append(Util.getDateStampShort().replace("-", " : ") + "       " + input + "\n");
			messages.setCaretPosition(messages.getDocument().getLength());
		}
	}
	
	public static void main(String[] args) {
	
		
		if (args.length == 2) {
			javax.swing.SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					new SwingTerminal(args[0], Integer.parseInt(args[1]));
				}
			});
		}
		
		if (args.length == 1) {
			javax.swing.SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					new SwingTerminal(args[0], 4444);
				}
			});
		}
		
		if (args.length == 0) {
			javax.swing.SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					new SwingTerminal("crawler", 4444);
				}
			});
		}
	}
}
