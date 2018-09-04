package cameraNode;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class UnicastListenerThread extends Thread {
	protected DatagramSocket uSocket = null;
	protected DatagramPacket packet;
	String nodeName;
		
	public UnicastListenerThread() throws IOException { 
		super();
	}

	public void run() {		
		while (uSocket == null) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		for (;;){ 
			byte[] buf = new byte[256];
			packet = new DatagramPacket(buf, buf.length);

			try {
				uSocket.receive(packet);
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			String received = new String(packet.getData(), 0, packet.getLength());
			String split[] = received.split(" ");
			
			if (split[0].contains("setElected")) {
				String leaderId = split[1];
				try {
					CameraNode.setElectedMessage(leaderId);
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			
			else if (split[0].contains("startElection")){
				long hash = Integer.parseInt(split[1]);
				try {
					CameraNode.startElectionMessage(hash);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

			else if (split[0].contains("bwChangeRequest")){
				int camId = Integer.parseInt(split[1]);
				int bwRequest = Integer.parseInt(split[2]);
				boolean predecessorResponse = Boolean.parseBoolean(split[3]);
   			    try {
					CameraNode.checkBwChangeRequest(camId, bwRequest, predecessorResponse);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			
			else if (split[0].contains("keepAlive")) {
				String node = split[1];
				CameraNode.networkSetup.testDiscoveryLoopComplete(node);
			}

		}
	}
	
    public void setSocket (DatagramSocket socket) {
		uSocket = socket;
	}

}
